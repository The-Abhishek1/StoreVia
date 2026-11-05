package com.xcloak.storevia.feature.stock

import com.xcloak.storevia.core.access.*
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.CategoryDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.database.entity.CategoryEntity
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.core.util.PackUnit
import com.xcloak.storevia.data.product.ProductRepository
import com.xcloak.storevia.data.product.SaveResult
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.core.store.StoreFeatureSet
import com.xcloak.storevia.core.store.StoreTypeConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

data class EditForm(
    val name: String = "",
    val variantLabel: String = "",
    val sku: String = "",
    val barcode: String = "",
    val unitId: String = "",
    val hsn: String = "",
    val gstRateBp: Int = 0,
    val taxInclusive: Boolean = true,
    val sellPrice: String = "",
    val cost: String = "",
    val minStock: String = "",
    val trackExpiry: Boolean = false,
    val serialTracked: Boolean = false,
    /** "" = no category. */
    val categoryId: String = "",
    val attributes: Map<String, String> = emptyMap(),
)

/** An error is a string resource plus an optional format argument. */
data class FieldError(@StringRes val res: Int, val arg: String? = null)

data class EditUi(
    val form: EditForm = EditForm(),
    val isNew: Boolean = true,
    val errors: Map<String, FieldError> = emptyMap(),
    val saving: Boolean = false,
)

sealed interface EditEvent {
    data class Saved(val productId: String) : EditEvent
    data object Archived : EditEvent
}

