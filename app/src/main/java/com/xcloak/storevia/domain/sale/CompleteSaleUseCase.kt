package com.xcloak.storevia.domain.sale

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.*
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.core.money.DiscountAllocator
import com.xcloak.storevia.core.money.GstCalculator
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.money.Rounding
import java.time.LocalDate
import javax.inject.Inject

data class CartItem(
    val productId: String,
    val qtyMilli: Long,
    val unitPricePaise: Long,
    val discountPaise: Long = 0,
)

data class PaymentInput(val method: PaymentMethod, val amountPaise: Long, val reference: String? = null)

data class SaleTotals(
    val subtotalPaise: Long,    // sum of taxable values
    val discountPaise: Long,
    val taxPaise: Long,
    val roundOffPaise: Long,
    val totalPaise: Long,
)

data class SalePreview(
    val totals: SaleTotals,
    val lines: List<SaleLineEntity>,
    /** True when some quantity has no batch behind it (store allows negative stock). */
    val soldAgainstNegativeStock: Boolean,
)

class InsufficientStockException(val productName: String, val shortMilli: Long) :
    Exception("Not enough stock for $productName")

class SaleValidationException(message: String) : Exception(message)

class CreditLimitExceededException(val limitPaise: Long, val balancePaise: Long) :
    Exception("Credit limit exceeded")

/**
 * Completes a sale in ONE database transaction: invoice number, FEFO batch allocation, tax split,
 * sale lines, stock ledger rows, batch balances, payments and khata. Any failure rolls everything back.
 * [preview] runs the same allocation and tax maths without writing, so the screen shows the exact total.
 */
class CompleteSaleUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val storeDao: StoreDao,
    private val productDao: ProductDao,
    private val batchDao: BatchDao,
    private val stockDao: StockMovementDao,
    private val saleDao: SaleDao,
    private val customerDao: CustomerDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
    private val series: InvoiceSeries = NoSeries,
) {
    private class Computed(val lines: List<SaleLineEntity>, val movements: List<StockMovementEntity>, val overrides: List<String>)

    private fun totalsOf(lines: List<SaleLineEntity>): SaleTotals {
        val (total, roundOff) = GstCalculator.roundToRupee(lines.sumOf { it.lineTotalPaise })
        return SaleTotals(
            subtotalPaise = lines.sumOf { it.taxableValuePaise },
            discountPaise = lines.sumOf { it.discountPaise },
            taxPaise = lines.sumOf { it.cgstPaise + it.sgstPaise + it.igstPaise },
            roundOffPaise = roundOff,
            totalPaise = total,
        )
    }

    suspend fun preview(
        items: List<CartItem>,
        interState: Boolean = false,
        today: LocalDate = LocalDate.now(),
        billDiscountPaise: Long = 0,
    ): SalePreview {
        val store = storeDao.get() ?: throw SaleValidationException("Store is not set up")
        val c = computeLines("preview", items, store, interState, today, now = 0L, billDiscountPaise = billDiscountPaise)
        return SalePreview(totalsOf(c.lines), c.lines, c.lines.any { it.batchId == null })
    }

    /** @return the new sale id */
    suspend operator fun invoke(
        items: List<CartItem>,
        payments: List<PaymentInput>,
        customerId: String? = null,
        interState: Boolean = false,
        today: LocalDate = LocalDate.now(),
        now: Long = System.currentTimeMillis(),
        billDiscountPaise: Long = 0,
    ): String {
        access.require(Permission.SELL)
        if (items.isEmpty()) throw SaleValidationException("Cart is empty")

        return db.withTransaction {
            val store = storeDao.get() ?: throw SaleValidationException("Store is not set up")
            val saleId = java.util.UUID.randomUUID().toString()
            val c = computeLines(saleId, items, store, interState, today, now, billDiscountPaise)

            // Cached balances move together with the ledger rows.
            for (m in c.movements) m.batchId?.let { batchDao.adjustQty(it, m.qtyMilli, now) }

            val totals = totalsOf(c.lines)
            val paid = payments.sumOf { it.amountPaise }
            if (payments.any { it.amountPaise <= 0 }) throw SaleValidationException("Invalid payment amount")
            if (paid > totals.totalPaise) throw SaleValidationException("Payment exceeds bill total")
            val credit = totals.totalPaise - paid
            // Sensitive things need the matching permission: discounts, selling at another price, and giving credit.
            if (totals.discountPaise > 0) access.require(Permission.APPLY_DISCOUNT)
            if (c.overrides.isNotEmpty()) access.require(Permission.OVERRIDE_PRICE)
            if (credit > 0) access.require(Permission.SELL_ON_CREDIT)
            if (credit > 0 && customerId == null)
                throw SaleValidationException("Choose a customer to sell on credit")
            if (credit > 0 && customerId != null) {
                val limit = customerDao.getById(customerId)?.creditLimitPaise
                val balance = customerDao.balance(customerId)
                if (limit != null && balance + credit > limit) throw CreditLimitExceededException(limit, balance)
            }

            val invoiceNo = "${store.invoicePrefix}-${series.current().let { if (it.isEmpty()) "" else "$it-" }}${store.nextInvoiceNo.toString().padStart(5, '0')}"
            storeDao.incrementInvoiceNo(store.id)

            saleDao.insertSale(
                SaleEntity(
                    id = saleId, invoiceNo = invoiceNo, customerId = customerId, interState = interState,
                    subtotalPaise = totals.subtotalPaise, discountPaise = totals.discountPaise,
                    taxPaise = totals.taxPaise, roundOffPaise = totals.roundOffPaise,
                    totalPaise = totals.totalPaise, paidPaise = paid, createdAt = now,
                )
            )
            saleDao.insertLines(c.lines)
            stockDao.insertAll(c.movements)
            saleDao.insertPayments(payments.map {
                PaymentEntity(saleId = saleId, customerId = customerId, method = it.method,
                    amountPaise = it.amountPaise, reference = it.reference, createdAt = now)
            })
            if (credit > 0 && customerId != null) {
                customerDao.insertLedger(
                    CustomerLedgerEntity(customerId = customerId, type = LedgerType.SALE_ON_CREDIT,
                        amountPaise = credit, refType = "SALE", refId = saleId, createdAt = now)
                )
            }
            if (totals.discountPaise > 0)
                audit.log(AuditAction.SALE_DISCOUNT, "Discount ${Money(totals.discountPaise).format()} on $invoiceNo (bill ${Money(totals.totalPaise).format()})", "SALE", saleId)
            c.overrides.forEach { audit.log(AuditAction.PRICE_OVERRIDE, "$invoiceNo: $it", "SALE", saleId) }
            saleId
        }
    }

    /** FEFO allocation + tax split. Read-only: callers apply the returned movements. */
    private suspend fun computeLines(
        saleId: String,
        items: List<CartItem>,
        store: StoreEntity,
        interState: Boolean,
        today: LocalDate,
        now: Long,
        billDiscountPaise: Long = 0,
    ): Computed {
        val lines = mutableListOf<SaleLineEntity>()
        val movements = mutableListOf<StockMovementEntity>()
        val overrides = mutableListOf<String>()

        // A bill-level discount is spread over the lines in proportion to their value (after line discounts),
        // then treated as extra line discount, so tax is always computed on what the customer really pays.
        val nets = items.map { maxOf(Money(it.unitPricePaise).timesQtyMilli(it.qtyMilli).paise - it.discountPaise, 0L) }
        if (billDiscountPaise < 0 || billDiscountPaise > nets.sum()) throw SaleValidationException("Invalid bill discount")
        val shares = DiscountAllocator.allocate(billDiscountPaise, nets)

        for ((idx, original) in items.withIndex()) {
            val item = original.copy(discountPaise = original.discountPaise + shares[idx])
            if (item.qtyMilli <= 0) throw SaleValidationException("Quantity must be positive")
            val product = productDao.getById(item.productId)
                ?: throw SaleValidationException("Product not found")

            if (item.unitPricePaise != product.sellPricePaise)
                overrides += "${product.name} sold at ${Money(item.unitPricePaise).format()} instead of ${Money(product.sellPricePaise).format()}"
            val gross = Money(item.unitPricePaise).timesQtyMilli(item.qtyMilli).paise
            if (item.discountPaise < 0 || item.discountPaise > gross)
                throw SaleValidationException("Invalid discount for ${product.name}")

            data class Alloc(val batch: BatchEntity?, val qtyMilli: Long)
            val allocs = mutableListOf<Alloc>()
            var remaining = item.qtyMilli
            for (b in batchDao.fefoBatches(product.id, today)) {
                if (remaining == 0L) break
                val take = minOf(remaining, b.qtyOnHandMilli)
                allocs += Alloc(b, take); remaining -= take
            }
            if (remaining > 0) {
                if (!store.allowNegativeStock) throw InsufficientStockException(product.name, remaining)
                allocs += Alloc(null, remaining)
            }

            // One sale line per allocation; amount and discount split proportionally, remainder on the last.
            var grossLeft = gross
            var discLeft = item.discountPaise
            allocs.forEachIndexed { i, a ->
                val last = i == allocs.lastIndex
                val allocGross = if (last) grossLeft else Rounding.divHalfUp(gross * a.qtyMilli, item.qtyMilli)
                val allocDisc = if (last) discLeft else Rounding.divHalfUp(item.discountPaise * a.qtyMilli, item.qtyMilli)
                grossLeft -= allocGross; discLeft -= allocDisc

                val tax = GstCalculator.calculate(
                    amountPaise = allocGross - allocDisc,
                    rateBp = product.gstRateBp,
                    taxInclusive = product.taxInclusive,
                    interState = interState,
                )
                lines += SaleLineEntity(
                    saleId = saleId, productId = product.id, batchId = a.batch?.id,
                    nameSnapshot = product.name, hsnSnapshot = product.hsnCode,
                    qtyMilli = a.qtyMilli, unitPricePaise = item.unitPricePaise,
                    discountPaise = allocDisc, gstRateBp = product.gstRateBp,
                    taxInclusive = product.taxInclusive,
                    taxableValuePaise = tax.taxableValue,
                    cgstPaise = tax.cgst, sgstPaise = tax.sgst, igstPaise = tax.igst,
                    lineTotalPaise = tax.lineTotal,
                    costSnapshotPaise = a.batch?.costPaise ?: product.costPaise,
                )
                movements += StockMovementEntity(
                    productId = product.id, batchId = a.batch?.id, type = MovementType.SALE,
                    qtyMilli = -a.qtyMilli, unitCostPaise = a.batch?.costPaise ?: product.costPaise,
                    refType = "SALE", refId = saleId, createdAt = now,
                )
            }
        }
        return Computed(lines, movements, overrides)
    }
}
