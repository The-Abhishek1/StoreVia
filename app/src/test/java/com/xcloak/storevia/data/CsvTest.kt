package com.xcloak.storevia.data

import com.xcloak.storevia.core.util.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun parsesQuotesCommasAndNewlines() {
        val rows = Csv.parse("name,notes\r\n\"Tea, masala\",\"say \"\"hi\"\"\nsecond line\"\r\nSugar,plain\r\n")
        assertEquals(listOf("name", "notes"), rows[0])
        assertEquals(listOf("Tea, masala", "say \"hi\"\nsecond line"), rows[1])
        assertEquals(listOf("Sugar", "plain"), rows[2])
    }

    @Test fun skipsBlankLinesAndBom() {
        val rows = Csv.parse("\uFEFFa,b\n\n1,2\n   ,  \n")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
    }

    @Test fun semicolonFilesAreDetected() = assertEquals(listOf("a", "b", "c"), Csv.parse("a;b;c\n1;2;3")[0])

    @Test fun writeThenParseRoundTrips() {
        val data = listOf(listOf("name", "price"), listOf("Tea, \"special\"", "10.00"), listOf("Multi\nline", "5"))
        assertEquals(data, Csv.parse(Csv.write(data)))
    }

    @Test fun formulaCellsAreNeutralised() {
        assertEquals("'=SUM(A1)", Csv.safe("=SUM(A1)"))
        assertEquals("'@cmd", Csv.safe("@cmd"))
        assertEquals("Tea", Csv.safe("Tea"))
    }
}
