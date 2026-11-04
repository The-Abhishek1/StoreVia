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
import com.xcloak.storevia.data.export.CsvExporter
import com.xcloak.storevia.data.export.ExportFormat
import com.xcloak.storevia.data.export.ExportKind
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
import java.io.StringReader
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ExportFormatTest {
    private lateinit var db: StorviaDatabase
    private lateinit var context: Context
    private lateinit var exporter: CsvExporter

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StorviaDatabase::class.java).allowMainThreadQueries().build()
        exporter = CsvExporter(db.exportDao(), db.customerDao())
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

    private fun assertWellFormedXml(xml: String) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        while (parser.next() != XmlPullParser.END_DOCUMENT) Unit
    }
}
