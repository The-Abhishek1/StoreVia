package com.xcloak.storevia.feature.purchases

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.ProductListRow
import com.xcloak.storevia.core.database.dao.SupplierDao
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.store.StoreFeatureSet
import com.xcloak.storevia.core.store.StoreTypeConfig
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.core.util.PackUnit
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.domain.purchase.PurchaseException
import com.xcloak.storevia.domain.purchase.PurchaseItem
import com.xcloak.storevia.domain.purchase.RecordPurchaseUseCase
import com.xcloak.storevia.feature.stock.FieldError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class PurchaseLineUi(
    val lineId: String = UUID.randomUUID().toString(),
    val productId: String,
    val name: String,
    val unitSymbol: String,
    val allowDecimal: Boolean,
    val trackExpiry: Boolean,
    val serialTracked: Boolean,
    val qtyMilli: Long,
    val unitCostPaise: Long,
    val batchNo: String? = null,
    val expiry: LocalDate? = null,
    val serialNumbersText: String = "",
    val baseUnitSymbol: String = unitSymbol,
    val baseAllowDecimal: Boolean = allowDecimal,
    val packUnitSymbol: String? = null,
    val packSizeMilli: Long? = null,
    val usingPackUnit: Boolean = false,
) {
    val totalPaise: Long get() = Money(unitCostPaise).timesQtyMilli(qtyMilli).paise
    val displayQtyMilli: Long
        get() = if (usingPackUnit) PackUnit(packUnitSymbol!!, packSizeMilli!!).fromBaseQuantity(qtyMilli) else qtyMilli
    fun withUnit(pack: Boolean) = copy(
        unitSymbol = if (pack) packUnitSymbol!! else baseUnitSymbol,
        allowDecimal = if (pack) false else baseAllowDecimal,
        usingPackUnit = pack,
    )
}

data class PurchaseUi(
    val supplier: SupplierEntity? = null,
    val invoiceNo: String = "",
    val date: LocalDate = LocalDate.now(),
    val lines: List<PurchaseLineUi> = emptyList(),
    val query: String = "",
    /** What the supplier's bill says in total. Blank = just add up the lines. */
    val billTotalText: String = "",
    val paidText: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    /** The line whose edit dialog is open (a new line opens straight away). */
    val editingLineId: String? = null,
    val message: FieldError? = null,
    val saving: Boolean = false,
) {
    val linesTotalPaise: Long get() = lines.sumOf { it.totalPaise }
    val totalPaise: Long? get() = if (billTotalText.isBlank()) linesTotalPaise else Parsing.moneyToPaise(billTotalText)
    val paidPaise: Long? get() = if (paidText.isBlank()) 0L else Parsing.moneyToPaise(paidText)
    /** What goes on the supplier's account. */
    val owedPaise: Long get() = ((totalPaise ?: linesTotalPaise) - (paidPaise ?: 0L)).coerceAtLeast(0)
}

