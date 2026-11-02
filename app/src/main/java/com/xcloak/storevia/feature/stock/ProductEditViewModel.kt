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
import com.xcloak.storevia.data.product.ProductRepository
import com.xcloak.storevia.data.product.SaveResult
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.core.store.StoreFeatureSet
import com.xcloak.storevia.core.store.StoreTypeConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditForm(
    val name: String = "",
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
    /** "" = no category. */
    val categoryId: String = "",
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
    private var original: ProductEntity? = null

    private val _ui = MutableStateFlow(EditUi(isNew = productId == null))
    val ui: StateFlow<EditUi> = _ui.asStateFlow()

    val units: StateFlow<List<UnitEntity>> =
        unitDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val features: StateFlow<StoreFeatureSet> = prefs.settings.map { StoreTypeConfig.of(it.storeType) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StoreTypeConfig.of(prefs.settings.value.storeType))

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
        // "barcode" is set either by the nav argument (Add with this barcode) or by the scanner screen.
        viewModelScope.launch {
            handle.getStateFlow<String?>("barcode", null).filterNotNull().collect {
                if (it.isNotBlank()) update { copy(barcode = it) }
                handle["barcode"] = null
            }
        }
        productId?.let { id -> viewModelScope.launch { load(id) } }
    }

    private suspend fun load(id: String) {
        val p = productDao.observeById(id).first() ?: return
        original = p
        val bc = productDao.getBarcodes(id).firstOrNull().orEmpty()
        _ui.update {
            it.copy(
                form = EditForm(
                    name = p.name, sku = p.sku.orEmpty(), barcode = bc, unitId = p.unitId,
                    hsn = p.hsnCode.orEmpty(), gstRateBp = p.gstRateBp, taxInclusive = p.taxInclusive,
                    sellPrice = Parsing.paiseToInput(p.sellPricePaise), cost = Parsing.paiseToInput(p.costPaise),
                    minStock = if (p.minStockMilli > 0) Parsing.milliToInput(p.minStockMilli) else "",
                    trackExpiry = p.trackExpiry, categoryId = p.categoryId.orEmpty(),
                ),
            )
        }
    }

    fun update(block: EditForm.() -> EditForm) = _ui.update { it.copy(form = it.form.block(), errors = emptyMap()) }

    fun save() {
        val f = _ui.value.form
        val unit = units.value.firstOrNull { it.id == f.unitId }
        val errors = mutableMapOf<String, FieldError>()

        if (f.name.isBlank()) errors["name"] = FieldError(R.string.err_name_required)
        val sell = Parsing.moneyToPaise(f.sellPrice)
        if (sell == null) errors["sell"] = FieldError(R.string.err_price_invalid)
        val cost = if (f.cost.isBlank()) 0L else Parsing.moneyToPaise(f.cost)
        if (cost == null) errors["cost"] = FieldError(R.string.err_amount_invalid)
        val minStock = if (f.minStock.isBlank()) 0L else Parsing.qtyToMilli(f.minStock, unit?.allowDecimal ?: false)
        if (minStock == null) errors["min"] = FieldError(if (unit?.allowDecimal == false) R.string.err_qty_whole else R.string.err_qty_invalid)
        if (unit == null) errors["unit"] = FieldError(R.string.err_amount_invalid)

        if (errors.isNotEmpty()) { _ui.update { it.copy(errors = errors) }; return }

        val base = original ?: ProductEntity(name = f.name, unitId = f.unitId, sellPricePaise = sell!!)
        val product = base.copy(
            name = f.name.trim(), sku = f.sku.trim().ifEmpty { null }, unitId = f.unitId,
            hsnCode = f.hsn.trim().ifEmpty { null }, gstRateBp = f.gstRateBp, taxInclusive = f.taxInclusive,
            sellPricePaise = sell!!, costPaise = cost!!, minStockMilli = minStock!!,
            trackExpiry = if (features.value.supportsExpiryTracking) f.trackExpiry else original?.trackExpiry ?: false,
            categoryId = f.categoryId.ifEmpty { null },
        )
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            try {
                when (val r = repo.save(product, f.barcode, isNew = original == null)) {
                    is SaveResult.Saved -> _events.send(EditEvent.Saved(r.id))
                    is SaveResult.DuplicateBarcode -> _ui.update {
                        it.copy(saving = false, errors = mapOf("barcode" to FieldError(R.string.err_barcode_used, r.productName)))
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
}
