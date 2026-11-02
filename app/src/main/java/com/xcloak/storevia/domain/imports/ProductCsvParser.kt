package com.xcloak.storevia.domain.imports

import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.core.util.Parsing
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

data class ImportRow(
    val line: Int,
    val name: String,
    val sku: String?,
    val barcode: String?,
    val unitId: String,
    val hsn: String?,
    val gstRateBp: Int,
    val taxInclusive: Boolean,
    val costPaise: Long,
    val pricePaise: Long,
    val mrpPaise: Long?,
    val minStockMilli: Long,
    val openingQtyMilli: Long,
    val batchNo: String?,
    val expiry: LocalDate?,
)

enum class ImportError {
    NAME_REQUIRED, PRICE_INVALID, COST_INVALID, MRP_INVALID, GST_INVALID, TAX_FLAG_INVALID,
    QTY_INVALID, MIN_STOCK_INVALID, EXPIRY_INVALID, UNIT_UNKNOWN, DUPLICATE_IN_FILE,
}

data class RowError(val line: Int, val error: ImportError, val detail: String = "")

data class ParseResult(
    val rows: List<ImportRow>,
    val errors: List<RowError>,
    /** Required columns that the file does not have (name, price). */
    val missingColumns: List<String>,
)

/**
 * Turns CSV rows into validated products. Column order doesn't matter; headers are matched by name
 * (several common spellings accepted). Every problem is reported with its line number, nothing is guessed:
 * a row with any error is left out rather than imported half-right.
 */
object ProductCsvParser {
    const val MAX_ROWS = 20_000

    private val ALIASES = mapOf(
        "name" to listOf("name", "product", "product name", "item", "item name"),
        "sku" to listOf("sku", "code", "item code"),
        "barcode" to listOf("barcode", "ean", "upc", "bar code"),
        "unit" to listOf("unit", "uom"),
        "hsn" to listOf("hsn", "hsn code", "hsn sac"),
        "gst" to listOf("gst", "gst rate", "gst %", "tax rate", "tax"),
        "taxIncl" to listOf("tax inclusive", "inclusive", "price includes gst", "price includes tax"),
        "cost" to listOf("cost", "cost price", "purchase price", "purchase cost", "buying price"),
        "price" to listOf("price", "selling price", "sell price", "sale price", "rate"),
        "mrp" to listOf("mrp"),
        "min" to listOf("min stock", "minimum stock", "reorder level", "min qty"),
        "qty" to listOf("opening qty", "opening stock", "current stock", "stock", "quantity", "qty"),
        "batch" to listOf("batch", "batch no", "batch number"),
        "expiry" to listOf("expiry", "expiry date", "exp", "exp date"),
    )

