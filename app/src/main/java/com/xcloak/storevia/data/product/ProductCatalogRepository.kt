package com.xcloak.storevia.data.product

import com.xcloak.storevia.core.database.dao.CategoryDao
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.database.entity.CategoryEntity
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.ProductBarcodeEntity
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.core.database.dao.ProductListRow
import com.xcloak.storevia.core.database.dao.ProductVariantRow
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.prefs.StorageModeRouter
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

class CatalogCloudException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProductCatalogRepository @Inject constructor(
    private val categories: CategoryDao,
    private val units: UnitDao,
    private val products: ProductDao,
    private val batches: BatchDao,
    private val prefs: AppPrefs,
    private val client: SupabaseClient,
) {
    private val refreshGeneration = MutableStateFlow(0L)

    fun isCloudMode(): Boolean = prefs.settings.value.storageMode == StorageMode.CLOUD

    fun observeCategories(): Flow<List<CategoryEntity>> = prefs.settings
        .map { it.storageMode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            StorageModeRouter.route(
                mode,
                local = { categories.observeAll() },
                cloud = { cloudFlow { loadCategories() } },
            )
        }

    fun observeCategorySnapshot(): Flow<Pair<List<CategoryEntity>, Map<String, Int>>> = prefs.settings
        .map { it.storageMode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            StorageModeRouter.route(
                mode,
                local = {
                    combine(categories.observeAll(), categories.observeProductCounts()) { cats, counts ->
                        cats to counts.associate { it.id to it.count }
                    }
                },
                cloud = { cloudFlow { loadCategories() to categoryProductCountsCloud() } },
            )
        }

    fun observeUnits(): Flow<List<UnitEntity>> = prefs.settings
        .map { it.storageMode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            StorageModeRouter.route(mode, local = { units.observeAll() }, cloud = { cloudFlow { loadUnits() } })
        }

    suspend fun getProduct(id: String): ProductEntity? = routeSuspend(
        local = { products.getById(id) },
        cloud = { loadProducts().firstOrNull { it.id == id } },
    )

    suspend fun findProductByBarcode(barcode: String): ProductEntity? = routeSuspend(
        local = { products.findByBarcode(barcode) },
        cloud = {
            val productId = loadBarcodes().firstOrNull { it.barcode == barcode && it.deletedAt == null }?.productId
            productId?.let { id -> loadProducts().firstOrNull { it.id == id && it.deletedAt == null } }
        },
    )

    suspend fun getUnit(id: String): UnitEntity? = routeSuspend(
        local = { units.getById(id) },
        cloud = { loadUnits().firstOrNull { it.id == id } },
    )

    fun observeCheckoutRows(query: String, branchId: String): Flow<List<ProductListRow>> =
        observeStockRows(query, categoryId = null, branchId = branchId).map { it.take(8) }

    suspend fun fefoBatches(productId: String, today: LocalDate, branchId: String): List<BatchEntity> =
        routeSuspend(
            local = { batches.fefoBatches(productId, today, branchId) },
            cloud = {
                select("batches", requireStoreId())
                    .asSequence()
                    .filter { it.requiredString("product_id") == productId }
                    .filter { it.requiredString("branch_id") == branchId }
                    .filter { it.long("deleted_at") == null }
                    .map { row ->
                        BatchEntity(
                            id = row.requiredString("id"),
                            productId = row.requiredString("product_id"),
                            branchId = row.requiredString("branch_id"),
                            batchNo = row.string("batch_no"),
                            expiryDate = row.string("expiry_date")?.let(LocalDate::parse),
                            costPaise = row.long("cost_paise") ?: 0L,
                            mrpPaise = row.long("mrp_paise"),
                            qtyReceivedMilli = row.long("qty_received_milli") ?: 0L,
                            qtyOnHandMilli = row.long("qty_on_hand_milli") ?: 0L,
                            receivedAt = row.long("received_at") ?: 0L,
                            updatedAt = row.long("updated_at") ?: 0L,
                            deletedAt = row.long("deleted_at"),
                            dirty = false,
                        )
                    }
                    .filter { it.qtyOnHandMilli > 0 && (it.expiryDate == null || !it.expiryDate.isBefore(today)) }
                    .sortedWith(compareBy<BatchEntity> { it.expiryDate == null }.thenBy { it.expiryDate }.thenBy { it.receivedAt })
                    .toList()
            },
        )

    fun observeProduct(id: String): Flow<ProductEntity?> = prefs.settings
        .map { it.storageMode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            StorageModeRouter.route(
                mode,
                local = { products.observeById(id) },
                cloud = { cloudFlow { loadProducts().firstOrNull { it.id == id } } },
            )
        }

    fun observeActiveProducts(): Flow<List<ProductEntity>> = prefs.settings
        .map { it.storageMode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            StorageModeRouter.route(
                mode,
                local = { products.observeActiveSellable() },
                cloud = { cloudFlow { sellableProducts(loadProducts()) } },
            )
        }

    fun observeBarcode(productId: String): Flow<String?> = prefs.settings
        .map { it.storageMode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            StorageModeRouter.route(
                mode,
                local = { products.observeBarcode(productId) },
                cloud = { cloudFlow { loadBarcodes().firstOrNull { it.productId == productId && it.deletedAt == null }?.barcode } },
            )
        }

    fun observeVariants(parentId: String, branchId: String): Flow<List<ProductVariantRow>> = prefs.settings
        .map { it.storageMode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            StorageModeRouter.route(
                mode,
                local = { products.observeVariants(parentId, branchId) },
                cloud = {
                    combine(
                        cloudFlow { loadProducts().filter { it.parentProductId == parentId && it.deletedAt == null && it.isActive } },
                        cloudFlow { loadStockTotals(branchId) },
                        cloudFlow { loadUnits().associate { it.id to it.symbol } },
                    ) { cloudVariants, cloudStockTotals, unitSymbols ->
                        val stockById = cloudStockTotals
                        cloudVariants.map { p ->
                            ProductVariantRow(
                                id = p.id,
                                variantLabel = p.variantLabel.orEmpty(),
                                sku = p.sku,
                                sellPricePaise = p.sellPricePaise,
                                onHandMilli = stockById[p.id] ?: 0L,
                                unitSymbol = unitSymbols[p.unitId],
                            )
                        }.sortedBy { it.variantLabel }
                    }
                },
            )
        }

    fun observeStockRows(query: String, categoryId: String?, branchId: String): Flow<List<ProductListRow>> =
        prefs.settings
            .map { it.storageMode }
            .distinctUntilChanged()
            .flatMapLatest { mode ->
                StorageModeRouter.route(
                    mode,
                    local = {
                        if (categoryId == null) products.listWithStock(query, branchId)
                        else products.listWithStockByCategory(query, categoryId, branchId)
                    },
                    cloud = {
                        combine(
                            cloudFlow { loadProducts() },
                            cloudFlow { loadUnits().associate { it.id to it.symbol } },
                            cloudFlow { loadBarcodes().filter { it.deletedAt == null }.groupBy { it.productId } },
                            cloudFlow { loadStockTotals(branchId) },
                        ) { allProducts, unitSymbols, barcodes, stockRows ->
                            val sellable = sellableProducts(allProducts)
                            val stockById = stockRows
                            val needle = query.trim()
                            sellable.asSequence()
                                .filter { categoryId == null || it.categoryId == categoryId }
                                .filter { needle.isEmpty() || it.name.contains(needle, true) ||
                                    it.sku?.contains(needle, true) == true ||
                                    barcodes[it.id].orEmpty().any { bc -> bc.barcode == needle } }
                                .map { p ->
                                    val stock = stockById[p.id]
                                    ProductListRow(
                                        id = p.id,
                                        name = p.name,
                                        variantLabel = p.variantLabel,
                                        sellPricePaise = p.sellPricePaise,
                                        unitSymbol = unitSymbols[p.unitId].orEmpty(),
                                        onHandMilli = stock ?: 0L,
                                        minStockMilli = p.minStockMilli,
                                        categoryId = p.categoryId,
                                    )
                                }.sortedBy { it.name.lowercase() }.toList()
                        }
                    },
                )
            }

    suspend fun getBarcode(productId: String): String? = routeSuspend(
        local = { products.getBarcodes(productId).firstOrNull() },
        cloud = { loadBarcodes().firstOrNull { it.productId == productId && it.deletedAt == null }?.barcode },
    )

    suspend fun getVariants(parentId: String): List<ProductEntity> = routeSuspend(
        local = { products.getByParent(parentId) },
        cloud = { loadProducts().filter { it.parentProductId == parentId && it.deletedAt == null && it.isActive } },
    )

    suspend fun saveCategory(category: CategoryEntity) = routeSuspend(
        local = {
            if (categories.getById(category.id) == null) categories.insert(category) else categories.update(category)
        },
        cloud = { upsert("categories", category.toCloudJson(requireStoreId())) },
    )

    suspend fun deleteCategory(id: String, now: Long) = routeSuspend(
        local = {
            categories.clearFromProducts(id, now)
            categories.softDelete(id, now)
        },
        cloud = {
            cloudCall("Delete cloud category") {
                client.postgrest.rpc(
                    "delete_category_catalog",
                    buildJsonObject {
                        put("p_store_id", requireStoreId())
                        put("p_category_id", id)
                        put("p_now", now)
                    },
                )
            }
            refreshGeneration.update { it + 1 }
        },
    )

    suspend fun findCategoryByName(name: String): CategoryEntity? = routeSuspend(
        local = { categories.findByName(name) },
        cloud = { loadCategories().firstOrNull { it.name.equals(name, ignoreCase = true) && it.deletedAt == null } },
    )

    suspend fun categoryProductCounts(): Map<String, Int> = routeSuspend(
        local = { categories.observeProductCounts().let { flow -> flow.first().associate { it.id to it.count } } },
        cloud = { categoryProductCountsCloud() },
    )

    private suspend fun categoryProductCountsCloud(): Map<String, Int> =
        loadProducts().asSequence().filter { it.deletedAt == null && it.categoryId != null }
            .groupingBy { it.categoryId!! }.eachCount()

    private fun sellableProducts(all: List<ProductEntity>): List<ProductEntity> {
        val active = all.filter { it.deletedAt == null && it.isActive }
        val parentsWithActiveChildren = active.mapNotNull { it.parentProductId }.toSet()
        return active.filter { it.parentProductId != null || it.id !in parentsWithActiveChildren }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun saveProduct(product: ProductEntity, barcode: String?): SaveResult {
        requireCloudMode()
        return saveCloudProduct(product, barcode)
    }

    suspend fun archiveProduct(id: String) {
        requireCloudMode()
        archiveCloudProduct(id)
    }

    suspend fun hasStockOrHistory(productId: String): Boolean = routeSuspend(
        local = { products.currentStock(productId) != 0L || products.hasStockHistory(productId) },
        cloud = {
            val store = requireStoreId()
            val movements = exists("stock_movements", store, "product_id", productId)
            val batches = exists("batches", store, "product_id", productId, nonzeroQuantity = true)
            movements || batches
        },
    )

    suspend fun hasVariants(productId: String): Boolean = routeSuspend(
        local = { products.hasVariants(productId) },
        cloud = { loadProducts().any { it.parentProductId == productId && it.deletedAt == null && it.isActive } },
    )

    private suspend fun saveCloudProduct(product: ProductEntity, barcode: String?): SaveResult {
        val store = requireStoreId()
        if (product.parentProductId != null) {
            val parent = loadProducts().firstOrNull { it.id == product.parentProductId }
            if (parent == null || parent.deletedAt != null || parent.parentProductId != null ||
                hasCloudStockHistory(parent.id)
            ) return SaveResult.InvalidVariantParent
        }

        val normalized = barcode?.trim()?.takeIf(String::isNotEmpty)
        val existingBarcode = loadBarcodes().firstOrNull {
            it.barcode == normalized && it.deletedAt == null && it.productId != product.id
        }
        if (normalized != null && existingBarcode != null) {
            val owner = loadProducts().firstOrNull { it.id == existingBarcode.productId }
            return SaveResult.DuplicateBarcode(owner?.name ?: "another product")
        }

        val now = System.currentTimeMillis()
        val savedProduct = product.copy(updatedAt = now)
        val barcodeRow = normalized?.let {
            ProductBarcodeEntity(productId = product.id, barcode = it, updatedAt = now)
        }
        val result = cloudCall("Save cloud product") {
            client.postgrest.rpc(
                "save_product_catalog",
                buildJsonObject {
                    put("p_store_id", store)
                    put("p_product", savedProduct.toCloudJson(store))
                    put("p_barcode", normalized)
                    put("p_barcode_id", barcodeRow?.id)
                    put("p_now", now)
                },
            ).decodeAs<JsonObject>()
        }
        return when (result.requiredString("status")) {
            "saved" -> {
                refreshGeneration.update { it + 1 }
                SaveResult.Saved(result.requiredString("id"))
            }
            "duplicate_barcode" -> SaveResult.DuplicateBarcode(result.string("product_name") ?: "another product")
            "invalid_variant_parent" -> SaveResult.InvalidVariantParent
            else -> throw CatalogCloudException("Cloud product save returned an unknown result.")
        }
    }

    private suspend fun archiveCloudProduct(id: String) {
        val now = System.currentTimeMillis()
        cloudCall("Archive cloud product") {
            client.postgrest.rpc(
                "archive_product_catalog",
                buildJsonObject {
                    put("p_store_id", requireStoreId())
                    put("p_product_id", id)
                    put("p_now", now)
                },
            )
        }
        refreshGeneration.update { it + 1 }
    }

    private suspend fun hasCloudStockHistory(productId: String): Boolean {
        val store = requireStoreId()
        return exists("stock_movements", store, "product_id", productId)
    }

    private suspend fun loadCategories(): List<CategoryEntity> = select("categories", requireStoreId())
        .map { row ->
            CategoryEntity(
                id = row.requiredString("id"),
                name = row.requiredString("name"),
                parentId = row.string("parent_id"),
                updatedAt = row.long("updated_at") ?: 0L,
                deletedAt = row.long("deleted_at"),
                dirty = false,
            )
        }.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }

    private suspend fun loadUnits(): List<UnitEntity> = select("units", requireStoreId())
        .map { row ->
            UnitEntity(
                id = row.requiredString("id"),
                name = row.requiredString("name"),
                symbol = row.requiredString("symbol"),
                allowDecimal = row.long("allow_decimal") == 1L,
                updatedAt = row.long("updated_at") ?: 0L,
                dirty = false,
            )
        }.sortedBy { it.name.lowercase() }

    private suspend fun loadProducts(): List<ProductEntity> = select("products", requireStoreId()).map { row ->
        ProductEntity(
            id = row.requiredString("id"),
            name = row.requiredString("name"),
            parentProductId = row.string("parent_product_id"),
            variantLabel = row.string("variant_label"),
            sku = row.string("sku"),
            categoryId = row.string("category_id"),
            unitId = row.requiredString("unit_id"),
            hsnCode = row.string("hsn_code"),
            gstRateBp = row.long("gst_rate_bp")?.toInt() ?: 0,
            taxInclusive = row.long("tax_inclusive") == 1L,
            mrpPaise = row.long("mrp_paise"),
            sellPricePaise = row.long("sell_price_paise") ?: 0L,
            costPaise = row.long("cost_paise") ?: 0L,
            trackExpiry = row.long("track_expiry") == 1L,
            serialTracked = row.long("serial_tracked") == 1L,
            minStockMilli = row.long("min_stock_milli") ?: 0L,
            isActive = row.long("is_active") != 0L,
            attributesJson = row.string("attributes_json") ?: "{}",
            createdAt = row.long("created_at") ?: 0L,
            updatedAt = row.long("updated_at") ?: 0L,
            deletedAt = row.long("deleted_at"),
            dirty = false,
        )
    }

    private suspend fun loadBarcodes(): List<ProductBarcodeEntity> = select("product_barcodes", requireStoreId()).map { row ->
        ProductBarcodeEntity(
            id = row.requiredString("id"),
            productId = row.requiredString("product_id"),
            barcode = row.requiredString("barcode"),
            updatedAt = row.long("updated_at") ?: 0L,
            deletedAt = row.long("deleted_at"),
            dirty = false,
        )
    }

    private suspend fun loadStockTotals(branchId: String): Map<String, Long> =
        select("batches", requireStoreId())
            .asSequence()
            .filter { it.string("branch_id") == branchId && it.long("deleted_at") == null }
            .groupingBy { it.requiredString("product_id") }
            .fold(0L) { total, row -> total + (row.long("qty_on_hand_milli") ?: 0L) }

    private suspend fun select(table: String, storeId: String): List<JsonObject> =
        cloudCall("Read cloud $table") {
            val all = mutableListOf<JsonObject>()
            var offset = 0L
            while (true) {
                val page = client.from(table).select {
                    filter { eq("store_id", storeId) }
                    order("id", Order.ASCENDING)
                    range(offset, offset + PAGE_SIZE - 1L)
                }.decodeList<JsonObject>()
                all += page
                if (page.size < PAGE_SIZE) break
                offset += PAGE_SIZE
            }
            all
        }

    private suspend fun exists(
        table: String,
        storeId: String,
        field: String,
        value: String,
        nonzeroQuantity: Boolean = false,
    ): Boolean = cloudCall("Check cloud $table") {
        client.from(table).select {
            filter {
                eq("store_id", storeId)
                eq(field, value)
                if (nonzeroQuantity) neq("qty_on_hand_milli", 0)
            }
            limit(1)
        }.decodeList<JsonObject>().isNotEmpty()
    }

    private suspend fun upsert(table: String, row: JsonObject) {
        cloudCall("Save cloud $table") {
            client.from(table).upsert(row) { onConflict = "id" }
        }
        refreshGeneration.update { it + 1 }
    }

    private suspend fun <T> cloudCall(operation: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CatalogCloudException) {
        throw e
    } catch (e: Exception) {
        throw CatalogCloudException("$operation failed: ${e.message ?: "Supabase request failed."}", e)
    }

    private fun requireStoreId(): String = prefs.settings.value.cloudStoreId
        ?.takeIf(String::isNotBlank)
        ?: throw CatalogCloudException("No connected cloud store is selected.")

    private fun requireCloudMode() {
        if (!isCloudMode()) throw CatalogCloudException("Cloud catalog access was requested outside CLOUD mode.")
    }

    private suspend fun <T> routeSuspend(local: suspend () -> T, cloud: suspend () -> T): T {
        val action = StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { local },
            cloud = { cloud },
        )
        return action()
    }

    private fun <T> cloudFlow(read: suspend () -> T): Flow<T> = refreshGeneration.flatMapLatest {
        flow { emit(read()) }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.takeUnless { it.toString() == "null" }
        ?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredString(key: String): String =
        string(key) ?: throw CatalogCloudException("Cloud row is missing '$key'.")

    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private companion object {
        const val PAGE_SIZE = 1000
    }
}

