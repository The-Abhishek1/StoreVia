package com.xcloak.storevia.domain.imports

import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.util.Csv
import com.xcloak.storevia.core.util.Parsing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class SalesImportSummary(
    val line: Int,
    val billId: String,
    val branchName: String?,
    val date: LocalDate,
    val customerName: String?,
    val paymentMethod: PaymentMethod?,
    val isCredit: Boolean,
    val discountPaise: Long,
    val taxPaise: Long,
    val totalPaise: Long,
)

data class SalesImportLine(
    val line: Int,
    val billId: String,
    val sku: String?,
    val productName: String?,
    val quantity: String,
    val unitPricePaise: Long,
)

data class SalesImportPayment(
    val line: Int,
    val billId: String,
    val method: PaymentMethod,
    val amountPaise: Long,
    val reference: String?,
)

data class SalesImportRowError(val line: Int, val message: String)

data class SalesImportDocument(
    val summaries: List<SalesImportSummary>,
    val lines: List<SalesImportLine>,
    val payments: List<SalesImportPayment>,
    val errors: List<SalesImportRowError>,
)

object SalesImportParser {
    private val dateFormats = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("d-M-yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
    )

    fun parse(summaryCsv: String, lineCsv: String, paymentCsv: String? = null): SalesImportDocument {
        val errors = mutableListOf<SalesImportRowError>()
        val summaryRows = Csv.parse(summaryCsv)
        val itemRows = Csv.parse(lineCsv)
        val summaries = parseSummaries(summaryRows, errors)
        val lines = parseLines(itemRows, errors)
        val payments = paymentCsv?.let { parsePayments(Csv.parse(it), errors) }.orEmpty()

        val billIds = summaries.map { it.billId }.toSet()
        val groupedLineIds = lines.map { it.billId }.toSet()
        lines.filter { it.billId !in billIds }.forEach {
            errors += SalesImportRowError(it.line, "Line item refers to bill '${it.billId}', which is missing from the sales summary.")
        }
        summaries.filter { it.billId !in groupedLineIds }.forEach {
            errors += SalesImportRowError(it.line, "Bill '${it.billId}' has no line items.")
        }
        payments.filter { it.billId !in billIds }.forEach {
            errors += SalesImportRowError(it.line, "Payment refers to bill '${it.billId}', which is missing from the sales summary.")
        }
        val paymentBills = payments.map { it.billId }.toSet()
        summaries.filter { it.paymentMethod == null && !it.isCredit && it.billId !in paymentBills }.forEach {
            errors += SalesImportRowError(it.line, "Split-payment bill '${it.billId}' has no payment rows.")
        }
        return SalesImportDocument(summaries, lines, payments, errors)
    }

    private fun parseSummaries(
        table: List<List<String>>,
        errors: MutableList<SalesImportRowError>,
    ): List<SalesImportSummary> {
        if (table.isEmpty()) {
            errors += SalesImportRowError(1, "Sales summary file is empty.")
            return emptyList()
        }
        val headers = table.first().mapIndexed { index, value -> normalize(value) to index }.toMap()
        val billColumn = headers.firstIndex("billid", "invoice", "invoiceid")
        val branchColumn = headers.firstIndex("branch", "branchname", "location")
        val dateColumn = headers.firstIndex("date")
        val paymentColumn = headers.firstIndex("paymentmode", "paymentmethod")
        val totalColumn = headers.firstIndex("total")
        val required = listOf("bill_id" to billColumn, "date" to dateColumn, "payment_mode" to paymentColumn, "total" to totalColumn)
        required.filter { it.second == null }.forEach { (name) ->
            errors += SalesImportRowError(1, "Sales summary is missing required column '$name'.")
        }
        if (required.any { it.second == null }) return emptyList()
        val customerColumn = headers.firstIndex("customer", "customername")
        val discountColumn = headers.firstIndex("discount")
        val taxColumn = headers.firstIndex("tax")
        val seen = mutableSetOf<String>()

        return table.drop(1).mapIndexedNotNull { index, row ->
            val line = index + 2
            fun value(column: Int?) = column?.let { row.getOrNull(it)?.trim().orEmpty() }.orEmpty()
            val billId = value(billColumn)
            val rawDate = value(dateColumn)
            val date = parseDate(rawDate)
            val mode = parsePayment(value(paymentColumn))
            val total = Parsing.moneyToPaise(value(totalColumn))
            val discount = if (discountColumn == null || value(discountColumn).isEmpty()) 0L else Parsing.moneyToPaise(value(discountColumn))
            val tax = if (taxColumn == null || value(taxColumn).isEmpty()) 0L else Parsing.moneyToPaise(value(taxColumn))

            var invalid = false
            if (billId.isBlank()) { errors += SalesImportRowError(line, "Bill ID is required."); invalid = true }
            if (billId.isNotBlank() && !seen.add(billId)) { errors += SalesImportRowError(line, "Bill ID '$billId' is duplicated."); invalid = true }
            if (date == null) { errors += SalesImportRowError(line, "Date '$rawDate' is invalid."); invalid = true }
            if (mode == null) { errors += SalesImportRowError(line, "Payment mode must be CASH, UPI, CARD, OTHER, CREDIT, or SPLIT."); invalid = true }
            if (total == null) { errors += SalesImportRowError(line, "Total is invalid."); invalid = true }
            if (discount == null) { errors += SalesImportRowError(line, "Discount is invalid."); invalid = true }
            if (tax == null) { errors += SalesImportRowError(line, "Tax is invalid."); invalid = true }
            if (invalid) null else SalesImportSummary(
                line, billId, value(branchColumn).ifBlank { null }, checkNotNull(date), value(customerColumn).ifBlank { null },
                mode?.first, checkNotNull(mode).second, checkNotNull(discount), checkNotNull(tax), checkNotNull(total),
            )
        }
    }

