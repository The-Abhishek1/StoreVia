package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.core.database.dao.BatchExportRow
import com.xcloak.storevia.core.database.dao.ProductExportRow
import com.xcloak.storevia.core.database.dao.SaleExportRow
import com.xcloak.storevia.core.database.dao.SaleLineExportRow
import com.xcloak.storevia.core.database.dao.SalePaymentExportRow
import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.domain.imports.DuplicatePolicy
import com.xcloak.storevia.domain.imports.ImportPreview
import com.xcloak.storevia.domain.imports.ImportRow
import com.xcloak.storevia.domain.imports.ImportSummary
import com.xcloak.storevia.domain.imports.SalesImportDocument
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class CustomerExportRow(val name: String, val phone: String?, val balancePaise: Long)

class CloudImportExportException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Server-only import/export reads and writes for CLOUD mode. */
@Singleton
class CloudImportExportRepository @Inject constructor(
    private val client: SupabaseClient,
    private val prefs: AppPrefs,
) {
    suspend fun units(): List<UnitEntity> = guarded("Loading cloud units") {
        rows("units").map { row ->
            UnitEntity(
                id = row.string("id"),
                name = row.string("name"),
                symbol = row.string("symbol"),
                allowDecimal = row.int("allow_decimal") != 0,
                updatedAt = row.long("updated_at"),
                dirty = false,
            )
        }
    }

    suspend fun previewProducts(importRows: List<ImportRow>): ImportPreview = guarded("Checking cloud products") {
        val products = rows("products").filter { it.longOrNull("deleted_at") == null }
        val barcodes = rows("product_barcodes").filter { it.longOrNull("deleted_at") == null }
        val activeBarcodes = barcodes.groupBy { it.string("product_id") }
        val existingCount = importRows.count { imported ->
            products.any { existing ->
                val barcodeMatches = imported.barcode != null &&
                    activeBarcodes[existing.string("id")].orEmpty().any { it.string("barcode") == imported.barcode }
                barcodeMatches || (imported.sku != null && existing.stringOrNull("sku") == imported.sku) ||
                    (imported.barcode == null && imported.sku == null && existing.string("name").equals(imported.name, true))
            }
        }
        ImportPreview(newRows = importRows.size - existingCount, existingRows = existingCount)
    }

    suspend fun importProducts(rows: List<ImportRow>, policy: DuplicatePolicy): ImportSummary =
        guarded("Importing products into Supabase") {
            requireCloudMode()
            require(rows.isNotEmpty()) { "There are no valid product rows to import." }
            val knownUnits = units().map { it.id }.toSet()
            require(rows.all { it.unitId in knownUnits }) {
                "The selected import refers to a unit that is no longer available in the cloud store."
            }
            val branchId = if (rows.any { it.openingQtyMilli > 0 }) {
                val activeBranches = rows("branches").filter {
                    it.int("is_active") != 0 && it.longOrNull("deleted_at") == null
                }
                require(activeBranches.size == 1) {
                    "Opening stock cannot be assigned safely: cloud product imports require exactly one active branch."
                }
                activeBranches.single().string("id")
            } else {
                null
            }
            val response = client.postgrest.rpc(
                "storevia_import_products",
                buildJsonObject {
                    put("p_store_id", requireStoreId())
                    put("p_policy", policy.name)
                    if (branchId == null) put("p_branch_id", kotlinx.serialization.json.JsonNull)
                    else put("p_branch_id", branchId)
                    putJsonArray("p_rows") {
                        rows.forEach { row ->
                            add(buildJsonObject {
                                put("line", row.line)
                                put("name", row.name)
                                putNullable("sku", row.sku)
                                putNullable("barcode", row.barcode)
                                put("unit_id", row.unitId)
                                putNullable("hsn", row.hsn)
                                put("gst_rate_bp", row.gstRateBp)
                                put("tax_inclusive", row.taxInclusive)
                                put("cost_paise", row.costPaise)
                                put("price_paise", row.pricePaise)
                                putNullable("mrp_paise", row.mrpPaise)
                                put("min_stock_milli", row.minStockMilli)
                                put("opening_qty_milli", row.openingQtyMilli)
                                putNullable("batch_no", row.batchNo)
                                putNullable("expiry", row.expiry?.toString())
                                putNullable("attributes_json", row.attributesJson)
                            })
                        }
                    }
                },
            ).decodeAs<JsonObject>()
            ImportSummary(response.int("created"), response.int("updated"), response.int("skipped"))
        }

    suspend fun importSales(document: SalesImportDocument, requestId: String): Int =
        guarded("Importing sales into Supabase") {
            requireCloudMode()
            require(document.errors.isEmpty()) { "Resolve the sales CSV validation errors before importing." }
            require(document.summaries.isNotEmpty() && document.lines.isNotEmpty()) {
                "There are no valid sales rows to import."
            }
            val payload = buildJsonObject {
                putJsonArray("summaries") {
                    document.summaries.forEach { summary ->
                        add(buildJsonObject {
                            put("bill_id", summary.billId)
                            putNullable("branch_name", summary.branchName)
                            put("created_at", summary.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                            putNullable("customer_name", summary.customerName)
                            putNullable("payment_method", summary.paymentMethod?.name)
                            put("is_credit", summary.isCredit)
                            put("discount_paise", summary.discountPaise)
                            put("tax_paise", summary.taxPaise)
                            put("total_paise", summary.totalPaise)
                        })
                    }
                }
                putJsonArray("lines") {
                    document.lines.forEach { line ->
                        add(buildJsonObject {
                            put("bill_id", line.billId)
                            putNullable("sku", line.sku)
                            putNullable("product_name", line.productName)
                            put("quantity", line.quantity)
                            put("unit_price_paise", line.unitPricePaise)
                        })
                    }
                }
                putJsonArray("payments") {
                    document.payments.forEach { payment ->
                        add(buildJsonObject {
                            put("bill_id", payment.billId)
                            put("method", payment.method.name)
                            put("amount_paise", payment.amountPaise)
                            putNullable("reference", payment.reference)
                        })
                    }
                }
            }
            val result = client.postgrest.rpc(
                "storevia_import_sales",
                buildJsonObject {
                    put("p_store_id", requireStoreId())
                    put("p_request_id", requestId)
                    put("p_document", payload)
                },
            ).decodeAs<JsonObject>()
            result.int("imported")
        }

    suspend fun products(): List<ProductExportRow> = guarded("Exporting cloud products") {
        val units = rows("units").associateBy { it.string("id") }
        val barcodes = rows("product_barcodes").filter { it.longOrNull("deleted_at") == null }
            .groupBy { it.string("product_id") }
        val stock = rows("batches").filter { it.longOrNull("deleted_at") == null }
            .groupBy { it.string("product_id") }.mapValues { (_, batches) ->
                batches.sumOf { it.long("qty_on_hand_milli") }
            }
        rows("products").asSequence()
            .filter { it.longOrNull("deleted_at") == null }
            .mapNotNull { row ->
                val unit = units[row.string("unit_id")] ?: return@mapNotNull null
                ProductExportRow(
                    name = row.string("name"),
                    sku = row.stringOrNull("sku"),
                    barcode = barcodes[row.string("id")].orEmpty().firstOrNull()?.stringOrNull("barcode"),
                    unitSymbol = unit.string("symbol"),
                    hsnCode = row.stringOrNull("hsn_code"),
                    gstRateBp = row.int("gst_rate_bp"),
                    taxInclusive = row.int("tax_inclusive") != 0,
                    costPaise = row.long("cost_paise"),
                    sellPricePaise = row.long("sell_price_paise"),
                    mrpPaise = row.longOrNull("mrp_paise"),
                    minStockMilli = row.long("min_stock_milli"),
                    onHandMilli = stock[row.string("id")] ?: 0L,
                    attributesJson = row.string("attributes_json"),
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    suspend fun batches(): List<BatchExportRow> = guarded("Exporting cloud stock") {
        val products = rows("products").associateBy { it.string("id") }
        val units = rows("units").associateBy { it.string("id") }
        val branches = rows("branches").associateBy { it.string("id") }
        rows("batches").asSequence()
            .filter { it.longOrNull("deleted_at") == null && it.long("qty_on_hand_milli") > 0 }
            .mapNotNull { batch ->
                val product = products[batch.string("product_id")] ?: return@mapNotNull null
                val unit = units[product.string("unit_id")] ?: return@mapNotNull null
                val branch = branches[batch.string("branch_id")] ?: return@mapNotNull null
                BatchExportRow(
                    branchName = branch.string("name"),
                    productName = product.string("name"),
                    unitSymbol = unit.string("symbol"),
                    batchNo = batch.stringOrNull("batch_no"),
                    expiryDate = batch.stringOrNull("expiry_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    qtyOnHandMilli = batch.long("qty_on_hand_milli"),
                    costPaise = batch.long("cost_paise"),
                )
            }
            .sortedWith(compareBy<BatchExportRow> { it.productName.lowercase() }.thenBy { it.expiryDate })
            .toList()
    }

    suspend fun sales(): List<SaleExportRow> = guarded("Exporting cloud sales") {
        val branches = rows("branches").associateBy { it.string("id") }
        val customers = rows("customers").associateBy { it.string("id") }
        val payments = rows("payments").filter { it.long("amount_paise") > 0 }
            .groupBy { it.stringOrNull("sale_id") }
        val sales = rows("sales")
        val linesBySale = rows("sale_lines").groupBy { it.string("sale_id") }
        sales.mapNotNull { sale ->
            val branch = branches[sale.string("branch_id")] ?: return@mapNotNull null
            val customer = sale.stringOrNull("customer_id")?.let(customers::get)
            val salePayments = payments[sale.string("id")].orEmpty()
            val total = sale.long("total_paise")
            val returned = sale.long("returned_paise")
            val paid = sale.long("paid_paise")
            val paymentMode = when {
                total - returned > paid -> "CREDIT"
                salePayments.map { it.string("method") }.distinct().size > 1 -> "SPLIT"
                else -> salePayments.minOfOrNull { it.string("method") } ?: "OTHER"
            }
            val subtotal = linesBySale[sale.string("id")].orEmpty().sumOf { it.long("taxable_value_paise") }
            SaleExportRow(
                invoiceNo = sale.string("invoice_no"),
                branchName = branch.string("name"),
                createdAt = sale.long("created_at"),
                customerName = customer?.stringOrNull("name"),
                status = sale.string("status"),
                subtotalPaise = subtotal,
                taxPaise = sale.long("tax_paise"),
                discountPaise = sale.long("discount_paise"),
                roundOffPaise = sale.long("round_off_paise"),
                totalPaise = total,
                returnedPaise = returned,
                paidPaise = paid,
                paymentMode = paymentMode,
            )
        }.sortedBy { it.createdAt }
    }

    suspend fun saleLines(): List<SaleLineExportRow> = guarded("Exporting cloud sale lines") {
        val sales = rows("sales").associateBy { it.string("id") }
        val products = rows("products").associateBy { it.string("id") }
        rows("sale_lines").mapNotNull { line ->
            val sale = sales[line.string("sale_id")] ?: return@mapNotNull null
            val product = products[line.string("product_id")]
            SaleLineExportRow(
                invoiceNo = sale.string("invoice_no"),
                sku = product?.stringOrNull("sku"),
                productName = line.string("name_snapshot"),
                qtyMilli = line.long("qty_milli"),
                unitPricePaise = line.long("unit_price_paise"),
            )
        }.sortedBy { it.invoiceNo }
    }

    suspend fun salePayments(): List<SalePaymentExportRow> = guarded("Exporting cloud payments") {
        val sales = rows("sales").associateBy { it.string("id") }
        rows("payments").filter { it.long("amount_paise") > 0 }.mapNotNull { payment ->
            val sale = payment.stringOrNull("sale_id")?.let(sales::get) ?: return@mapNotNull null
            SalePaymentExportRow(
                invoiceNo = sale.string("invoice_no"),
                method = payment.string("method"),
                amountPaise = payment.long("amount_paise"),
                reference = payment.stringOrNull("reference"),
            )
        }.sortedWith(compareBy<SalePaymentExportRow> { it.invoiceNo }.thenBy { it.method })
    }

    suspend fun customers(): List<CustomerExportRow> = guarded("Exporting cloud customers") {
        val balances = rows("customer_ledger").groupBy { it.string("customer_id") }
            .mapValues { (_, entries) -> entries.sumOf { it.long("amount_paise") } }
        rows("customers").asSequence()
            .filter { it.longOrNull("deleted_at") == null }
            .map {
                CustomerExportRow(
                    name = it.string("name"),
                    phone = it.stringOrNull("phone"),
                    balancePaise = balances[it.string("id")] ?: 0L,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    private suspend fun rows(table: String): List<JsonObject> {
        val storeId = requireStoreId()
        val result = mutableListOf<JsonObject>()
        var offset = 0L
        val pageSize = 500L
        while (true) {
            val page = client.from(table).select {
                filter { eq("store_id", storeId) }
                order("id", Order.ASCENDING)
                range(offset, offset + pageSize - 1)
            }.decodeList<JsonObject>()
            result += page
            if (page.size < pageSize) return result
            offset += pageSize
        }
    }

    private suspend fun <T> guarded(operation: String, action: suspend () -> T): T = try {
        requireCloudMode()
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudImportExportException) {
        throw e
    } catch (e: Exception) {
        throw CloudImportExportException("$operation failed. Supabase was not replaced with local data: ${e.message ?: "remote request failed."}", e)
    }

    private fun requireCloudMode() {
        check(prefs.settings.value.storageMode == StorageMode.CLOUD) {
            "Cloud import/export access requires CLOUD storage mode."
        }
    }

    private fun requireStoreId(): String = checkNotNull(
        prefs.settings.value.cloudStoreId?.takeIf(String::isNotBlank),
    ) { "Cloud import/export access requires a connected store." }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long = longOrNull(key) ?: 0L
    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(key: String): Int = long(key).toInt()
    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, kotlinx.serialization.json.JsonNull) else put(key, value)
    }
    private fun JsonObjectBuilder.putNullable(key: String, value: Long?) {
        if (value == null) put(key, kotlinx.serialization.json.JsonNull) else put(key, value)
    }

}
