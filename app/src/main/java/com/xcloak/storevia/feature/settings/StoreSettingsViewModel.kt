package com.xcloak.storevia.feature.settings

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.database.entity.StoreEntity
import com.xcloak.storevia.core.util.GstinValidator
import com.xcloak.storevia.data.prefs.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StoreForm(
    val name: String = "",
    val ownerName: String = "",
    val address: String = "",
    val city: String = "",
    val phone: String = "",
    val gstin: String = "",
    val stateCode: String = "29",
    val invoicePrefix: String = "INV",
    val nextInvoiceNo: String = "1",
    val deviceSeries: String = "",
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
    private val appPrefs: AppPrefs,
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
                    name = if (s.name == DEFAULT_NAME) "" else s.name, ownerName = s.ownerName,
                    address = s.address, city = s.city, phone = s.phone,
                    gstin = s.gstin.orEmpty(), stateCode = s.stateCode, invoicePrefix = s.invoicePrefix,
                    nextInvoiceNo = s.nextInvoiceNo.toString(),
                    deviceSeries = appPrefs.settings.value.deviceSeries,
                    allowNegativeStock = s.allowNegativeStock,
                ),
                minNextInvoiceNo = s.nextInvoiceNo,
            )
        }
    }

    fun update(block: StoreForm.() -> StoreForm) = _ui.update { it.copy(form = it.form.block(), errors = emptyMap()) }

    fun clearCloudRegistrationDetails() = _ui.update {
        it.copy(
            form = it.form.copy(
                name = "",
                ownerName = "",
                address = "",
                city = "",
                phone = "",
                gstin = "",
                stateCode = "",
            ),
            errors = emptyMap(),
        )
    }

    fun save() = save(requireRegistrationDetails = false)

    fun save(requireRegistrationDetails: Boolean, setupFlow: Boolean = false) {
        val f = _ui.value.form
        val base = original ?: return
        if (!setupFlow && !access.actor().can(Permission.MANAGE_SETTINGS)) { _ui.update { it.copy(errors = mapOf("name" to R.string.err_permission_denied)) }; return }
        val errors = mutableMapOf<String, Int>()

        if (f.name.isBlank()) errors["name"] = R.string.err_store_name_required
        if (requireRegistrationDetails) {
            if (f.ownerName.isBlank()) errors["ownerName"] = R.string.err_required
            if (f.address.isBlank()) errors["address"] = R.string.err_required
            if (f.city.isBlank()) errors["city"] = R.string.err_required
            if (f.phone.isBlank()) errors["phone"] = R.string.err_phone_invalid
            if (f.stateCode.isBlank()) errors["stateCode"] = R.string.err_required
        }
        if (f.phone.isNotBlank() && !f.phone.trim().matches(Regex("^\\+?[0-9]{10,15}$"))) {
            errors["phone"] = R.string.err_phone_invalid
        }
        val prefix = f.invoicePrefix.trim().uppercase()
        if (prefix.isEmpty() || prefix.length > 8 || !prefix.all { it.isLetterOrDigit() || it == '-' }) errors["prefix"] = R.string.err_prefix_invalid
        val deviceSeries = f.deviceSeries.trim().uppercase()
        if (deviceSeries.length > 6 || deviceSeries.any { it !in 'A'..'Z' && it !in '0'..'9' }) {
            errors["deviceSeries"] = R.string.err_device_series_invalid
        }
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
        val nextInvoiceNumber = requireNotNull(next)
        val previousDeviceSeries = appPrefs.settings.value.deviceSeries

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updated = storeDao.updateSettings(
                id = base.id,
                name = f.name.trim(),
                ownerName = f.ownerName.trim(),
                address = f.address.trim(),
                city = f.city.trim(),
                phone = f.phone.trim(),
                gstin = gstin,
                stateCode = f.stateCode,
                invoicePrefix = prefix,
                nextInvoiceNo = nextInvoiceNumber,
                allowNegativeStock = f.allowNegativeStock,
                updatedAt = now,
            )
            if (updated == 0) {
                val latest = storeDao.get()
                if (latest != null) {
                    _ui.update {
                        it.copy(
                            minNextInvoiceNo = latest.nextInvoiceNo,
                            errors = mapOf("next" to R.string.err_next_invoice),
                        )
                    }
                } else {
                    _ui.update { it.copy(errors = mapOf("name" to R.string.err_store_unavailable)) }
                }
                return@launch
            }
            val changes = buildList {
                if (base.name != f.name.trim()) add("name")
                if (base.ownerName != f.ownerName.trim() || base.address != f.address.trim() ||
                    base.city != f.city.trim() || base.phone != f.phone.trim()
                ) add("business details")
                if (base.gstin != gstin) add("GSTIN")
                if (base.stateCode != f.stateCode) add("state")
                if (base.invoicePrefix != prefix || base.nextInvoiceNo != nextInvoiceNumber) add("invoice numbering")
                if (previousDeviceSeries != deviceSeries) add("device invoice series")
                if (base.allowNegativeStock != f.allowNegativeStock) add("selling out-of-stock items")
            }
            if (changes.isNotEmpty()) audit.log(AuditAction.SETTINGS_CHANGED, "Store settings changed: ${changes.joinToString(", ")}")
            appPrefs.update { copy(deviceSeries = deviceSeries) }
            original = storeDao.get() ?: base.copy(
                name = f.name.trim(), ownerName = f.ownerName.trim(), address = f.address.trim(),
                city = f.city.trim(), phone = f.phone.trim(), gstin = gstin,
                stateCode = f.stateCode, invoicePrefix = prefix, nextInvoiceNo = nextInvoiceNumber,
                allowNegativeStock = f.allowNegativeStock, updatedAt = now, dirty = true,
            )
            _saved.send(Unit)
        }
    }

    companion object { const val DEFAULT_NAME = "My Store" }
}
