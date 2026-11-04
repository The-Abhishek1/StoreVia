package com.xcloak.storevia.domain.imports

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.*
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.core.money.Money
import java.time.LocalDate
import java.time.ZoneId
import java.math.BigInteger
import javax.inject.Inject

enum class SalesImportStockMode { HISTORICAL_ONLY, LIVE_DEDUCT }

data class SalesImportValidation(val errors: List<SalesImportRowError>) {
    val valid: Boolean get() = errors.isEmpty()
}

data class SalesImportSummaryResult(val imported: Int, val skipped: Int = 0)

class SalesImportUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val productDao: ProductDao,
    private val customerDao: CustomerDao,
    private val unitDao: UnitDao,
    private val batchDao: BatchDao,
    private val recipeDao: RecipeDao,
    private val stockDao: StockMovementDao,
    private val saleDao: SaleDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
    private val serialNumberDao: SerialNumberDao = db.serialNumberDao(),
) {
    suspend fun validate(document: SalesImportDocument): SalesImportValidation {
        val errors = document.errors.toMutableList()
        val products = mutableMapOf<SalesImportLine, ProductEntity?>()
        document.lines.forEach { line ->
            val product = if (line.sku != null) productDao.findBySku(line.sku)
                else line.productName?.let { productDao.findByName(it) }
            products[line] = product
            if (product == null) errors += SalesImportRowError(line.line, "Product '${line.sku ?: line.productName}' was not found.")
            else {
                val unit = unitDao.getById(product.unitId)
                val quantity = com.xcloak.storevia.core.util.Parsing.qtyToMilli(line.quantity, unit?.allowDecimal ?: false)
                if (quantity == null || quantity <= 0) {
                    errors += SalesImportRowError(line.line, "Quantity is invalid for ${product.name} (${unit?.symbol ?: "unit"}).")
                }
            }
        }
        document.summaries.forEach { summary ->
            if (saleDao.hasInvoiceNo(summary.billId)) {
                errors += SalesImportRowError(summary.line, "Bill ID '${summary.billId}' already exists.")
            }
            if (summary.customerName != null && customerDao.findByName(summary.customerName) == null) {
                errors += SalesImportRowError(summary.line, "Customer '${summary.customerName}' was not found.")
            }
            if (summary.isCredit && summary.customerName == null) {
                errors += SalesImportRowError(summary.line, "Credit sales require a customer.")
            }
            if (summary.totalPaise < 0 || summary.discountPaise < 0 || summary.taxPaise < 0 ||
                summary.taxPaise > summary.totalPaise
            ) {
                errors += SalesImportRowError(summary.line, "Total, discount, and tax values are inconsistent.")
            }
        }
        return SalesImportValidation(errors.distinct())
    }

    suspend fun import(
        document: SalesImportDocument,
        stockMode: SalesImportStockMode,
        now: Long = System.currentTimeMillis(),
    ): SalesImportSummaryResult {
        access.require(Permission.IMPORT_DATA)
        return db.withTransaction {
            val validation = validate(document)
            if (!validation.valid) throw SalesImportValidationException(validation.errors)

            val linesByBill = document.lines.groupBy { it.billId }
            val zone = ZoneId.systemDefault()
            for (summary in document.summaries) {
                val customer = summary.customerName?.let { customerDao.findByName(it) }
                val createdAt = summary.date.atStartOfDay(zone).toInstant().toEpochMilli()
                val totalNet = summary.totalPaise - summary.taxPaise
                val rawLines = checkNotNull(linesByBill[summary.billId])
                val prepared = rawLines.map { row ->
                    val product = (
                        if (row.sku != null) productDao.findBySku(row.sku)
                        else row.productName?.let { productDao.findByName(it) }
                    ) ?: throw IllegalStateException("Product disappeared while importing bill ${summary.billId}.")
                    val unit = unitDao.getById(product.unitId)
                        ?: throw IllegalStateException("Unit for ${product.name} no longer exists.")
                    val qty = checkNotNull(com.xcloak.storevia.core.util.Parsing.qtyToMilli(row.quantity, unit.allowDecimal))
                    PreparedLine(row, product, qty)
                }

                val pieces = mutableListOf<PreparedPiece>()
                prepared.forEach { line ->
                    if (stockMode == SalesImportStockMode.HISTORICAL_ONLY) {
                        pieces += PreparedPiece(line, null, line.qtyMilli)
                    } else if (recipeDao.hasRecipe(line.product.id)) {
                        pieces += PreparedPiece(line, null, line.qtyMilli)
                    } else {
                        if (line.product.serialTracked && line.qtyMilli % 1_000L != 0L) {
                            throw SalesImportValidationException(
                                listOf(SalesImportRowError(line.row.line, "Serial-tracked products require whole-unit quantities.")),
                            )
                        }
                        var remaining = line.qtyMilli
                        val batches = batchDao.fefoBatches(line.product.id, summary.date)
                        for (batch in batches) {
                            if (remaining == 0L) break
                            val take = minOf(remaining, batch.qtyOnHandMilli)
                            if (take > 0) {
                                batchDao.adjustQty(batch.id, -take, now)
                                pieces += PreparedPiece(line, batch, take)
                                remaining -= take
                            }
                        }
                        if (remaining > 0) {
                            throw SalesImportValidationException(
                                listOf(SalesImportRowError(line.row.line, "Insufficient stock for '${line.product.name}' on ${summary.date}.")),
                            )
                        }
                    }
                }

                val weights = pieces.map { piece ->
                    Money(piece.line.row.unitPricePaise).timesQtyMilli(piece.qtyMilli).paise.coerceAtLeast(1)
                }
                val lineTotals = allocate(totalNet + summary.taxPaise, weights)
                val taxableShares = allocate(totalNet, weights)
                val taxShares = allocate(summary.taxPaise, weights)
                val discountShares = allocate(summary.discountPaise, weights)
                val saleId = java.util.UUID.randomUUID().toString()
                val saleLines = pieces.mapIndexed { index, piece ->
                    val tax = taxShares[index]
                    val halfTax = tax / 2
                    val recipeComponents = recipeDao.getForProduct(piece.line.product.id)
                    val costSnapshot = if (recipeComponents.isEmpty()) {
                        piece.batch?.costPaise ?: piece.line.product.costPaise
                    } else {
                        recipeComponents.sumOf { component ->
                            val ingredient = productDao.getById(component.ingredientProductId)
                                ?: throw SalesImportValidationException(
                                    listOf(SalesImportRowError(piece.line.row.line, "A recipe ingredient is unavailable.")),
                                )
                            val required = scaleMilli(component.quantityPerUnitMilli, 1_000L)
                            Money(ingredient.costPaise).timesQtyMilli(required).paise
                        }
                    }
                    SaleLineEntity(
                        saleId = saleId,
                        productId = piece.line.product.id,
                        batchId = piece.batch?.id,
                        nameSnapshot = piece.line.product.name,
                        hsnSnapshot = piece.line.product.hsnCode,
                        qtyMilli = piece.qtyMilli,
                        unitPricePaise = piece.line.row.unitPricePaise,
                        discountPaise = discountShares[index],
                        gstRateBp = piece.line.product.gstRateBp,
                        taxInclusive = piece.line.product.taxInclusive,
                        taxableValuePaise = taxableShares[index],
                        cgstPaise = halfTax,
                        sgstPaise = tax - halfTax,
                        igstPaise = 0,
                        lineTotalPaise = lineTotals[index],
                        costSnapshotPaise = costSnapshot,
                    )
                }
                val paid = if (summary.isCredit) 0L else summary.totalPaise
                saleDao.insertSale(
                    SaleEntity(
                        id = saleId,
                        invoiceNo = summary.billId,
                        customerId = customer?.id,
                        interState = false,
                        subtotalPaise = totalNet,
                        discountPaise = summary.discountPaise,
                        taxPaise = summary.taxPaise,
                        roundOffPaise = 0,
                        totalPaise = summary.totalPaise,
                        paidPaise = paid,
                        createdAt = createdAt,
                    ),
                )
                saleDao.insertLines(saleLines)
                if (summary.paymentMethod != null) {
                    saleDao.insertPayments(
                        listOf(
                            PaymentEntity(
                                saleId = saleId,
                                customerId = customer?.id,
                                method = summary.paymentMethod,
                                amountPaise = summary.totalPaise,
                                createdAt = createdAt,
                            ),
                        ),
                    )
                }
                if (summary.isCredit && customer != null) {
                    customerDao.insertLedger(
                        CustomerLedgerEntity(
                            customerId = customer.id,
                            type = LedgerType.SALE_ON_CREDIT,
                            amountPaise = summary.totalPaise,
                            refType = "SALE",
                            refId = saleId,
                            createdAt = createdAt,
                        ),
                    )
                }
                if (stockMode == SalesImportStockMode.LIVE_DEDUCT) {
                    val movements = mutableListOf<StockMovementEntity>()
                    pieces.forEachIndexed { index, piece ->
                        val saleLine = saleLines[index]
                        val components = recipeDao.getForProduct(piece.line.product.id)
                        if (components.isNotEmpty()) {
                            components.forEach { component ->
                                val ingredient = productDao.getById(component.ingredientProductId)
                                    ?: throw SalesImportValidationException(
                                        listOf(SalesImportRowError(piece.line.row.line, "A recipe ingredient is unavailable.")),
                                    )
                                var remaining = scaleMilli(component.quantityPerUnitMilli, piece.qtyMilli)
                                val batches = batchDao.fefoBatches(ingredient.id, summary.date)
                                for (batch in batches) {
                                    if (remaining == 0L) break
                                    val take = minOf(remaining, batch.qtyOnHandMilli)
                                    if (take > 0) {
                                        batchDao.adjustQty(batch.id, -take, now)
                                        movements += StockMovementEntity(
                                            productId = ingredient.id,
                                            batchId = batch.id,
                                            type = MovementType.SALE,
                                            qtyMilli = -take,
                                            unitCostPaise = batch.costPaise,
                                            refType = "RECIPE_SALE",
                                            refId = saleId,
                                            reason = saleLine.id,
                                            createdAt = createdAt,
                                        )
                                        remaining -= take
                                    }
                                }
                                if (remaining > 0) {
                                    throw SalesImportValidationException(
                                        listOf(SalesImportRowError(piece.line.row.line, "Insufficient ingredient stock for '${ingredient.name}' on ${summary.date}.")),
                                    )
                                }
                            }
                        } else {
                            if (piece.line.product.serialTracked) {
                                val batch = piece.batch ?: throw SalesImportValidationException(
                                    listOf(SalesImportRowError(piece.line.row.line, "Serial-tracked sale has no stock batch.")),
                                )
                                val requiredSerials = piece.qtyMilli / 1_000L
                                val serials = serialNumberDao.availableForBatch(piece.line.product.id, batch.id)
                                if (serials.size.toLong() < requiredSerials) {
                                    throw SalesImportValidationException(
                                        listOf(SalesImportRowError(piece.line.row.line, "Serial numbers are missing for '${piece.line.product.name}'.")),
                                    )
                                }
                                serials.take(requiredSerials.toInt()).forEach { serial ->
                                    if (serialNumberDao.markSold(serial.id, saleLine.id, now) != 1) {
                                        throw SalesImportValidationException(
                                            listOf(SalesImportRowError(piece.line.row.line, "A serial number is no longer available.")),
                                        )
                                    }
                                }
                            }
                            movements += StockMovementEntity(
                                productId = piece.line.product.id,
                                batchId = piece.batch?.id,
                                type = MovementType.SALE,
                                qtyMilli = -piece.qtyMilli,
                                unitCostPaise = piece.batch?.costPaise ?: piece.line.product.costPaise,
                                refType = "SALE",
                                refId = saleId,
                                reason = "Imported live sale",
                                createdAt = createdAt,
                            )
                        }
                    }
                    if (movements.isNotEmpty()) stockDao.insertAll(movements)
                }
            }
            audit.log(
                AuditAction.IMPORT,
                "Sales import: ${document.summaries.size} bills, stock mode ${stockMode.name.lowercase()}",
            )
            SalesImportSummaryResult(document.summaries.size)
        }
    }

    private data class PreparedLine(val row: SalesImportLine, val product: ProductEntity, val qtyMilli: Long)
    private data class PreparedPiece(val line: PreparedLine, val batch: BatchEntity?, val qtyMilli: Long)

    private fun allocate(total: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        require(total >= 0 && weights.all { it >= 0 })
        val totalWeight = weights.fold(BigInteger.ZERO) { sum, weight -> sum + BigInteger.valueOf(weight) }
        require(totalWeight.signum() > 0)
        val totalValue = BigInteger.valueOf(total)
        val shares = weights.map { weight ->
            totalValue.multiply(BigInteger.valueOf(weight)).divideAndRemainder(totalWeight)
        }
        val remainder = (totalValue - shares.fold(BigInteger.ZERO) { sum, share -> sum + share[0] }).intValueExact()
        return shares.mapIndexed { index, share ->
            share[0].longValueExact() + if (index < remainder) 1L else 0L
        }
    }

    private fun scaleMilli(quantityMilli: Long, multiplierMilli: Long): Long =
        BigInteger.valueOf(quantityMilli)
            .multiply(BigInteger.valueOf(multiplierMilli))
            .add(BigInteger.valueOf(500L))
            .divide(BigInteger.valueOf(1_000L))
            .longValueExact()
}

class SalesImportValidationException(val errors: List<SalesImportRowError>) :
    Exception(errors.firstOrNull()?.message ?: "Sales import is invalid.")
