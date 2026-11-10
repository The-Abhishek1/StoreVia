package com.xcloak.storevia.feature.sales

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.domain.sale.RefundMode
import com.xcloak.storevia.domain.sale.ReturnException
import com.xcloak.storevia.domain.sale.ReturnItem
import com.xcloak.storevia.domain.sale.ReturnItemsUseCase
import com.xcloak.storevia.domain.sale.ReturnPreview
import com.xcloak.storevia.data.cloud.CloudSalesInventoryRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.cloud.CloudSalesInventoryException
import com.xcloak.storevia.feature.stock.FieldError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReturnRow(
    val productId: String,
    val name: String,
    val symbol: String,
    val allowDecimal: Boolean,
    val soldMilli: Long,
    val returnedMilli: Long,
) {
    val returnableMilli: Long get() = soldMilli - returnedMilli
}

data class ReturnUi(
    val loaded: Boolean = false,
    val rows: List<ReturnRow> = emptyList(),
    val qtyText: Map<String, String> = emptyMap(),
    val hasCustomer: Boolean = false,
    val method: PaymentMethod = PaymentMethod.CASH,
    val asCredit: Boolean = false,
    val preview: ReturnPreview? = null,
    val error: FieldError? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class ReturnViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val saleDao: SaleDao,
    private val productDao: ProductDao,
    private val unitDao: UnitDao,
    private val returnItems: ReturnItemsUseCase,
    private val cloudSales: CloudSalesInventoryRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val saleId: String = checkNotNull(handle["id"])
    private val _ui = MutableStateFlow(ReturnUi())
    val ui: StateFlow<ReturnUi> = _ui.asStateFlow()

    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done = _done.receiveAsFlow()

    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                if (prefs.settings.value.storageMode == StorageMode.CLOUD) {
                    val detail = cloudSales.loadReturnDetail(saleId) ?: run {
                        _ui.update { it.copy(loaded = true) }
                        return@launch
                    }
                    _ui.update {
                        it.copy(
                            loaded = true,
                            rows = detail.lines.map { line ->
                                ReturnRow(
                                    line.productId,
                                    line.name,
                                    line.unitSymbol,
                                    line.allowDecimal,
                                    line.soldMilli,
                                    line.returnedMilli,
                                )
                            },
                            hasCustomer = detail.sale.customerId != null,
                        )
                    }
                    return@launch
                }
                val sale = saleDao.getById(saleId) ?: return@launch
                val rows = saleDao.getLines(saleId).groupBy { it.productId }.map { (pid, lines) ->
                    val unit = productDao.getById(pid)?.let { unitDao.getById(it.unitId) }
                    ReturnRow(pid, lines.first().nameSnapshot, unit?.symbol.orEmpty(), unit?.allowDecimal ?: false,
                        lines.sumOf { it.qtyMilli }, lines.sumOf { it.returnedQtyMilli })
                }
                _ui.update { it.copy(loaded = true, rows = rows, hasCustomer = sale.customerId != null) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(loaded = true, error = FieldError(R.string.err_generic, e.message)) }
            }
        }
    }

    fun setQty(productId: String, text: String) {
        _ui.update { it.copy(qtyText = it.qtyText + (productId to text), error = null) }
        refreshPreview()
    }

    fun setMethod(m: PaymentMethod) = _ui.update { it.copy(method = m, asCredit = false) }
    fun setCredit() = _ui.update { it.copy(asCredit = true) }

    /** Parses what the user typed. Returns null when something is invalid. */
    private fun items(s: ReturnUi): List<ReturnItem>? {
        val out = mutableListOf<ReturnItem>()
        for (row in s.rows) {
            val t = s.qtyText[row.productId].orEmpty()
            if (t.isBlank()) continue
            val q = Parsing.qtyToMilli(t, row.allowDecimal) ?: return null
            if (q > 0) out += ReturnItem(row.productId, q)
        }
        return out
    }

    private fun refreshPreview() {
        previewJob?.cancel()
        val s = _ui.value
        val list = items(s)
        if (list == null) { _ui.update { it.copy(preview = null, error = FieldError(R.string.err_qty_invalid)) }; return }
        if (list.isEmpty()) { _ui.update { it.copy(preview = null) }; return }
        previewJob = viewModelScope.launch {
            try {
                val pv = returnItems.preview(saleId, list)
                _ui.update { it.copy(preview = pv) }
            } catch (e: ReturnException) {
                _ui.update { it.copy(preview = null, error = FieldError(errorRes(e))) }
            } catch (e: CloudSalesInventoryException) {
                _ui.update { it.copy(preview = null, error = FieldError(R.string.err_generic, e.message)) }
            }
        }
    }

    private fun errorRes(e: ReturnException) = when (e.reason) {
        ReturnException.Reason.EXCEEDS_RETURNABLE -> R.string.err_exceeds_returnable
        ReturnException.Reason.CREDIT_NEEDS_CUSTOMER -> R.string.err_credit_needs_customer
        ReturnException.Reason.SERIAL_STATE -> R.string.err_serial_state
        else -> R.string.err_nothing_to_return
    }

    fun confirm() {
        val s = _ui.value
        val list = items(s)
        if (s.saving) return
        if (list.isNullOrEmpty()) { _ui.update { it.copy(error = FieldError(R.string.err_nothing_to_return)) }; return }
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            try {
                returnItems(saleId, list, if (s.asCredit) RefundMode.KhataCredit else RefundMode.Money(s.method))
                _done.send(Unit)
            } catch (e: ReturnException) {
                _ui.update { it.copy(saving = false, error = FieldError(errorRes(e))) }
            } catch (e: PermissionDeniedException) {
                _ui.update { it.copy(saving = false, error = FieldError(R.string.err_permission_denied)) }
            } catch (e: CloudSalesInventoryException) {
                _ui.update { it.copy(saving = false, error = FieldError(R.string.err_generic, e.message)) }
            }
        }
    }
}
