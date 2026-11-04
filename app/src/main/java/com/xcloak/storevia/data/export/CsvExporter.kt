package com.xcloak.storevia.data.export

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.ExportDao
import com.xcloak.storevia.core.util.Csv
import com.xcloak.storevia.core.util.Parsing
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

enum class ExportKind(val fileName: String) {
    PRODUCTS("storevia-products.csv"), STOCK("storevia-stock-batches.csv"),
    SALES("storevia-sales.csv"), SALES_LINES("storevia-sales-lines.csv"), PAYMENTS("storevia-sales-payments.csv"),
    CUSTOMERS("storevia-customers.csv"),
    TEMPLATE("storevia-product-import-template.csv"),
}

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    PDF("pdf", "application/pdf"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
}

/** Builds CSV text. Text cells go through [Csv.safe] so a product called "=1+1" can't run as a formula. */
class CsvExporter @Inject constructor(
    private val exportDao: ExportDao,
    private val customerDao: CustomerDao,
) {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    suspend fun build(kind: ExportKind): String = when (kind) {
        ExportKind.PRODUCTS -> products()
        ExportKind.STOCK -> stock()
        ExportKind.SALES -> sales()
        ExportKind.SALES_LINES -> salesLines()
        ExportKind.PAYMENTS -> salesPayments()
        ExportKind.CUSTOMERS -> customers()
        ExportKind.TEMPLATE -> template()
    }

    suspend fun build(kind: ExportKind, format: ExportFormat): ByteArray {
        val csv = build(kind)
        return when (format) {
            ExportFormat.CSV -> csv.toByteArray(Charsets.UTF_8)
            ExportFormat.PDF -> pdf(kind, Csv.parse(csv))
            ExportFormat.XLSX -> xlsx(kind, Csv.parse(csv))
            ExportFormat.DOCX -> docx(kind, Csv.parse(csv))
        }
    }

    private suspend fun products(): String = Csv.write(
        listOf(PRODUCT_HEADER) + exportDao.products().map {
            listOf(
                Csv.safe(it.name), Csv.safe(it.sku.orEmpty()), Csv.safe(it.barcode.orEmpty()), Csv.safe(it.unitSymbol), Csv.safe(it.hsnCode.orEmpty()),
                (it.gstRateBp / 100.0).toString().removeSuffix(".0"), if (it.taxInclusive) "yes" else "no",
                Parsing.paiseToPlain(it.costPaise), Parsing.paiseToPlain(it.sellPricePaise), it.mrpPaise?.let(Parsing::paiseToPlain).orEmpty(),
                Parsing.milliToInput(it.minStockMilli), Parsing.milliToInput(it.onHandMilli), Csv.safe(it.attributesJson),
            )
        }
    )

    private suspend fun salesLines(): String = Csv.write(
        listOf(listOf("bill_id", "sku", "product_name", "qty", "unit_price")) +
        exportDao.saleLines().map {
            listOf(
                Csv.safe(it.invoiceNo), Csv.safe(it.sku.orEmpty()), Csv.safe(it.productName),
                Parsing.milliToInput(it.qtyMilli), Parsing.paiseToPlain(it.unitPricePaise),
            )
        }
    )

    private suspend fun salesPayments(): String = Csv.write(
        listOf(listOf("bill_id", "payment_mode", "amount", "reference")) +
            exportDao.salePayments().map {
                listOf(                Csv.safe(it.invoiceNo), Csv.safe(it.method), Parsing.paiseToPlain(it.amountPaise), Csv.safe(it.reference.orEmpty()))
            },
    )

    private suspend fun stock(): String = Csv.write(
        listOf(listOf("branch", "product", "unit", "batch_no", "expiry", "quantity", "cost")) + exportDao.batches().map {
            listOf(Csv.safe(it.branchName), Csv.safe(it.productName), Csv.safe(it.unitSymbol), Csv.safe(it.batchNo.orEmpty()), it.expiryDate?.toString().orEmpty(),
                Parsing.milliToInput(it.qtyOnHandMilli), Parsing.paiseToPlain(it.costPaise))
        }
    )

    private suspend fun sales(): String = Csv.write(
        listOf(listOf("bill_id", "branch", "date", "customer", "payment_mode", "status", "taxable_value", "tax", "discount", "round_off", "total", "returned", "paid", "due")) +
            exportDao.sales().map {
                listOf(
                    Csv.safe(it.invoiceNo), Csv.safe(it.branchName), Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                    Csv.safe(it.customerName.orEmpty()), Csv.safe(it.paymentMode), Csv.safe(it.status),
                    Parsing.paiseToPlain(it.subtotalPaise), Parsing.paiseToPlain(it.taxPaise), Parsing.paiseToPlain(it.discountPaise),
                    Parsing.paiseToPlain(it.roundOffPaise), Parsing.paiseToPlain(it.totalPaise), Parsing.paiseToPlain(it.returnedPaise),
                    Parsing.paiseToPlain(it.paidPaise), Parsing.paiseToPlain(it.totalPaise - it.returnedPaise - it.paidPaise),
                )
            }
    )

    private suspend fun customers(): String = Csv.write(
        listOf(listOf("name", "phone", "balance_due")) + customerDao.observeWithBalance("").first().map {
            listOf(Csv.safe(it.name), Csv.safe(it.phone.orEmpty()), Parsing.paiseToPlain(it.balancePaise))
        }
    )

    /** Header + two sample rows so the shop sees what to fill in. */
    private fun template(): String = Csv.write(
        listOf(
            PRODUCT_HEADER.dropLast(2) + "opening_qty" + "batch_no" + "expiry" + "attributes_json",
            listOf("Parle-G Biscuits 100g", "PARLE100", "8901719101234", "pkt", "1905", "18", "yes", "7.50", "10", "10", "20", "50", "B2410", "2027-03-31", "{}"),
            listOf("Rice (loose)", "", "", "kg", "1006", "5", "yes", "42", "55", "", "10", "25.5", "", "", "{}"),
        )
    )

    private fun pdf(kind: ExportKind, rows: List<List<String>>): ByteArray {
        val document = PdfDocument()
        val output = ByteArrayOutputStream()
        val columns = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val landscape = columns > 5
        val pageWidth = if (landscape) 842 else 595
        val pageHeight = if (landscape) 595 else 842
        val margin = 28f
        val usableWidth = pageWidth - margin * 2
        val columnWidth = usableWidth / columns
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; isFakeBoldText = true }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = if (landscape) 7f else 8f }
        val headerPaint = Paint(bodyPaint).apply { isFakeBoldText = true; color = android.graphics.Color.WHITE }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(31, 78, 121) }
        val cellPadding = 4f
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: android.graphics.Canvas? = null
        var y = 0f
        fun wrap(value: String, paint: Paint, width: Float): List<String> {
            if (value.isEmpty()) return listOf("")
            val result = mutableListOf<String>()
            var line = ""
            value.split(Regex("\\s+")).forEach { word ->
                if (line.isNotEmpty() && paint.measureText("$line $word") > width) {
                    result += line
                    line = word
                } else {
                    line = if (line.isEmpty()) word else "$line $word"
                }
                while (paint.measureText(line) > width && line.length > 1) {
                    var end = line.length
                    while (end > 1 && paint.measureText(line.substring(0, end)) > width) end--
                    result += line.substring(0, end)
                    line = line.substring(end)
                }
            }
            if (line.isNotEmpty()) result += line
            return result.ifEmpty { listOf("") }
        }
        fun drawRow(row: List<String>, header: Boolean) {
            val paint = if (header) headerPaint else bodyPaint
            val cells = (0 until columns).map { index ->
                wrap(row.getOrElse(index) { "" }, paint, columnWidth - cellPadding * 2).let { lines ->
                    if (lines.size <= 4) lines else lines.take(3) + listOf(lines[3].take(24) + "...")
                }
            }
            val rowHeight = maxOf(18f, (cells.maxOfOrNull { it.size } ?: 1) * (bodyPaint.textSize + 3f) + cellPadding * 2)
            val top = y
            cells.forEachIndexed { index, lines ->
                val left = margin + index * columnWidth
                val right = left + columnWidth
                if (header) canvas?.drawRect(left, top, right, top + rowHeight, headerFill)
                canvas?.drawRect(left, top, right, top + rowHeight, gridPaint)
                lines.forEachIndexed { lineIndex, line ->
                    canvas?.drawText(line, left + cellPadding, top + cellPadding + bodyPaint.textSize + lineIndex * (bodyPaint.textSize + 3f), paint)
                }
            }
            y += rowHeight
        }
        fun nextPage() {
            page?.let(document::finishPage)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page?.canvas
            y = margin
            canvas?.drawText(kind.name.replace('_', ' '), margin, y, titlePaint)
            y += 24f
            if (rows.isNotEmpty()) drawRow(rows.first(), header = true)
        }
        nextPage()
        rows.drop(1).forEach { row ->
            if (y + 60f > pageHeight - margin) nextPage()
            drawRow(row, header = false)
        }
        page?.let(document::finishPage)
        document.writeTo(output)
        document.close()
        return output.toByteArray()
    }

    private fun xlsx(kind: ExportKind, rows: List<List<String>>): ByteArray {
        val headers = rows.firstOrNull().orEmpty()
        val lastColumn = excelColumn(headers.size.coerceAtLeast(1))
        val lastRow = rows.size.coerceAtLeast(1)
        val contents = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:$lastColumn$lastRow"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>""")
            headers.forEachIndexed { index, header ->
                val width = (rows.maxOfOrNull { it.getOrNull(index)?.length ?: 0 } ?: 8).coerceIn(8, 40) + 2
                append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
            }
            append("</cols><sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                append("<row r=\"${rowIndex + 1}\">")
                row.forEachIndexed { columnIndex, value ->
                    val cellRef = "${excelColumn(columnIndex + 1)}${rowIndex + 1}"
                    val style = if (rowIndex == 0) 1 else numericStyle(headers.getOrNull(columnIndex).orEmpty())
                    val number = value.toBigDecimalOrNull()
                    if (rowIndex > 0 && style > 1 && number != null) {
                        append("""<c r="$cellRef" s="$style"><v>${number.stripTrailingZeros().toPlainString()}</v></c>""")
                    } else {
                        append("""<c r="$cellRef" s="$style" t="inlineStr"><is><t xml:space="preserve">${xml(value)}</t></is></c>""")
                    }
                }
                append("</row>")
            }
            append("</sheetData><autoFilter ref=\"A1:$lastColumn$lastRow\"/></worksheet>")
        }
        return zipped(
            mapOf(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
                "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xml(kind.name.take(31))}" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
                "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="#,##0.###"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F4E79"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD9E2F3"/></left><right style="thin"><color rgb="FFD9E2F3"/></right><top style="thin"><color rgb="FFD9E2F3"/></top><bottom style="thin"><color rgb="FFD9E2F3"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
                "xl/worksheets/sheet1.xml" to contents,
            ),
        )
    }

    private fun numericStyle(header: String): Int {
        val normalized = header.trim().lowercase()
        return when {
            normalized.contains("qty") || normalized.contains("quantity") || normalized.endsWith("_stock") -> 3
            normalized in setOf("gst_rate", "discount", "tax", "round_off", "total", "returned", "paid", "due", "balance_due") ||
                normalized.contains("price") || normalized.contains("cost") || normalized.contains("value") || normalized.contains("amount") -> 2
            else -> 0
        }
    }

    private fun docx(kind: ExportKind, rows: List<List<String>>): ByteArray =
        zipped(
            mapOf(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
                "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
                "word/document.xml" to buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
                    append("<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr><w:t>${xml(kind.name.replace('_', ' '))}</w:t></w:r></w:p>")
                    append("""<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblLayout w:type="autofit"/><w:tblBorders><w:top w:val="single" w:sz="4" w:color="B4C7E7"/><w:left w:val="single" w:sz="4" w:color="B4C7E7"/><w:bottom w:val="single" w:sz="4" w:color="B4C7E7"/><w:right w:val="single" w:sz="4" w:color="B4C7E7"/><w:insideH w:val="single" w:sz="4" w:color="B4C7E7"/><w:insideV w:val="single" w:sz="4" w:color="B4C7E7"/></w:tblBorders></w:tblPr><w:tblGrid>""")
                    repeat(rows.firstOrNull()?.size ?: 1) { append("""<w:gridCol w:w="1440"/>""") }
                    append("</w:tblGrid>")
                    rows.forEachIndexed { rowIndex, row ->
                        append("<w:tr>")
                        row.forEach { value ->
                            append("<w:tc><w:tcPr>")
                            if (rowIndex == 0) append("""<w:shd w:fill="1F4E79"/>""")
                            append("</w:tcPr><w:p><w:r>")
                            if (rowIndex == 0) append("<w:rPr><w:b/><w:color w:val=\"FFFFFF\"/></w:rPr>")
                            append("<w:t xml:space=\"preserve\">${xml(value)}</w:t></w:r></w:p></w:tc>")
                        }
                        append("</w:tr>")
                    }
                    append("</w:tbl><w:sectPr/></w:body></w:document>")
                },
            ),
        )

    private fun zipped(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char
                },
            )
        }
    }

    private fun excelColumn(number: Int): String {
        var n = number
        val result = StringBuilder()
        while (n > 0) {
            n--
            result.append(('A'.code + n % 26).toChar())
            n /= 26
        }
        return result.reverse().toString()
    }

    companion object {
        val PRODUCT_HEADER = listOf(
            "name", "sku", "barcode", "unit", "hsn", "gst_rate", "tax_inclusive", "cost", "price", "mrp", "min_stock", "current_stock", "attributes_json",
        )
    }
}
