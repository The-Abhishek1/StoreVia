package com.xcloak.storevia.data.imports

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Reads the first worksheet of an .xlsx file into the existing CSV import pipeline. */
object XlsxCsvReader {
    private const val MAX_ENTRY_BYTES = 5 * 1024 * 1024
    private const val MAX_COLUMNS = 1024

    fun read(bytes: ByteArray): String {
        val entries = readEntries(bytes)
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: throw IOException("The spreadsheet does not contain a first worksheet.")
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::readSharedStrings).orEmpty()
        val rows = readSheet(sheet, sharedStrings)
        if (rows.isEmpty()) throw IOException("The spreadsheet contains no worksheet rows.")
        return com.xcloak.storevia.core.util.Csv.write(rows)
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val wanted = entry.name in setOf("xl/worksheets/sheet1.xml", "xl/sharedStrings.xml")
                    val output = if (wanted) ByteArrayOutputStream() else null
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_ENTRY_BYTES) {
                            throw IOException("The spreadsheet content is too large.")
                        }
                        output?.write(buffer, 0, read)
                    }
                    if (output != null) entries[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(ByteArrayInputStream(bytes), "UTF-8")
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val parser = parser(bytes)
        val strings = mutableListOf<String>()
        var inString = false
        val value = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "si") {
                    inString = true
                    value.clear()
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inString) value.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si" && inString) {
                    strings += value.toString()
                    inString = false
                }
            }
        }
        return strings
    }

    private fun readSheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val parser = parser(bytes)
        val rows = mutableListOf<List<String>>()
        var rowCells: MutableMap<Int, String>? = null
        var cellReference: String? = null
        var cellType: String? = null
        var cellValue: String? = null
        val inlineValue = StringBuilder()
        var inInlineString = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> rowCells = linkedMapOf()
                    "c" -> {
                        cellReference = parser.getAttributeValue(null, "r")
                        cellType = parser.getAttributeValue(null, "t")
                        cellValue = null
                        inlineValue.clear()
                    }
                    "is" -> inInlineString = true
                    "v" -> if (cellReference != null) cellValue = parser.nextText()
                    "t" -> if (inInlineString) inlineValue.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val column = cellReference?.let(::columnIndex)
                            ?: throw IOException("The spreadsheet contains a cell without an address.")
                        if (column >= MAX_COLUMNS) throw IOException("The spreadsheet has too many columns.")
                        val value = when (cellType) {
                            "s" -> cellValue?.toIntOrNull()?.let(sharedStrings::getOrNull)
                                ?: throw IOException("The spreadsheet contains an invalid shared string.")
                            "inlineStr" -> inlineValue.toString()
                            else -> cellValue.orEmpty()
                        }
                        rowCells?.put(column, value)
                        cellReference = null
                    }
                    "is" -> inInlineString = false
                    "row" -> {
                        val cells = rowCells.orEmpty()
                        val width = (cells.keys.maxOrNull() ?: -1) + 1
                        rows += List(width) { cells[it].orEmpty() }
                        rowCells = null
                    }
                }
            }
        }
        return rows
    }

    private fun columnIndex(reference: String): Int {
        var value = 0
        var chars = 0
        for (char in reference) {
            if (!char.isLetter()) break
            value = value * 26 + (char.uppercaseChar() - 'A' + 1)
            chars++
        }
        if (chars == 0) throw IOException("The spreadsheet contains an invalid cell address.")
        return value - 1
    }
}
