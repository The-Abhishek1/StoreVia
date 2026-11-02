package com.xcloak.storevia.feature.settings

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.database.entity.StoreEntity
import com.xcloak.storevia.core.util.GstinValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StoreForm(
    val name: String = "",
    val address: String = "",
    val phone: String = "",
    val gstin: String = "",
    val stateCode: String = "29",
    val invoicePrefix: String = "INV",
    val nextInvoiceNo: String = "1",
    val allowNegativeStock: Boolean = true,
)

data class StoreSettingsUi(
    val loaded: Boolean = false,
    val form: StoreForm = StoreForm(),
    /** Invoice numbers can't go backwards: that would create duplicates. */
    val minNextInvoiceNo: Long = 1,
    val errors: Map<String, Int> = emptyMap(),
)

@HiltViewModel
class StoreSettingsViewModel @Inject constructor(
    private val storeDao: StoreDao,
    private val access: Access,
    private val audit: AuditSink,
) : ViewModel() {
    private val _ui = MutableStateFlow(StoreSettingsUi())
    val ui: StateFlow<StoreSettingsUi> = _ui.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    private var original: StoreEntity? = null

    init {
        viewModelScope.launch {
            val s = storeDao.get() ?: return@launch
            original = s
            _ui.value = StoreSettingsUi(
                loaded = true,
                form = StoreForm(
                    name = if (s.name == DEFAULT_NAME) "" else s.name, address = s.address, phone = s.phone,
                    gstin = s.gstin.orEmpty(), stateCode = s.stateCode, invoicePrefix = s.invoicePrefix,
                    nextInvoiceNo = s.nextInvoiceNo.toString(), allowNegativeStock = s.allowNegativeStock,
                ),
                minNextInvoiceNo = s.nextInvoiceNo,
            )
        }
    }

    fun update(block: StoreForm.() -> StoreForm) = _ui.update { it.copy(form = it.form.block(), errors = emptyMap()) }

    fun save() {
        val f = _ui.value.form
        val base = original ?: return
        if (!access.actor().can(Permission.MANAGE_SETTINGS)) { _ui.update { it.copy(errors = mapOf("name" to R.string.err_permission_denied)) }; return }
        val errors = mutableMapOf<String, Int>()

        if (f.name.isBlank()) errors["name"] = R.string.err_name_required
        val prefix = f.invoicePrefix.trim().uppercase()
        if (prefix.isEmpty() || prefix.length > 8 || !prefix.all { it.isLetterOrDigit() || it == '-' }) errors["prefix"] = R.string.err_prefix_invalid
        val next = f.nextInvoiceNo.trim().toLongOrNull()
        if (next == null || next < _ui.value.minNextInvoiceNo) errors["next"] = R.string.err_next_invoice

        val gstin = f.gstin.takeIf { it.isNotBlank() }?.let(GstinValidator::normalize)
        if (gstin != null) when (GstinValidator.validate(gstin, f.stateCode)) {
            GstinValidator.Result.BAD_FORMAT -> errors["gstin"] = R.string.err_gstin_format
            GstinValidator.Result.STATE_MISMATCH -> errors["gstin"] = R.string.err_gstin_state
            GstinValidator.Result.BAD_CHECKSUM -> errors["gstin"] = R.string.err_gstin_checksum
            GstinValidator.Result.VALID -> Unit
        }
        if (errors.isNotEmpty()) { _ui.update { it.copy(errors = errors) }; return }

        viewModelScope.launch {
            storeDao.upsert(
                base.copy(
                    name = f.name.trim(), address = f.address.trim(), phone = f.phone.trim(), gstin = gstin,
                    stateCode = f.stateCode, invoicePrefix = prefix, nextInvoiceNo = next!!,
                    allowNegativeStock = f.allowNegativeStock, updatedAt = System.currentTimeMillis(), dirty = true,
                )
            )
            val changes = buildList {
                if (base.name != f.name.trim()) add("name")
                if (base.gstin != gstin) add("GSTIN")
                if (base.stateCode != f.stateCode) add("state")
                if (base.invoicePrefix != prefix || base.nextInvoiceNo != next) add("invoice numbering")
                if (base.allowNegativeStock != f.allowNegativeStock) add("selling out-of-stock items")
            }
            if (changes.isNotEmpty()) audit.log(AuditAction.SETTINGS_CHANGED, "Store settings changed: ${changes.joinToString(", ")}")
            _saved.send(Unit)
        }
    }

    companion object { const val DEFAULT_NAME = "My Store" }
}