sealed interface PurchaseEvent { data class Saved(val purchaseId: String) : PurchaseEvent }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PurchaseEditViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val productDao: ProductDao,
    private val unitDao: UnitDao,
    private val supplierDao: SupplierDao,
    private val recordPurchase: RecordPurchaseUseCase,
    prefs: AppPrefs,
) : ViewModel() {

    private val _ui = MutableStateFlow(PurchaseUi())
    val ui: StateFlow<PurchaseUi> = _ui.asStateFlow()

    val features: StateFlow<StoreFeatureSet> = prefs.settings.map { StoreTypeConfig.of(it.storeType) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StoreTypeConfig.of(prefs.settings.value.storeType))

    private val _events = Channel<PurchaseEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val suppliers: StateFlow<List<SupplierEntity>> =
        supplierDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val results: StateFlow<List<ProductListRow>> = _ui.map { it.query.trim() }.distinctUntilChanged()
        .flatMapLatest { q -> if (q.isEmpty()) flowOf(emptyList<ProductListRow>()) else productDao.listWithStock(q).map { it.take(8) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        handle.get<String>("supplierId")?.let { id -> viewModelScope.launch { supplierDao.getById(id)?.let { s -> _ui.update { it.copy(supplier = s) } } } }
        // Barcode handed back by the scanner screen.
        viewModelScope.launch {
            handle.getStateFlow<String?>("barcode", null).filterNotNull().collect { code ->
                handle["barcode"] = null
                val p = productDao.findByBarcode(code)
                if (p != null) addProduct(p.id) else _ui.update { it.copy(message = FieldError(R.string.err_barcode_not_found, code)) }
            }
        }
    }

    private fun fail(res: Int, arg: String? = null) = _ui.update { it.copy(message = FieldError(res, arg), saving = false) }
    private fun edit(block: PurchaseUi.() -> PurchaseUi) = _ui.update { it.block().copy(message = null) }

    fun setQuery(q: String) = edit { copy(query = q) }
    fun setSupplier(s: SupplierEntity?) = edit { copy(supplier = s) }
    fun setInvoice(v: String) = edit { copy(invoiceNo = v) }
    fun setDate(d: LocalDate) = edit { copy(date = d) }
    fun setBillTotal(v: String) = edit { copy(billTotalText = v) }
    fun setPaid(v: String) = edit { copy(paidText = v) }
    fun setMethod(m: PaymentMethod) = edit { copy(method = m) }
    fun payInFull() = edit { copy(paidText = Parsing.paiseToInput(totalPaise ?: linesTotalPaise)) }
    fun openLine(id: String?) = _ui.update { it.copy(editingLineId = id) }
    fun removeLine(id: String) = edit { copy(lines = lines.filterNot { it.lineId == id }) }

    fun addSupplier(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val s = SupplierEntity(name = name.trim()); supplierDao.insert(s); setSupplier(s)
        }
    }

    fun addProduct(productId: String) = viewModelScope.launch {
        val p = productDao.getById(productId) ?: return@launch
        val unit = unitDao.getById(p.unitId)
        val pack = try {
            PackUnit.fromAttributesJson(p.attributesJson)
        } catch (_: IllegalArgumentException) {
            _ui.update { it.copy(message = FieldError(R.string.err_product_pack_config)) }
            return@launch
        }
        val line = PurchaseLineUi(
            productId = p.id, name = listOfNotNull(p.name, p.variantLabel).joinToString(" · "),
            unitSymbol = unit?.symbol.orEmpty(), allowDecimal = unit?.allowDecimal ?: false,
            trackExpiry = p.trackExpiry && features.value.supportsExpiryTracking,
            serialTracked = p.serialTracked,
            qtyMilli = 1000, unitCostPaise = p.costPaise, baseUnitSymbol = unit?.symbol.orEmpty(),
            baseAllowDecimal = unit?.allowDecimal ?: false, packUnitSymbol = pack?.name, packSizeMilli = pack?.baseUnitsPerPackMilli)
        edit { copy(lines = lines + line, query = "", editingLineId = line.lineId) }
    }

    /** @return a string-resource id for the problem, or null when the line was updated. */
    fun updateLine(
        lineId: String,
        qtyText: String,
        costText: String,
        batchNo: String,
        expiry: LocalDate?,
        usingPackUnit: Boolean,
        serialNumbersText: String = "",
    ): Int? {
        val line = _ui.value.lines.firstOrNull { it.lineId == lineId } ?: return null
        val displayQty = Parsing.qtyToMilli(qtyText, if (usingPackUnit) false else line.baseAllowDecimal)
        if (displayQty == null || displayQty <= 0) return if (!usingPackUnit && line.baseAllowDecimal) R.string.err_qty_invalid else R.string.err_qty_whole
        val qty = if (usingPackUnit) {
            val packSize = line.packSizeMilli ?: return R.string.err_product_pack_config
            try { PackUnit(line.packUnitSymbol!!, packSize).toBaseQuantity(displayQty) }
            catch (_: ArithmeticException) { return R.string.err_qty_invalid }
        } else displayQty
        val serials = serialNumbersText.split(',', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        if (line.serialTracked && (qty % 1_000L != 0L || serials.size.toLong() != qty / 1_000L)) return R.string.err_serial_count
        if (line.serialTracked && serials.distinct().size != serials.size) return R.string.err_serial_duplicate
        if (!line.serialTracked && serials.isNotEmpty()) return R.string.err_serial_not_tracked
        val cost = Parsing.moneyToPaise(costText) ?: return R.string.err_amount_invalid
        if (line.trackExpiry && expiry == null) return R.string.err_expiry_required
        edit {
            copy(lines = lines.map {
                if (it.lineId == lineId) it.copy(
                    qtyMilli = qty,
                    unitCostPaise = cost,
                    unitSymbol = if (usingPackUnit) it.packUnitSymbol!! else it.baseUnitSymbol,
                    allowDecimal = if (usingPackUnit) false else it.baseAllowDecimal,
                    usingPackUnit = usingPackUnit,
                    batchNo = batchNo.trim().ifEmpty { null }.takeIf { features.value.supportsBatchTracking },
                    expiry = expiry.takeIf { features.value.supportsExpiryTracking },
                    serialNumbersText = serialNumbersText,
                ) else it
            })
        }
        return null
    }

    fun save() {
        val s = _ui.value
        if (s.saving) return
        if (s.lines.isEmpty()) { fail(R.string.err_no_lines); return }
        val total = s.totalPaise ?: run { fail(R.string.err_amount_invalid); return }
        val paid = s.paidPaise ?: run { fail(R.string.err_amount_invalid); return }
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            try {
                val id = recordPurchase(
                    items = s.lines.map {
                        PurchaseItem(
                            it.productId, it.qtyMilli, it.unitCostPaise, it.batchNo, it.expiry,
                            it.serialNumbersText.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty),
                        )
                    },
                    supplierId = s.supplier?.id, invoiceNo = s.invoiceNo,
                    purchaseDateMillis = s.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    billTotalPaise = total, paidPaise = paid, payMethod = if (paid > 0) s.method else null,
                )
                _events.send(PurchaseEvent.Saved(id))
            } catch (e: PurchaseException) {
                when (e.reason) {
                    PurchaseException.Reason.PAID_EXCEEDS_TOTAL -> fail(R.string.err_paid_exceeds_purchase)
                    PurchaseException.Reason.SUPPLIER_REQUIRED_FOR_CREDIT -> fail(R.string.err_supplier_for_credit)
                    PurchaseException.Reason.EXPIRY_REQUIRED -> fail(R.string.err_expiry_required_for, e.detail)
                    PurchaseException.Reason.NO_LINES -> fail(R.string.err_no_lines)
                    PurchaseException.Reason.SERIAL_COUNT -> fail(R.string.err_serial_count)
                    PurchaseException.Reason.SERIAL_DUPLICATE -> fail(R.string.err_serial_duplicate)
                    else -> fail(R.string.err_amount_invalid)
                }
            } catch (e: com.xcloak.storevia.core.access.PermissionDeniedException) {
                fail(R.string.err_permission_denied)
            }
        }
    }
}