    private fun parsePayments(
        table: List<List<String>>,
        errors: MutableList<SalesImportRowError>,
    ): List<SalesImportPayment> {
        if (table.isEmpty()) {
            errors += SalesImportRowError(1, "Payments file is empty.")
            return emptyList()
        }
        val headers = table.first().mapIndexed { index, value -> normalize(value) to index }.toMap()
        val billColumn = headers.firstIndex("billid", "invoice", "invoiceid")
        val methodColumn = headers.firstIndex("paymentmode", "paymentmethod", "method")
        val amountColumn = headers.firstIndex("amount", "amountpaid")
        val referenceColumn = headers.firstIndex("reference", "ref")
        val required = listOf("bill_id" to billColumn, "payment_mode" to methodColumn, "amount" to amountColumn)
        required.filter { it.second == null }.forEach { (name) ->
            errors += SalesImportRowError(1, "Payments file is missing required column '$name'.")
        }
        if (required.any { it.second == null }) return emptyList()

        return table.drop(1).mapIndexedNotNull { index, row ->
            val line = index + 2
            fun value(column: Int?) = column?.let { row.getOrNull(it)?.trim().orEmpty() }.orEmpty()
            val billId = value(billColumn)
            val mode = parsePayment(value(methodColumn))
            val amount = Parsing.moneyToPaise(value(amountColumn))
            var invalid = false
            if (billId.isBlank()) { errors += SalesImportRowError(line, "Bill ID is required."); invalid = true }
            if (mode == null || mode.second) {
                errors += SalesImportRowError(line, "Payment mode must be CASH, UPI, CARD, or OTHER.")
                invalid = true
            }
            if (amount == null || amount <= 0) { errors += SalesImportRowError(line, "Payment amount must be positive."); invalid = true }
            if (invalid) null else SalesImportPayment(
                line, billId, checkNotNull(mode).first!!, checkNotNull(amount), value(referenceColumn).ifBlank { null },
            )
        }
    }

    private fun parseLines(
        table: List<List<String>>,
        errors: MutableList<SalesImportRowError>,
    ): List<SalesImportLine> {
        if (table.isEmpty()) {
            errors += SalesImportRowError(1, "Sales line-items file is empty.")
            return emptyList()
        }
        val headers = table.first().mapIndexed { index, value -> normalize(value) to index }.toMap()
        val billColumn = headers.firstIndex("billid", "invoice", "invoiceid")
        val skuColumn = headers.firstIndex("sku", "productsku")
        val nameColumn = headers.firstIndex("productname", "name")
        val quantityColumn = headers.firstIndex("qty", "quantity")
        val priceColumn = headers.firstIndex("unitprice", "price")
        val required = listOf("bill_id" to billColumn, "qty" to quantityColumn, "unit_price" to priceColumn)
        required.filter { it.second == null }.forEach { (name) ->
            errors += SalesImportRowError(1, "Sales line-items file is missing required column '$name'.")
        }
        if (skuColumn == null && nameColumn == null) {
            errors += SalesImportRowError(1, "Sales line-items file needs a SKU or product_name column.")
        }
        if (required.any { it.second == null } || (skuColumn == null && nameColumn == null)) return emptyList()

        return table.drop(1).mapIndexedNotNull { index, row ->
            val line = index + 2
            fun value(column: Int?) = column?.let { row.getOrNull(it)?.trim().orEmpty() }.orEmpty()
            val billId = value(billColumn)
            val sku = value(skuColumn).ifBlank { null }
            val name = value(nameColumn).ifBlank { null }
            val quantity = value(quantityColumn)
            val price = Parsing.moneyToPaise(value(priceColumn))
            var invalid = false
            if (billId.isBlank()) { errors += SalesImportRowError(line, "Bill ID is required."); invalid = true }
            if (sku == null && name == null) { errors += SalesImportRowError(line, "A SKU or product name is required."); invalid = true }
            if (Parsing.qtyToMilli(quantity, allowDecimal = true)?.let { it > 0 } != true) {
                errors += SalesImportRowError(line, "Quantity must be a positive number with at most three decimal places."); invalid = true
            }
            if (price == null) { errors += SalesImportRowError(line, "Unit price is invalid."); invalid = true }
            if (invalid) null else SalesImportLine(line, billId, sku, name, quantity, checkNotNull(price))
        }
    }

    private fun parseDate(value: String): LocalDate? = dateFormats.firstNotNullOfOrNull { formatter ->
        try { LocalDate.parse(value, formatter) } catch (_: DateTimeParseException) { null }
    }

    private fun parsePayment(value: String): Pair<PaymentMethod?, Boolean>? = when (normalize(value)) {
        "cash" -> PaymentMethod.CASH to false
        "upi", "qr" -> PaymentMethod.UPI to false
        "card", "creditcard", "debitcard" -> PaymentMethod.CARD to false
        "other" -> PaymentMethod.OTHER to false
        "credit", "khata", "onaccount" -> null to true
        "split", "partial" -> null to false
        else -> null
    }

    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

    private fun Map<String, Int>.firstIndex(vararg names: String): Int? =
        names.firstNotNullOfOrNull { this[normalize(it)] }
}
