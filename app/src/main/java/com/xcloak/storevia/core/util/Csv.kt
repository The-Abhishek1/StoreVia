package com.xcloak.storevia.core.util

/** Small RFC 4180 reader/writer. Handles quotes, embedded commas/newlines, a UTF-8 BOM and ';' exports. */
object Csv {
    fun parse(input: String): List<List<String>> {
        val text = input.removePrefix("\uFEFF")
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','

        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.clear() }
        fun endRow() {
            endCell()
            if (row.any { it.isNotBlank() }) rows.add(row)   // skip blank lines
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> cell.append(c)
                }
                c == '"' -> inQuotes = true
                c == delimiter -> endCell()
                c == '\n' -> endRow()
                c == '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** With a BOM so Excel opens UTF-8 (₹, Hindi, Kannada) correctly. */
    fun write(rows: List<List<String>>): String =
        "\uFEFF" + rows.joinToString("\r\n") { r -> r.joinToString(",") { quote(it) } } + "\r\n"

    private fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Spreadsheet formula injection guard for TEXT cells (names, notes). Don't use for numbers. */
    fun safe(text: String): String {
        val firstNonWhitespace = text.firstOrNull { !it.isWhitespace() }
        return if (firstNonWhitespace != null && firstNonWhitespace in "=+-@") "'$text" else text
    }
}
