package com.xcloak.storevia.data.imports

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportFileTextTest {
    @Test
    fun decodesUtf8Csv() {
        assertEquals("name,price\nTea,10", ImportFileText.decode("name,price\nTea,10".toByteArray()))
    }

    @Test
    fun decodesExcelUtf16LittleEndianCsv() {
        val text = "name,price\nTea,10"
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)

        assertEquals(text, ImportFileText.decode(bytes))
    }

    @Test
    fun decodesUtf16BigEndianCsv() {
        val text = "name,price\nTea,10"
        val bytes = byteArrayOf(0xfe.toByte(), 0xff.toByte()) + text.toByteArray(Charsets.UTF_16BE)

        assertEquals(text, ImportFileText.decode(bytes))
    }
}
