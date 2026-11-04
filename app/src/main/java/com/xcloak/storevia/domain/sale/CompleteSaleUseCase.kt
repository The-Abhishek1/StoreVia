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
import java.math.BigInteger
import java.time.LocalDate
import javax.inject.Inject

data class CartItem(
    val productId: String,
    val qtyMilli: Long,
    val unitPricePaise: Long,
    val discountPaise: Long = 0,
    val preferredBatchId: String? = null,
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

internal fun preferredBatchOrder(eligible: List<BatchEntity>, preferredBatchId: String?): List<BatchEntity> {
    if (preferredBatchId == null) return eligible
    val preferred = eligible.firstOrNull { it.id == preferredBatchId }
        ?: throw SaleValidationException("Selected batch is unavailable or expired")
    return listOf(preferred) + eligible.filterNot { it.id == preferredBatchId }
}

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
    private val recipeDao: RecipeDao = db.recipeDao(),
    private val serialNumberDao: SerialNumberDao = db.serialNumberDao(),
) {
    private data class SerialAssignment(val serialId: String, val saleLineId: String)
    private class Computed(
        val lines: List<SaleLineEntity>,
        val movements: List<StockMovementEntity>,
        val overrides: List<String>,
        val batchOverrides: List<String>,
        val soldAgainstNegativeStock: Boolean,
        val serialAssignments: List<SerialAssignment>,
    )

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
        val branchId = db.branchDao().activeBranchId()
        val c = computeLines("preview", items, store, branchId, interState, today, now = 0L, billDiscountPaise = billDiscountPaise)
        return SalePreview(totalsOf(c.lines), c.lines, c.soldAgainstNegativeStock)
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
            val branchId = db.branchDao().activeBranchId()
            val saleId = java.util.UUID.randomUUID().toString()
            val c = computeLines(saleId, items, store, branchId, interState, today, now, billDiscountPaise)
            if (items.any { it.preferredBatchId != null }) access.require(Permission.OVERRIDE_BATCH)

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
                    id = saleId, invoiceNo = invoiceNo, branchId = branchId, customerId = customerId, interState = interState,
                    subtotalPaise = totals.subtotalPaise, discountPaise = totals.discountPaise,
                    taxPaise = totals.taxPaise, roundOffPaise = totals.roundOffPaise,
                    totalPaise = totals.totalPaise, paidPaise = paid, createdAt = now,
                )
            )
            saleDao.insertLines(c.lines)
            c.serialAssignments.forEach { assignment ->
                if (serialNumberDao.markSold(assignment.serialId, assignment.saleLineId, now) != 1) {
                    throw SaleValidationException("A serial number is no longer available.")
                }
            }
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
            c.batchOverrides.forEach { audit.log(AuditAction.BATCH_OVERRIDE, "$invoiceNo: $it", "SALE", saleId) }
            saleId
        }
    }

    /** FEFO allocation + tax split. Read-only: callers apply the returned movements. */
    private suspend fun computeLines(
        saleId: String,
        items: List<CartItem>,
        store: StoreEntity,
        branchId: String,
        interState: Boolean,
        today: LocalDate,
        now: Long,
        billDiscountPaise: Long = 0,
    ): Computed {
        val lines = mutableListOf<SaleLineEntity>()
        val movements = mutableListOf<StockMovementEntity>()
        val overrides = mutableListOf<String>()
        val batchOverrides = mutableListOf<String>()
        val reservedByBatch = mutableMapOf<String, Long>()
        val reservedSerialIds = mutableSetOf<String>()
        val serialAssignments = mutableListOf<SerialAssignment>()
        var soldAgainstNegativeStock = false

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
            val recipe = recipeDao.getForProduct(product.id)
            if (recipe.isNotEmpty()) {
                if (item.preferredBatchId != null) throw SaleValidationException("Batch override is unavailable for recipe products.")
                allocs += Alloc(null, item.qtyMilli)
            } else {
                if (product.serialTracked && item.qtyMilli % 1_000L != 0L) {
                    throw SaleValidationException("Serial-tracked products must be sold in whole units.")
                }
                var remaining = item.qtyMilli
                val eligibleBatches = batchDao.fefoBatches(product.id, today, branchId)
                val preferredBatch = item.preferredBatchId?.let { id ->
                    eligibleBatches.firstOrNull { it.id == id }
                        ?: throw SaleValidationException("Selected batch is unavailable or expired for ${product.name}")
                }
                if (preferredBatch != null) {
                    batchOverrides += "${product.name}: selected batch ${preferredBatch.batchNo ?: preferredBatch.id}"
                }
                val allocationOrder = preferredBatchOrder(eligibleBatches, item.preferredBatchId)
                for (b in allocationOrder) {
                    if (remaining == 0L) break
                    val available = (b.qtyOnHandMilli - (reservedByBatch[b.id] ?: 0L)).coerceAtLeast(0L)
                    val take = minOf(remaining, available)
                    if (take > 0) {
                        allocs += Alloc(b, take)
                        reservedByBatch[b.id] = (reservedByBatch[b.id] ?: 0L) + take
                        remaining -= take
                    }
                }
                if (remaining > 0) {
                    if (product.serialTracked || !store.allowNegativeStock) throw InsufficientStockException(product.name, remaining)
                    soldAgainstNegativeStock = true
                    allocs += Alloc(null, remaining)
                }
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
                val recipeUnitCost = if (recipe.isEmpty()) {
                    a.batch?.costPaise ?: product.costPaise
                } else {
                    recipe.sumOf { component ->
                        val ingredient = productDao.getById(component.ingredientProductId)
                            ?: throw SaleValidationException("Recipe ingredient is unavailable.")
                        val requiredForUnit = scaleMilli(component.quantityPerUnitMilli, 1_000L)
                        Money(ingredient.costPaise).timesQtyMilli(requiredForUnit).paise
                    }
                }
                val saleLine = SaleLineEntity(
                    saleId = saleId, productId = product.id, batchId = a.batch?.id,
                    nameSnapshot = product.name, hsnSnapshot = product.hsnCode,
                    qtyMilli = a.qtyMilli, unitPricePaise = item.unitPricePaise,
                    discountPaise = allocDisc, gstRateBp = product.gstRateBp,
                    taxInclusive = product.taxInclusive,
                    taxableValuePaise = tax.taxableValue,
                    cgstPaise = tax.cgst, sgstPaise = tax.sgst, igstPaise = tax.igst,
                    lineTotalPaise = tax.lineTotal,
                    costSnapshotPaise = recipeUnitCost,
                )
                lines += saleLine
                if (recipe.isEmpty()) {
                    if (product.serialTracked) {
                        val batch = a.batch ?: throw InsufficientStockException(product.name, a.qtyMilli)
                        val serials = serialNumberDao.availableForBatch(product.id, batch.id)
                            .filterNot { it.id in reservedSerialIds }
                        val requiredSerials = a.qtyMilli / 1_000L
                        if (serials.size.toLong() < requiredSerials) {
                            throw InsufficientStockException(product.name, (requiredSerials - serials.size) * 1_000L)
                        }
                        serials.take(requiredSerials.toInt()).forEach { serial ->
                            reservedSerialIds += serial.id
                            serialAssignments += SerialAssignment(serial.id, saleLine.id)
                        }
                    }
                    movements += StockMovementEntity(
                        productId = product.id, branchId = branchId, batchId = a.batch?.id, type = MovementType.SALE,
                        qtyMilli = -a.qtyMilli, unitCostPaise = recipeUnitCost,
                        refType = "SALE", refId = saleId, createdAt = now,
                    )
                } else {
                    recipe.forEach { component ->
                        val ingredient = productDao.getById(component.ingredientProductId)
                            ?: throw SaleValidationException("Recipe ingredient is unavailable.")
                        val required = scaleMilli(component.quantityPerUnitMilli, a.qtyMilli)
                        if (required == 0L) return@forEach
                        var ingredientRemaining = required
                        val ingredientBatches = batchDao.fefoBatches(ingredient.id, today, branchId)
                        for (batch in ingredientBatches) {
                            if (ingredientRemaining == 0L) break
                            val available = (batch.qtyOnHandMilli - (reservedByBatch[batch.id] ?: 0L)).coerceAtLeast(0L)
                            val take = minOf(ingredientRemaining, available)
                            if (take > 0) {
                                reservedByBatch[batch.id] = (reservedByBatch[batch.id] ?: 0L) + take
                                movements += StockMovementEntity(
                                    productId = ingredient.id, branchId = branchId, batchId = batch.id, type = MovementType.SALE,
                                    qtyMilli = -take, unitCostPaise = batch.costPaise,
                                    refType = "RECIPE_SALE", refId = saleId, reason = saleLine.id, createdAt = now,
                                )
                                ingredientRemaining -= take
                            }
                        }
                        if (ingredientRemaining > 0) {
                            if (!store.allowNegativeStock) throw InsufficientStockException(ingredient.name, ingredientRemaining)
                            soldAgainstNegativeStock = true
                            movements += StockMovementEntity(
                                productId = ingredient.id, branchId = branchId, batchId = null, type = MovementType.SALE,
                                qtyMilli = -ingredientRemaining, unitCostPaise = ingredient.costPaise,
                                refType = "RECIPE_SALE", refId = saleId, reason = saleLine.id, createdAt = now,
                            )
                        }
                    }
                }
            }
        }
        return Computed(lines, movements, overrides, batchOverrides, soldAgainstNegativeStock, serialAssignments)
    }

    private fun scaleMilli(quantityMilli: Long, productQuantityMilli: Long): Long {
        val scaled = BigInteger.valueOf(quantityMilli)
            .multiply(BigInteger.valueOf(productQuantityMilli))
            .add(BigInteger.valueOf(500))
            .divide(BigInteger.valueOf(1_000))
        return scaled.longValueExact()
    }
}
