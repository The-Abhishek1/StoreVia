package com.xcloak.storevia.data.export

import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.ExportDao
import com.xcloak.storevia.core.util.Csv
import com.xcloak.storevia.core.util.Parsing
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class ExportKind(val fileName: String) {
    PRODUCTS("storevia-products.csv"), STOCK("storevia-stock-batches.csv"),
    SALES("storevia-sales.csv"), CUSTOMERS("storevia-customers.csv"),
    TEMPLATE("storevia-product-import-template.csv"),
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
        ExportKind.CUSTOMERS -> customers()
        ExportKind.TEMPLATE -> template()
    }

    private suspend fun products(): String = Csv.write(
        listOf(PRODUCT_HEADER) + exportDao.products().map {
            listOf(
                Csv.safe(it.name), Csv.safe(it.sku.orEmpty()), Csv.safe(it.barcode.orEmpty()), it.unitSymbol, it.hsnCode.orEmpty(),
                (it.gstRateBp / 100.0).toString().removeSuffix(".0"), if (it.taxInclusive) "yes" else "no",
                Parsing.paiseToPlain(it.costPaise), Parsing.paiseToPlain(it.sellPricePaise), it.mrpPaise?.let(Parsing::paiseToPlain).orEmpty(),
                Parsing.milliToInput(it.minStockMilli), Parsing.milliToInput(it.onHandMilli),
            )
        }
    )

    private suspend fun stock(): String = Csv.write(
        listOf(listOf("product", "unit", "batch_no", "expiry", "quantity", "cost")) + exportDao.batches().map {
            listOf(Csv.safe(it.productName), it.unitSymbol, Csv.safe(it.batchNo.orEmpty()), it.expiryDate?.toString().orEmpty(),
                Parsing.milliToInput(it.qtyOnHandMilli), Parsing.paiseToPlain(it.costPaise))
        }
    )

    private suspend fun sales(): String = Csv.write(
        listOf(listOf("invoice", "date", "customer", "status", "taxable_value", "tax", "discount", "round_off", "total", "returned", "paid", "due")) +
            exportDao.sales().map {
                listOf(
                    it.invoiceNo, Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).format(dateTime),
                    Csv.safe(it.customerName.orEmpty()), it.status,
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
            PRODUCT_HEADER.dropLast(1) + "opening_qty" + "batch_no" + "expiry",
            listOf("Parle-G Biscuits 100g", "PARLE100", "8901719101234", "pkt", "1905", "18", "yes", "7.50", "10", "10", "20", "50", "B2410", "2027-03-31"),
            listOf("Rice (loose)", "", "", "kg", "1006", "5", "yes", "42", "55", "", "10", "25.5", "", ""),
        )
    )

    companion object {
        val PRODUCT_HEADER = listOf(
            "name", "sku", "barcode", "unit", "hsn", "gst_rate", "tax_inclusive", "cost", "price", "mrp", "min_stock", "current_stock",
        )
    }
}