private fun CategoryEntity.toCloudJson(storeId: String): JsonObject = buildJsonObject {
    put("id", id); put("name", name); put("parent_id", parentId); put("updated_at", updatedAt)
    put("deleted_at", deletedAt); put("store_id", storeId)
}

private fun ProductEntity.toCloudJson(storeId: String): JsonObject = buildJsonObject {
    put("id", id); put("name", name); put("parent_product_id", parentProductId); put("variant_label", variantLabel)
    put("sku", sku); put("category_id", categoryId); put("unit_id", unitId); put("hsn_code", hsnCode)
    put("gst_rate_bp", gstRateBp); put("tax_inclusive", taxInclusive.asDbInt()); put("mrp_paise", mrpPaise)
    put("sell_price_paise", sellPricePaise); put("cost_paise", costPaise); put("track_expiry", trackExpiry.asDbInt())
    put("serial_tracked", serialTracked.asDbInt()); put("min_stock_milli", minStockMilli)
    put("is_active", isActive.asDbInt()); put("attributes_json", attributesJson); put("created_at", createdAt)
    put("updated_at", updatedAt); put("deleted_at", deletedAt); put("store_id", storeId)
}

private fun ProductBarcodeEntity.toCloudJson(storeId: String): JsonObject = buildJsonObject {
    put("id", id); put("product_id", productId); put("barcode", barcode); put("updated_at", updatedAt)
    put("deleted_at", deletedAt); put("store_id", storeId)
}

private fun Boolean.asDbInt() = if (this) 1 else 0
