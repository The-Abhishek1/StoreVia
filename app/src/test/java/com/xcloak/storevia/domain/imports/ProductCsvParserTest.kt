package com.xcloak.storevia.domain.imports

import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.core.util.Csv
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ProductCsvParserTest {
    private val pcs = UnitEntity(id = "u-pcs", name = "Piece", symbol = "pcs", allowDecimal = false)
    private val kg = UnitEntity(id = "u-kg", name = "Kilogram", symbol = "kg", allowDecimal = true)
    private val units = listOf(pcs, kg)

    private fun parse(csv: String) = ProductCsvParser.parse(Csv.parse(csv), units)

    @Test fun goodRowsParseAndAliasesAreAccepted() {
        val r = parse(
            "Product Name,Selling Price,GST %,Unit,Opening Stock,Expiry Date,Barcode\n" +
                "Tea,55,5%,kg,25.5,31/03/2027,890\n" + "Soap,30,,,10,,"
        )
        assertTrue(r.errors.isEmpty()); assertEquals(2, r.rows.size)
        val tea = r.rows[0]
        assertEquals(5_500L, tea.pricePaise); assertEquals(500, tea.gstRateBp); assertEquals("u-kg", tea.unitId)
        assertEquals(25_500L, tea.openingQtyMilli); assertEquals(LocalDate.of(2027, 3, 31), tea.expiry); assertEquals("890", tea.barcode)
        val soap = r.rows[1]
        assertEquals("u-pcs", soap.unitId)            // blank unit -> Piece
        assertEquals(0, soap.gstRateBp); assertTrue(soap.taxInclusive)
    }

    @Test fun missingRequiredColumnsAreReported() {
        val r = parse("sku,cost\nA,5")
        assertEquals(listOf("name", "price"), r.missingColumns); assertTrue(r.rows.isEmpty())
    }

    @Test fun everyProblemIsReportedWithItsLineNumber() {
        val r = parse("name,price,gst_rate,tax_inclusive,expiry,unit,opening_qty\n" +
            ",10,5,yes,,,\n" +                    // line 2: no name
            "A,abc,5,yes,,,\n" +                  // line 3: bad price
            "B,10,150,yes,,,\n" +                 // line 4: bad GST
            "C,10,5,maybe,,,\n" +                 // line 5: bad flag
            "D,10,5,yes,notadate,,\n" +           // line 6: bad date
            "E,10,5,yes,,bag,\n" +                // line 7: unknown unit
            "F,10,5,yes,,pcs,1.5\n" +             // line 8: fractional pieces
            "G,10,5,yes,,,\n")                    // line 9: fine
        assertEquals(1, r.rows.size); assertEquals("G", r.rows[0].name)
        val byLine = r.errors.associate { it.line to it.error }
        assertEquals(ImportError.NAME_REQUIRED, byLine[2]); assertEquals(ImportError.PRICE_INVALID, byLine[3])
        assertEquals(ImportError.GST_INVALID, byLine[4]); assertEquals(ImportError.TAX_FLAG_INVALID, byLine[5])
        assertEquals(ImportError.EXPIRY_INVALID, byLine[6]); assertEquals(ImportError.UNIT_UNKNOWN, byLine[7])
        assertEquals(ImportError.QTY_INVALID, byLine[8])
    }

    @Test fun duplicateBarcodeInsideFileIsRejected() {
        val r = parse("name,price,barcode\nA,10,111\nB,12,111\nC,5,")
        assertEquals(2, r.rows.size)
        assertEquals(ImportError.DUPLICATE_IN_FILE, r.errors.single().error); assertEquals(3, r.errors.single().line)
    }

    @Test fun dateAndGstHelpers() {
        assertEquals(LocalDate.of(2027, 1, 5), ProductCsvParser.parseDate("2027-01-05"))
        assertEquals(LocalDate.of(2027, 1, 5), ProductCsvParser.parseDate("5-1-2027"))
        assertNull(ProductCsvParser.parseDate("31/02/2027"))
        assertEquals(1_800, ProductCsvParser.parseGstBp("18")); assertEquals(250, ProductCsvParser.parseGstBp("2.5%"))
        assertNull(ProductCsvParser.parseGstBp("-1")); assertNull(ProductCsvParser.parseGstBp("1.234"))
    }
}
