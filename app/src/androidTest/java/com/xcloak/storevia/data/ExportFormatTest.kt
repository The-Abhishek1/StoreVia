package com.xcloak.storevia.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.core.di.SupabaseModule
import com.xcloak.storevia.core.util.Csv
import com.xcloak.storevia.data.cloud.CloudImportExportRepository
import com.xcloak.storevia.data.export.CsvExporter
import com.xcloak.storevia.data.export.ExportFormat
import com.xcloak.storevia.data.export.ExportKind
import com.xcloak.storevia.data.imports.XlsxCsvReader
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ExportFormatTest {
    private lateinit var db: StorviaDatabase
    private lateinit var context: Context
    private lateinit var exporter: CsvExporter

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StorviaDatabase::class.java).allowMainThreadQueries().build()
        val prefs = AppPrefs(context).apply { update { copy(storageMode = StorageMode.LOCAL, cloudStoreId = null) } }
        exporter = CsvExporter(db.exportDao(), db.customerDao(), prefs, CloudImportExportRepository(SupabaseModule.supabase(), prefs))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        db.productDao().insert(
            ProductEntity(
                name = "Gadget",
                sku = "00017",
                unitId = unit.id,
                costPaise = 750,
                sellPricePaise = 1_000,
            ),
        )
    }

    @After fun tearDown() = db.close()

    @Test fun xlsxUsesNativeNumbersAndValidPackageXml() = runBlocking {
        val bytes = exporter.build(ExportKind.PRODUCTS, ExportFormat.XLSX)
        val entries = unzip(bytes)
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("""<c r="H2" s="2"><v>7.5</v></c>"""))
        assertTrue(sheet.contains("""<c r="B2" s="0" t="inlineStr">"""))
        entries.values.forEach(::assertWellFormedXml)
    }

    @Test fun xlsxProductsCanBeReadByTheImportCsvPipeline() = runBlocking {
        val rows = Csv.parse(XlsxCsvReader.read(exporter.build(ExportKind.PRODUCTS, ExportFormat.XLSX)))
        assertEquals("name", rows.first()[0])
        val product = rows.single { it.firstOrNull() == "Gadget" }
        assertEquals("00017", product[1])
        assertEquals("7.5", product[7])
    }

    @Test fun xlsxReaderSupportsSharedStringsAndEmptyCells() {
        val bytes = zip(
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>name</t></si><si><t>sell_price</t></si><si><t>Tea</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row><row r="2"><c r="A2" t="s"><v>2</v></c><c r="C2"><v>12.5</v></c></row></sheetData></worksheet>""",
        )
        assertEquals(
            listOf(listOf("name", "", "sell_price"), listOf("Tea", "", "12.5")),
            Csv.parse(XlsxCsvReader.read(bytes)),
        )
    }

    @Test fun csvEscapesFormulaLikeProductTextColumns() = runBlocking {
        val unit = UnitEntity(name = "Formula unit", symbol = " @SUM(A1)", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        db.productDao().insert(
            ProductEntity(name = "  =1+1", sku = "+SKU", hsnCode = "@HSN", unitId = unit.id, sellPricePaise = 0),
        )

        val rows = Csv.parse(exporter.build(ExportKind.PRODUCTS))
        val row = rows.single { it.firstOrNull() == "'  =1+1" }
        assertEquals("'+SKU", row[1])
        assertEquals("' @SUM(A1)", row[3])
        assertEquals("'@HSN", row[4])
    }

    @Test fun docxContainsAFormattedTableAndValidPackageXml() = runBlocking {
        val entries = unzip(exporter.build(ExportKind.PRODUCTS, ExportFormat.DOCX))
        val document = entries.getValue("word/document.xml")
        assertTrue(document.contains("<w:tbl>"))
        assertTrue(document.contains("<w:tblGrid>"))
        assertTrue(document.contains("""<w:shd w:fill="1F4E79"/>"""))
        entries.values.forEach(::assertWellFormedXml)
    }

    @Test fun pdfOpensAndContainsOnePage() = runBlocking {
        val file = File(context.cacheDir, "storevia-export-test.pdf")
        try {
            file.writeBytes(exporter.build(ExportKind.PRODUCTS, ExportFormat.PDF))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> assertEquals(1, renderer.pageCount) }
            }
        } finally {
            file.delete()
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun assertWellFormedXml(xml: String) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        while (parser.next() != XmlPullParser.END_DOCUMENT) Unit
    }
}
