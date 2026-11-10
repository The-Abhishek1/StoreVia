package com.xcloak.storevia.feature.reports

import com.xcloak.storevia.core.database.dao.BatchAgg
import com.xcloak.storevia.core.database.dao.LowStockRow
import com.xcloak.storevia.core.database.dao.PaymentTotalRow
import com.xcloak.storevia.core.database.dao.TopProductRow
import com.xcloak.storevia.core.database.dao.StockValue
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.feature.home.cloudInt
import com.xcloak.storevia.feature.home.cloudLong
import com.xcloak.storevia.feature.home.cloudString
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

internal data class CloudReportResult(
    val snapshot: ReportSnapshot,
    val options: ReportFilterOptions,
)

internal fun calculateCloudReports(
    rows: Map<String, List<JsonObject>>,
    query: ReportQuery,
    today: LocalDate,
    expiryWarnDays: Int,
): CloudReportResult {
    val allProducts = rows["products"].orEmpty()
    val products = allProducts.filter { it.cloudLong("deleted_at") == null }
    val categories = rows["categories"].orEmpty().filter { it.cloudLong("deleted_at") == null }
    val customers = rows["customers"].orEmpty().filter { it.cloudLong("deleted_at") == null }
    val suppliers = rows["suppliers"].orEmpty().filter { it.cloudLong("deleted_at") == null }
    val branches = rows["branches"].orEmpty().filter { it.cloudLong("deleted_at") == null }
    val units = rows["units"].orEmpty().associateBy { it.cloudString("id") }
    val productById = allProducts.associateBy { it.cloudString("id") }
    val purchaseById = rows["purchases"].orEmpty().associateBy { it.cloudString("id") }
    val supplierProductIds = rows["purchase_lines"].orEmpty()
        .mapNotNull { line ->
            val supplier = purchaseById[line.cloudString("purchase_id")]?.cloudString("supplier_id")
            val product = line.cloudString("product_id")
            if (supplier == null || product == null) null else supplier to product
        }.groupBy({ it.first }, { it.second })
    val paymentMethodsBySale = rows["payments"].orEmpty().mapNotNull { payment ->
        val saleId = payment.cloudString("sale_id") ?: return@mapNotNull null
        saleId to payment.cloudString("method")
    }.groupBy({ it.first }, { it.second })
    val allSaleLines = rows["sale_lines"].orEmpty().groupBy { it.cloudString("sale_id") }
    val allReturnLines = rows["sale_return_lines"].orEmpty().groupBy { it.cloudString("return_id") }
    val salesById = rows["sales"].orEmpty().associateBy { it.cloudString("id") }
    val lineFiltersActive = query.filters.productId != null ||
        query.filters.categoryId != null || query.filters.taxRateBp != null || query.filters.supplierId != null

    fun lineMatches(line: JsonObject): Boolean {
        val productId = line.cloudString("product_id")
        val product = productById[productId]
        val supplierProductSet = query.filters.supplierId?.let { supplierProductIds[it].orEmpty().toSet() }
        return (query.filters.productId == null || productId == query.filters.productId) &&
            (query.filters.categoryId == null || product?.cloudString("category_id") == query.filters.categoryId) &&
            (query.filters.taxRateBp == null || line.cloudInt("gst_rate_bp") == query.filters.taxRateBp) &&
            (supplierProductSet == null || productId in supplierProductSet)
    }

    fun saleMatches(sale: JsonObject): Boolean {
        val createdAt = sale.cloudLong("created_at") ?: return false
        if (sale.cloudString("status") == "CANCELLED" || createdAt < query.from || createdAt >= query.to) return false
        if (query.filters.branchId != null && sale.cloudString("branch_id") != query.filters.branchId) return false
        if (query.filters.customerId != null && sale.cloudString("customer_id") != query.filters.customerId) return false
        if (query.method != null && query.method.name !in paymentMethodsBySale[sale.cloudString("id")].orEmpty()) return false
        if (lineFiltersActive && allSaleLines[sale.cloudString("id")].orEmpty().none(::lineMatches)) return false
        return true
    }

    val selectedSales = rows["sales"].orEmpty().filter(::saleMatches)
    val selectedSaleIds = selectedSales.mapNotNullTo(mutableSetOf()) { it.cloudString("id") }
    val selectedLines = allSaleLines.filterKeys { it in selectedSaleIds }.values.flatten().filter(::lineMatches)
    val grossPaise = selectedSales.sumOf { sale ->
        if (!lineFiltersActive) sale.cloudLong("total_paise") ?: 0L
        else allSaleLines[sale.cloudString("id")].orEmpty().filter(::lineMatches).sumOf { it.cloudLong("line_total_paise") ?: 0L }
    }
    val discountPaise = selectedSales.sumOf { sale ->
        if (!lineFiltersActive) sale.cloudLong("discount_paise") ?: 0L
        else allSaleLines[sale.cloudString("id")].orEmpty().filter(::lineMatches).sumOf { it.cloudLong("discount_paise") ?: 0L }
    }
    val taxablePaise = selectedLines.sumOf { it.cloudLong("taxable_value_paise") ?: 0L }
    val costPaise = selectedLines.sumOf { (it.cloudLong("qty_milli") ?: 0L) * (it.cloudLong("cost_snapshot_paise") ?: 0L) / 1000L }
    val taxPaise = selectedLines.sumOf {
        (it.cloudLong("cgst_paise") ?: 0L) + (it.cloudLong("sgst_paise") ?: 0L) + (it.cloudLong("igst_paise") ?: 0L)
    }

    val saleLineById = rows["sale_lines"].orEmpty().associateBy { it.cloudString("id") }
    val returns = rows["sale_returns"].orEmpty().filter { row ->
        val createdAt = row.cloudLong("created_at") ?: return@filter false
        val sale = salesById[row.cloudString("sale_id")] ?: return@filter false
        val returnLines = allReturnLines[row.cloudString("id")].orEmpty()
        sale.cloudString("status") != "CANCELLED" && createdAt >= query.from && createdAt < query.to &&
            (query.filters.branchId == null || sale.cloudString("branch_id") == query.filters.branchId) &&
            (query.filters.customerId == null || sale.cloudString("customer_id") == query.filters.customerId) &&
            (query.method == null || query.method.name in paymentMethodsBySale[sale.cloudString("id")].orEmpty()) &&
            (!lineFiltersActive || returnLines.any { returnLine ->
                val saleLine = saleLineById[returnLine.cloudString("sale_line_id")]
                saleLine != null && lineMatches(saleLine)
            })
    }
    val selectedReturnIds = returns.mapNotNullTo(mutableSetOf()) { it.cloudString("id") }
    val selectedReturnLines = allReturnLines.filterKeys { it in selectedReturnIds }.values.flatten().filter { returnLine ->
        if (!lineFiltersActive) true
        else {
            val saleLine = saleLineById[returnLine.cloudString("sale_line_id")]
            saleLine != null && lineMatches(saleLine)
        }
    }
    val returnsPaise = returns.sumOf { returnRow ->
        if (!lineFiltersActive) returnRow.cloudLong("total_paise") ?: 0L
        else allReturnLines[returnRow.cloudString("id")].orEmpty().filter { returnLine ->
            saleLineById[returnLine.cloudString("sale_line_id")]?.let(::lineMatches) == true
        }.sumOf { it.cloudLong("amount_paise") ?: 0L }
    }
    val returnedTaxableAndCost = selectedReturnLines.fold(0L to 0L) { totals, returnLine ->
        val saleLine = saleLineById[returnLine.cloudString("sale_line_id")]
        if (saleLine == null || (saleLine.cloudLong("line_total_paise") ?: 0L) <= 0L) totals
        else {
            val lineTotal = saleLine.cloudLong("line_total_paise") ?: 0L
            val taxable = if (lineTotal <= 0L) 0L else
                (returnLine.cloudLong("amount_paise") ?: 0L) * (saleLine.cloudLong("taxable_value_paise") ?: 0L) / lineTotal
            val cost = (returnLine.cloudLong("qty_milli") ?: 0L) *
                (returnLine.cloudLong("cost_snapshot_paise") ?: 0L) / 1000L
            (totals.first + taxable) to (totals.second + cost)
        }
    }
    val selectedPayments = rows["payments"].orEmpty().filter { payment ->
        payment.cloudString("sale_id") in selectedSaleIds &&
            (query.method == null || payment.cloudString("method") == query.method.name)
    }
    val payments = selectedPayments.groupBy { it.cloudString("method") }
        .mapNotNull { (name, records) ->
            val method = runCatching { PaymentMethod.valueOf(name ?: "") }.getOrNull() ?: return@mapNotNull null
            PaymentTotalRow(method, records.sumOf { it.cloudLong("amount_paise") ?: 0L })
        }.sortedByDescending { it.totalPaise }

    val topProducts = selectedLines.groupBy { it.cloudString("product_id") }
        .mapNotNull { (id, lines) ->
            val productId = id ?: return@mapNotNull null
            val name = lines.mapNotNull { it.cloudString("name_snapshot") }.maxOrNull().orEmpty()
            val unitSymbol = units[productById[productId]?.cloudString("unit_id")]?.cloudString("symbol").orEmpty()
            TopProductRow(
                productId = productId,
                name = name,
                unitSymbol = unitSymbol,
                qtyMilli = lines.sumOf { it.cloudLong("qty_milli") ?: 0L },
                revenuePaise = lines.sumOf { it.cloudLong("line_total_paise") ?: 0L },
            )
        }.sortedByDescending { it.revenuePaise }.take(5)

    val purchaseSpend = rows["purchases"].orEmpty().filter { purchase ->
        val date = purchase.cloudLong("purchase_date") ?: return@filter false
        date >= query.from && date < query.to &&
            (query.filters.supplierId == null || purchase.cloudString("supplier_id") == query.filters.supplierId) &&
            (query.filters.branchId == null || purchase.cloudString("branch_id") == query.filters.branchId)
    }.sumOf { it.cloudLong("total_paise") ?: 0L }

    val branchId = query.filters.branchId
    val batches = rows["batches"].orEmpty().filter {
        it.cloudLong("deleted_at") == null && (branchId == null || it.cloudString("branch_id") == branchId)
    }
    val liveBatches = batches.filter { (it.cloudLong("qty_on_hand_milli") ?: 0L) > 0L }
    val stockValue = liveBatches.fold(StockValue(0L, 0L)) { total, batch ->
        val product = productById[batch.cloudString("product_id")]
        if (product == null || product.cloudLong("deleted_at") != null) total
        else total.copy(
            costPaise = total.costPaise + (batch.cloudLong("qty_on_hand_milli") ?: 0L) *
                (batch.cloudLong("cost_paise") ?: 0L) / 1000L,
            retailPaise = total.retailPaise + (batch.cloudLong("qty_on_hand_milli") ?: 0L) *
                (product.cloudLong("sell_price_paise") ?: 0L) / 1000L,
        )
    }
    fun expiryBetween(from: LocalDate, to: LocalDate): BatchAgg {
        val matching = liveBatches.filter { batch ->
            val expiry = batch.cloudString("expiry_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            expiry != null && !expiry.isBefore(from) && !expiry.isAfter(to)
        }
        return BatchAgg(matching.size, matching.sumOf {
            (it.cloudLong("qty_on_hand_milli") ?: 0L) * (it.cloudLong("cost_paise") ?: 0L) / 1000L
        })
    }
    val activeProducts = products.filter { it.cloudLong("is_active") != 0L }
    val parentIds = activeProducts.mapNotNull { it.cloudString("parent_product_id") }.toSet()
    val stockByProduct = batches.groupBy { it.cloudString("product_id") }
        .mapValues { (_, rowsForProduct) -> rowsForProduct.sumOf { it.cloudLong("qty_on_hand_milli") ?: 0L } }
    val lowStock = activeProducts.asSequence()
        .filter { it.cloudString("id") !in parentIds && (it.cloudLong("min_stock_milli") ?: 0L) > 0L }
        .mapNotNull { product ->
            val onHand = stockByProduct[product.cloudString("id")] ?: 0L
            val minimum = product.cloudLong("min_stock_milli") ?: 0L
            if (onHand >= minimum) null else LowStockRow(
                productId = product.cloudString("id").orEmpty(),
                name = product.cloudString("name").orEmpty(),
                onHandMilli = onHand,
                minStockMilli = minimum,
            )
        }.toList()

    fun positiveLedgerTotal(ledgerTable: String, foreignKey: String, entities: List<JsonObject>): Long {
        val activeIds = entities.mapNotNullTo(mutableSetOf()) { it.cloudString("id") }
        return rows[ledgerTable].orEmpty().filter { it.cloudString(foreignKey) in activeIds }
            .groupBy { it.cloudString(foreignKey) }
            .values.sumOf { records -> records.sumOf { it.cloudLong("amount_paise") ?: 0L }.coerceAtLeast(0L) }
    }

    val stock = StockReport(
        costPaise = stockValue.costPaise,
        retailPaise = stockValue.retailPaise,
        expiring = expiryBetween(today, today.plusDays(expiryWarnDays.toLong())),
        expired = expiryBetween(LocalDate.of(1970, 1, 1), today.minusDays(1)),
        lowStock = lowStock,
        khataDuePaise = positiveLedgerTotal("customer_ledger", "customer_id", customers),
        payablePaise = positiveLedgerTotal("supplier_ledger", "supplier_id", suppliers),
    )
    val snapshot = ReportSnapshot(
        query = query,
        sales = SalesReport(
            bills = selectedSales.size,
            grossPaise = grossPaise,
            returnsPaise = returnsPaise,
            discountPaise = discountPaise,
            taxPaise = taxPaise,
            profitPaise = (taxablePaise - costPaise) - (returnedTaxableAndCost.first - returnedTaxableAndCost.second),
        ),
        payments = payments,
        top = topProducts,
        stock = stock,
        purchaseSpendPaise = purchaseSpend,
    )
    val filterableProducts = products.filter { product ->
        product.cloudLong("is_active") != 0L &&
            (product.cloudString("parent_product_id") != null || product.cloudString("id") !in parentIds)
    }
    val options = ReportFilterOptions(
        products = filterableProducts.map { product ->
            ReportFilterOption(
                product.cloudString("id").orEmpty(),
                listOfNotNull(product.cloudString("name"), product.cloudString("variant_label"))
                    .joinToString(" · "),
            )
        },
        categories = categories.map { ReportFilterOption(it.cloudString("id").orEmpty(), it.cloudString("name").orEmpty()) },
        customers = customers.map { ReportFilterOption(it.cloudString("id").orEmpty(), it.cloudString("name").orEmpty()) },
        taxRatesBp = filterableProducts.mapNotNull { it.cloudInt("gst_rate_bp") }.distinct().sorted(),
        suppliers = suppliers.map { ReportFilterOption(it.cloudString("id").orEmpty(), it.cloudString("name").orEmpty()) },
        branches = branches.map { ReportFilterOption(it.cloudString("id").orEmpty(), it.cloudString("name").orEmpty()) },
    )
    return CloudReportResult(snapshot, options)
}