@HiltViewModel
class ProductEditViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val productDao: ProductDao,
    unitDao: UnitDao,
    categoryDao: CategoryDao,
    private val repo: ProductRepository,
    prefs: AppPrefs,
) : ViewModel() {

    private val productId: String? = handle["id"]
    private val parentId: String? = handle["parentId"]
    private var original: ProductEntity? = null
    val isVariant: Boolean = parentId != null

    private val _ui = MutableStateFlow(EditUi(isNew = productId == null))
    val ui: StateFlow<EditUi> = _ui.asStateFlow()

    val units: StateFlow<List<UnitEntity>> =
        unitDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val features: StateFlow<StoreFeatureSet> = prefs.settings.map { StoreTypeConfig.of(it.storeType) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StoreTypeConfig.of(prefs.settings.value.storeType))
    val attributePresets: StateFlow<List<ProductAttributePreset>> = prefs.settings.map {
        ProductAttributePresets.forType(it.storeType)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProductAttributePresets.forType(prefs.settings.value.storeType))

    private val _events = Channel<EditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Default unit once units exist (Piece if present).
        viewModelScope.launch {
            units.filter { it.isNotEmpty() }.collect { list ->
                if (_ui.value.form.unitId.isBlank())
                    update { copy(unitId = (list.firstOrNull { it.symbol == "pcs" } ?: list.first()).id) }
            }
        }
        handle.get<String>("barcode")?.takeIf { it.isNotBlank() }?.let { barcode ->
            update { copy(barcode = barcode) }
        }
        when {
            productId != null -> viewModelScope.launch { load(productId) }
            parentId != null -> viewModelScope.launch { loadVariantTemplate(parentId) }
        }
    }

    private suspend fun loadVariantTemplate(id: String) {
        val parent = productDao.getById(id) ?: return
        if (parent.deletedAt != null || parent.parentProductId != null) return
        _ui.update {
            it.copy(form = EditForm(
                name = parent.name,
                unitId = parent.unitId,
                hsn = parent.hsnCode.orEmpty(),
                gstRateBp = parent.gstRateBp,
                taxInclusive = parent.taxInclusive,
                sellPrice = Parsing.paiseToInput(parent.sellPricePaise),
                cost = Parsing.paiseToInput(parent.costPaise),
                minStock = if (parent.minStockMilli > 0) Parsing.milliToInput(parent.minStockMilli) else "",
                trackExpiry = parent.trackExpiry,
                categoryId = parent.categoryId.orEmpty(),
                attributes = decodeAttributes(parent.attributesJson),
            ))
        }
    }

    private suspend fun load(id: String) {
        val p = productDao.observeById(id).first() ?: return
        original = p
        val bc = productDao.getBarcodes(id).firstOrNull().orEmpty()
        _ui.update {
            it.copy(
                form = EditForm(
                    name = p.name, variantLabel = p.variantLabel.orEmpty(), sku = p.sku.orEmpty(), barcode = bc, unitId = p.unitId,
                    hsn = p.hsnCode.orEmpty(), gstRateBp = p.gstRateBp, taxInclusive = p.taxInclusive,
                    sellPrice = Parsing.paiseToInput(p.sellPricePaise), cost = Parsing.paiseToInput(p.costPaise),
                    minStock = if (p.minStockMilli > 0) Parsing.milliToInput(p.minStockMilli) else "",
                    trackExpiry = p.trackExpiry, categoryId = p.categoryId.orEmpty(),
                    serialTracked = p.serialTracked,
                    attributes = decodeAttributes(p.attributesJson),
                ),
            )
        }
    }

    fun update(block: EditForm.() -> EditForm) = _ui.update { it.copy(form = it.form.block(), errors = emptyMap()) }
    fun setBarcode(barcode: String) {
        if (barcode.isNotBlank()) update { copy(barcode = barcode) }
    }
    fun updateAttribute(key: String, value: String) = update {
        copy(attributes = attributes + (key to value))
    }
    fun addCustomAttribute(key: String) {
        val normalized = key.trim()
        if (normalized.isEmpty() || _ui.value.form.attributes.keys.any { it.equals(normalized, ignoreCase = true) }) return
        update { copy(attributes = attributes + (normalized to "")) }
    }
    fun removeAttribute(key: String) = update { copy(attributes = attributes - key) }

    fun save() {
        val f = _ui.value.form
        val unit = units.value.firstOrNull { it.id == f.unitId }
        val errors = mutableMapOf<String, FieldError>()

        if (f.name.isBlank()) errors["name"] = FieldError(R.string.err_name_required)
        if (isVariant && f.variantLabel.isBlank()) errors["variant"] = FieldError(R.string.err_variant_label_required)
        val sell = Parsing.moneyToPaise(f.sellPrice)
        if (sell == null) errors["sell"] = FieldError(R.string.err_price_invalid)
        val cost = if (f.cost.isBlank()) 0L else Parsing.moneyToPaise(f.cost)
        if (cost == null) errors["cost"] = FieldError(R.string.err_amount_invalid)
        val minStock = if (f.minStock.isBlank()) 0L else Parsing.qtyToMilli(f.minStock, unit?.allowDecimal ?: false)
        if (minStock == null) errors["min"] = FieldError(if (unit?.allowDecimal == false) R.string.err_qty_whole else R.string.err_qty_invalid)
        if (unit == null) errors["unit"] = FieldError(R.string.err_amount_invalid)
        if (f.serialTracked && unit?.allowDecimal != false) errors["serial"] = FieldError(R.string.err_serial_unit)
        if (original?.serialTracked == true && !f.serialTracked) errors["serial"] = FieldError(R.string.err_serial_disable)
        val packFieldsPresent = f.attributes[PackUnit.UNIT_NAME_ATTRIBUTE].orEmpty().isNotBlank() ||
            f.attributes[PackUnit.BASE_UNITS_ATTRIBUTE].orEmpty().isNotBlank()
        if (packFieldsPresent && runCatching { PackUnit.fromAttributesJson(encodeAttributes(f.attributes)) }.getOrNull() == null) {
            errors["pack"] = FieldError(R.string.err_product_pack_config)
        }

        if (errors.isNotEmpty()) { _ui.update { it.copy(errors = errors) }; return }

        val base = original ?: ProductEntity(name = f.name, unitId = f.unitId, sellPricePaise = sell!!)
        val product = base.copy(
            name = f.name.trim(), parentProductId = parentId ?: original?.parentProductId,
            variantLabel = if (isVariant) f.variantLabel.trim() else original?.variantLabel,
            sku = f.sku.trim().ifEmpty { null }, unitId = f.unitId,
            hsnCode = f.hsn.trim().ifEmpty { null }, gstRateBp = f.gstRateBp, taxInclusive = f.taxInclusive,
            sellPricePaise = sell!!, costPaise = cost!!, minStockMilli = minStock!!,
            trackExpiry = if (features.value.supportsExpiryTracking) f.trackExpiry else original?.trackExpiry ?: false,
            serialTracked = f.serialTracked,
            categoryId = f.categoryId.ifEmpty { null },
            attributesJson = encodeAttributes(f.attributes),
        )
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            try {
                if (product.serialTracked && original?.serialTracked != true) {
                    if (productDao.currentStock(product.id) != 0L || productDao.hasStockHistory(product.id)) {
                        _ui.update { it.copy(saving = false, errors = mapOf("serial" to FieldError(R.string.err_serial_existing_stock))) }
                        return@launch
                    }
                    if (productDao.hasVariants(product.id)) {
                        _ui.update { it.copy(saving = false, errors = mapOf("serial" to FieldError(R.string.err_serial_variant))) }
                        return@launch
                    }
                }
                when (val r = repo.save(product, f.barcode, isNew = original == null)) {
                    is SaveResult.Saved -> _events.send(EditEvent.Saved(r.id))
                    is SaveResult.DuplicateBarcode -> _ui.update {
                        it.copy(saving = false, errors = mapOf("barcode" to FieldError(R.string.err_barcode_used, r.productName)))
                    }
                    SaveResult.InvalidVariantParent -> _ui.update {
                        it.copy(saving = false, errors = mapOf("variant" to FieldError(R.string.err_variant_parent_invalid)))
                    }
                }
            } catch (e: PermissionDeniedException) {
                _ui.update { it.copy(saving = false, errors = mapOf("name" to FieldError(R.string.err_permission_denied))) }
            }
        }
    }

    fun archive() {
        val id = productId ?: return
        viewModelScope.launch { try { repo.archive(id); _events.send(EditEvent.Archived) } catch (_: PermissionDeniedException) { } }
    }

    private fun encodeAttributes(attributes: Map<String, String>): String =
        buildJsonObject {
            attributes.filterValues { it.isNotBlank() }.forEach { (key, value) -> put(key, value) }
        }.toString()

    private fun decodeAttributes(json: String): Map<String, String> =
        Json.parseToJsonElement(json).jsonObject.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap()
}