    // STRICT on purpose: the default (SMART) silently turns 31 Feb into 28 Feb, which would hide a typo in an expiry date.
    private val DATE_FORMATS = listOf("uuuu-M-d", "d/M/uuuu", "d-M-uuuu", "d.M.uuuu")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }

    private fun normalize(h: String) = h.trim().lowercase().replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ")

    fun parse(table: List<List<String>>, units: List<UnitEntity>): ParseResult {
        if (table.isEmpty()) return ParseResult(emptyList(), emptyList(), listOf("name", "price"))

        val header = table[0].map(::normalize)
        val col = ALIASES.mapValues { (_, names) -> header.indexOfFirst { it in names } }
        val missing = buildList {
            if (col["name"]!! < 0) add("name")
            if (col["price"]!! < 0) add("price")
        }
        if (missing.isNotEmpty()) return ParseResult(emptyList(), emptyList(), missing)

        val defaultUnit = units.firstOrNull { it.symbol == "pcs" } ?: units.firstOrNull()
        val rows = mutableListOf<ImportRow>()
        val errors = mutableListOf<RowError>()
        val seenBarcodes = mutableSetOf<String>()

        for ((index, cells) in table.drop(1).take(MAX_ROWS).withIndex()) {
            val line = index + 2   // header is line 1
            fun cell(key: String): String = col[key]!!.takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.trim().orEmpty()
            val rowErrors = mutableListOf<RowError>()
            fun fail(e: ImportError, detail: String = "") { rowErrors += RowError(line, e, detail) }

            val name = cell("name")
            if (name.isEmpty()) fail(ImportError.NAME_REQUIRED)

            val price = Parsing.moneyToPaise(cell("price")).also { if (it == null) fail(ImportError.PRICE_INVALID, cell("price")) }
            val costText = cell("cost")
            val cost = if (costText.isEmpty()) 0L else Parsing.moneyToPaise(costText).also { if (it == null) fail(ImportError.COST_INVALID, costText) }
            val mrpText = cell("mrp")
            val mrp = if (mrpText.isEmpty()) null else Parsing.moneyToPaise(mrpText).also { if (it == null) fail(ImportError.MRP_INVALID, mrpText) }

            val gstBp = parseGstBp(cell("gst")).also { if (it == null) fail(ImportError.GST_INVALID, cell("gst")) }
            val inclusive = parseYesNo(cell("taxIncl")).also { if (it == null) fail(ImportError.TAX_FLAG_INVALID, cell("taxIncl")) }

            val unitText = cell("unit")
            val unit = if (unitText.isEmpty()) defaultUnit
            else units.firstOrNull { it.symbol.equals(unitText, true) || it.name.equals(unitText, true) }
            if (unit == null) fail(ImportError.UNIT_UNKNOWN, unitText)

            val allowDecimal = unit?.allowDecimal ?: false
            val minText = cell("min")
            val minStock = if (minText.isEmpty()) 0L else Parsing.qtyToMilli(minText, allowDecimal).also { if (it == null) fail(ImportError.MIN_STOCK_INVALID, minText) }
            val qtyText = cell("qty")
            val qty = if (qtyText.isEmpty()) 0L else Parsing.qtyToMilli(qtyText, allowDecimal).also { if (it == null) fail(ImportError.QTY_INVALID, qtyText) }

            val expiryText = cell("expiry")
            val expiry = if (expiryText.isEmpty()) null else parseDate(expiryText).also { if (it == null) fail(ImportError.EXPIRY_INVALID, expiryText) }

            val barcode = cell("barcode").ifEmpty { null }
            if (barcode != null && !seenBarcodes.add(barcode)) fail(ImportError.DUPLICATE_IN_FILE, barcode)

            if (rowErrors.isNotEmpty()) { errors += rowErrors; continue }
            rows += ImportRow(
                line = line, name = name, sku = cell("sku").ifEmpty { null }, barcode = barcode, unitId = unit!!.id,
                hsn = cell("hsn").ifEmpty { null }, gstRateBp = gstBp!!, taxInclusive = inclusive!!,
                costPaise = cost!!, pricePaise = price!!, mrpPaise = mrp, minStockMilli = minStock!!,
                openingQtyMilli = qty!!, batchNo = cell("batch").ifEmpty { null }, expiry = expiry,
            )
        }
        return ParseResult(rows, errors, emptyList())
    }

    /** "18", "18%", "5.5" -> basis points. Blank means 0%. Null if invalid. */
    internal fun parseGstBp(text: String): Int? {
        val t = text.trim().removeSuffix("%").trim()
        if (t.isEmpty()) return 0
        val d = try { BigDecimal(t) } catch (_: NumberFormatException) { return null }
        if (d.signum() < 0 || d > BigDecimal(100) || d.scale() > 2) return null
        return d.movePointRight(2).toInt()
    }

    /** Blank defaults to yes (prices in India are usually MRP, tax included). Null if unrecognised. */
    internal fun parseYesNo(text: String): Boolean? = when (text.trim().lowercase()) {
        "", "y", "yes", "true", "1" -> true
        "n", "no", "false", "0" -> false
        else -> null
    }

    internal fun parseDate(text: String): LocalDate? {
        for (f in DATE_FORMATS) try { return LocalDate.parse(text.trim(), f) } catch (_: DateTimeParseException) { }
        return null
    }
}
