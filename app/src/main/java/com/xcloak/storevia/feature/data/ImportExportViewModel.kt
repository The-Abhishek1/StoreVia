package com.xcloak.storevia.feature.data

import com.xcloak.storevia.core.access.*
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.util.Csv
import com.xcloak.storevia.data.export.CsvExporter
import com.xcloak.storevia.data.export.ExportKind
import com.xcloak.storevia.data.export.ExportFormat
import com.xcloak.storevia.data.imports.ImportFileText
import com.xcloak.storevia.data.cloud.CloudAccountRepository
import com.xcloak.storevia.data.cloud.CloudImportExportRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.prefs.StorageModeRouter
import com.xcloak.storevia.domain.imports.*
import com.xcloak.storevia.domain.imports.SalesImportStockMode
import io.github.jan.supabase.auth.status.SessionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

data class PendingImport(
    val parse: ParseResult,
    val preview: ImportPreview,
    val policy: DuplicatePolicy = DuplicatePolicy.SKIP,
    val sourceMode: StorageMode? = null,
)
data class PendingSalesImport(
    val document: SalesImportDocument,
    val validation: SalesImportValidation,
    val stockMode: SalesImportStockMode = SalesImportStockMode.HISTORICAL_ONLY,
    val requestId: String = UUID.randomUUID().toString(),
)

data class DataUi(
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
    val messageArgs: List<Any> = emptyList(),
    val pending: PendingImport? = null,
    val pendingSales: PendingSalesImport? = null,
    val salesSummarySelected: Boolean = false,
    val salesLinesSelected: Boolean = false,
    val salesPaymentsSelected: Boolean = false,
    val proAccessChecked: Boolean = false,
    val proAccessEnabled: Boolean = false,
    val proAccessError: String? = null,
    val statusText: String? = null,
)

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: CsvExporter,
    private val importer: ProductImportUseCase,
    private val salesImporter: SalesImportUseCase,
    private val unitDao: UnitDao,
    private val access: Access,
    private val audit: AuditSink,
    private val cloud: CloudAccountRepository,
    private val prefs: AppPrefs,
    private val cloudImportExport: CloudImportExportRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(DataUi())
    val ui: StateFlow<DataUi> = _ui.asStateFlow()
    val settings = prefs.settings
    private var salesSummaryCsv: String? = null
    private var salesLinesCsv: String? = null
    private var salesPaymentsCsv: String? = null

    init { refreshProAccess() }

    fun dismissMessage() = _ui.update { it.copy(message = null, messageArgs = emptyList(), statusText = null) }
    fun cancelImport() = _ui.update { it.copy(pending = null) }
    fun cancelSalesImport() {
        salesSummaryCsv = null
        salesLinesCsv = null
        salesPaymentsCsv = null
        _ui.update {
            it.copy(pendingSales = null, salesSummarySelected = false, salesLinesSelected = false, salesPaymentsSelected = false)
        }
    }
    fun setPolicy(p: DuplicatePolicy) = _ui.update { s -> s.copy(pending = s.pending?.copy(policy = p)) }
    fun setSalesStockMode(mode: SalesImportStockMode) =
        _ui.update { s -> s.copy(pendingSales = s.pendingSales?.copy(stockMode = mode)) }

    fun refreshProAccess() = viewModelScope.launch { checkProAccess() }

    fun export(kind: ExportKind, format: ExportFormat, uri: Uri) = viewModelScope.launch {
        if (kind != ExportKind.TEMPLATE && !access.actor().can(Permission.EXPORT_DATA)) {
            _ui.update { it.copy(message = R.string.err_permission_denied) }; return@launch
        }
        if (kind != ExportKind.TEMPLATE && !checkProAccess()) return@launch
        _ui.update { it.copy(busy = true) }
        var failure: String? = null
        val ok = try {
            val bytes = exporter.build(kind, format)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }
        } catch (error: Exception) {
            Log.e(TAG, "Could not export ${kind.name.lowercase()}", error)
            failure = error.message
            false
        }
        if (ok && kind != ExportKind.TEMPLATE) audit.log(AuditAction.EXPORT, "Exported ${kind.name.lowercase()} as ${format.extension.uppercase()}")
        _ui.update {
            it.copy(
                busy = false,
                message = if (ok) R.string.export_done else R.string.export_failed,
                statusText = if (ok) null else failure?.let { detail -> "Export failed: $detail" },
            )
        }
    }

    fun readImport(uri: Uri) = viewModelScope.launch {
        if (!access.actor().can(Permission.IMPORT_DATA)) {
            _ui.update { it.copy(message = R.string.err_permission_denied) }; return@launch
        }
        if (!checkProAccess()) return@launch
        _ui.update { it.copy(busy = true) }
        var readFailure: Exception? = null
        val bytes = try {
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }
        } catch (error: Exception) {
            Log.e(TAG, "Could not read the selected product CSV", error)
            readFailure = error
            null
        }
        if (bytes == null) {
            _ui.update {
                it.copy(
                    busy = false,
                    message = R.string.import_read_failed,
                    statusText = "Could not read import file: " +
                        (readFailure?.message ?: "The file is unavailable or larger than 5 MB."),
                )
            }
            return@launch
        }
        try {
            val table = Csv.parse(importText(bytes))
            val sourceMode = prefs.settings.value.storageMode
            val units = StorageModeRouter.routeSuspend(
                sourceMode,
                local = { unitDao.observeAll().first() },
                cloud = { cloudImportExport.units() },
            )
            val parse = ProductCsvParser.parse(table, units)
            val preview = StorageModeRouter.routeSuspend(
                sourceMode,
                local = { importer.preview(parse.rows) },
                cloud = { cloudImportExport.previewProducts(parse.rows) },
            )
            _ui.update { it.copy(busy = false, pending = PendingImport(parse, preview, sourceMode = sourceMode)) }
        } catch (error: Exception) {
            Log.e(TAG, "Could not prepare the selected product CSV", error)
            _ui.update {
                it.copy(
                    busy = false,
                    message = R.string.import_preview_failed,
                    statusText = error.message?.let { detail -> "Could not prepare import: $detail" },
                )
            }
        }
    }

    fun readSalesSummary(uri: Uri) = readSalesCsv(uri, summary = true)
    fun readSalesLines(uri: Uri) = readSalesCsv(uri, summary = false)
    fun readSalesPayments(uri: Uri) = readSalesPaymentsCsv(uri)

    private fun readSalesCsv(uri: Uri, summary: Boolean) = viewModelScope.launch {
        if (!access.actor().can(Permission.IMPORT_DATA)) {
            _ui.update { it.copy(message = R.string.err_permission_denied) }
            return@launch
        }
        if (!checkProAccess()) return@launch
        _ui.update { it.copy(busy = true) }
        var readFailure: Exception? = null
        val bytes = try {
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }
        } catch (error: Exception) {
            Log.e(TAG, "Could not read the selected sales CSV", error)
            readFailure = error
            null
        }
        if (bytes == null) {
            _ui.update {
                it.copy(
                    busy = false,
                    message = R.string.import_read_failed,
                    statusText = "Could not read sales file: " +
                        (readFailure?.message ?: "The file is unavailable or larger than 5 MB."),
                )
            }
            return@launch
        }
        val text = try {
            importText(bytes)
        } catch (error: Exception) {
            Log.e(TAG, "Could not parse the selected sales spreadsheet", error)
            _ui.update {
                it.copy(
                    busy = false,
                    message = R.string.import_preview_failed,
                    statusText = error.message?.let { detail -> "Could not prepare sales import: $detail" },
                )
            }
            return@launch
        }
        if (SalesImportParser.isCombinedFile(text)) {
            salesSummaryCsv = text
            salesLinesCsv = text
        } else if (summary) {
            salesSummaryCsv = text
        } else {
            salesLinesCsv = text
        }
        _ui.update {
            it.copy(
                busy = false,
                salesSummarySelected = salesSummaryCsv != null,
                salesLinesSelected = salesLinesCsv != null,
                salesPaymentsSelected = salesPaymentsCsv != null,
            )
        }
    }

    fun prepareSalesImport() = viewModelScope.launch {
        val summaryCsv = salesSummaryCsv ?: return@launch
        val linesCsv = salesLinesCsv ?: return@launch
        if (!access.actor().can(Permission.IMPORT_DATA)) {
            _ui.update { it.copy(message = R.string.err_permission_denied) }
            return@launch
        }
        if (!checkProAccess()) return@launch
        _ui.update { it.copy(busy = true) }
        try {
            val document = SalesImportParser.parse(summaryCsv, linesCsv, salesPaymentsCsv)
            val validation = salesImporter.validate(document)
            _ui.update {
                it.copy(
                    busy = false,
                    pendingSales = PendingSalesImport(document, validation),
                    salesSummarySelected = false,
                    salesLinesSelected = false,
                    salesPaymentsSelected = false,
                )
            }
            salesSummaryCsv = null
            salesLinesCsv = null
            salesPaymentsCsv = null
        } catch (error: Exception) {
            Log.e(TAG, "Could not prepare the selected sales CSV files", error)
            _ui.update { it.copy(busy = false, message = R.string.sales_import_failed) }
        }
    }

    private fun readSalesPaymentsCsv(uri: Uri) = viewModelScope.launch {
        if (!access.actor().can(Permission.IMPORT_DATA)) {
            _ui.update { it.copy(message = R.string.err_permission_denied) }
            return@launch
        }
        if (!checkProAccess()) return@launch
        _ui.update { it.copy(busy = true) }
        var readFailure: Exception? = null
        val bytes = try {
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }
        } catch (error: Exception) {
            Log.e(TAG, "Could not read the selected sales payments CSV", error)
            readFailure = error
            null
        }
        if (bytes == null) {
            _ui.update {
                it.copy(
                    busy = false,
                    message = R.string.import_read_failed,
                    statusText = "Could not read sales payments file: " +
                        (readFailure?.message ?: "The file is unavailable or larger than 5 MB."),
                )
            }
            return@launch
        }
        salesPaymentsCsv = try {
            importText(bytes)
        } catch (error: Exception) {
            Log.e(TAG, "Could not parse the selected sales payments spreadsheet", error)
            _ui.update {
                it.copy(
                    busy = false,
                    message = R.string.import_preview_failed,
                    statusText = error.message?.let { detail -> "Could not prepare sales payments import: $detail" },
                )
            }
            return@launch
        }
        _ui.update { it.copy(busy = false, salesPaymentsSelected = true) }
    }

    fun confirmSalesImport() {
        val pending = _ui.value.pendingSales ?: return
        if (!pending.validation.valid) return
        viewModelScope.launch {
            if (!access.actor().can(Permission.IMPORT_DATA)) {
                _ui.update { it.copy(pendingSales = null, message = R.string.err_permission_denied) }
                return@launch
            }
            if (!checkProAccess()) {
                _ui.update { it.copy(pendingSales = null) }
                return@launch
            }
            _ui.update { it.copy(busy = true) }
            try {
                val imported = if (prefs.settings.value.storageMode == StorageMode.CLOUD) {
                    require(pending.stockMode == SalesImportStockMode.HISTORICAL_ONLY) {
                        "Cloud sales imports preserve the existing stock quantities."
                    }
                    cloudImportExport.importSales(pending.document, pending.requestId)
                } else {
                    salesImporter.import(pending.document, pending.stockMode).imported
                }
                _ui.update {
                    it.copy(
                        busy = false,
                        pendingSales = null,
                        salesSummarySelected = false,
                        salesLinesSelected = false,
                        salesPaymentsSelected = false,
                        message = R.string.sales_import_result,
                        messageArgs = listOf(imported),
                    )
                }
            } catch (error: SalesImportValidationException) {
                val validation = SalesImportValidation(error.errors)
                _ui.update { it.copy(busy = false, pendingSales = pending.copy(validation = validation)) }
            } catch (_: PermissionDeniedException) {
                _ui.update { it.copy(busy = false, message = R.string.err_permission_denied) }
            } catch (error: Exception) {
                Log.e(TAG, "Could not import sales CSV", error)
                _ui.update {
                    it.copy(
                        busy = false,
                        pendingSales = pending,
                        message = R.string.sales_import_failed,
                        statusText = error.message?.let { detail -> "Sales import failed: $detail" },
                    )
                }
            }
        }
    }

    fun confirmImport() {
        val p = _ui.value.pending ?: return
        viewModelScope.launch {
            if (p.sourceMode != null && p.sourceMode != prefs.settings.value.storageMode) {
                _ui.update {
                    it.copy(
                        pending = p,
                        message = null,
                        statusText = "Storage mode changed after preview. Reopen the import file to validate it against the current store.",
                    )
                }
                return@launch
            }
            if (!access.actor().can(Permission.IMPORT_DATA)) {
                _ui.update { it.copy(pending = null, message = R.string.err_permission_denied) }; return@launch
            }
            if (!checkProAccess()) {
                _ui.update { it.copy(pending = null) }
                return@launch
            }
            _ui.update { it.copy(busy = true, pending = null) }
            try {
                val s = StorageModeRouter.routeSuspend(
                    prefs.settings.value.storageMode,
                    local = { importer.import(p.parse.rows, p.policy) },
                    cloud = { cloudImportExport.importProducts(p.parse.rows, p.policy) },
                )
                _ui.update { it.copy(busy = false, message = R.string.import_result, messageArgs = listOf(s.created, s.updated, s.skipped)) }
            } catch (e: PermissionDeniedException) {
                _ui.update { it.copy(busy = false, message = R.string.err_permission_denied) }
            } catch (error: Exception) {
                Log.e(TAG, "Could not import product CSV", error)
                _ui.update {
                    it.copy(
                        busy = false,
                        pending = p,
                        message = R.string.import_failed,
                        statusText = error.message?.let { detail -> "Import failed: $detail" },
                    )
                }
            }
        }
    }

    private suspend fun checkProAccess(): Boolean {
        if (prefs.settings.value.storageMode == StorageMode.LOCAL) {
            _ui.update {
                it.copy(proAccessChecked = true, proAccessEnabled = true, proAccessError = null)
            }
            return true
        }
        if (prefs.settings.value.storageMode != StorageMode.CLOUD) {
            _ui.update {
                it.copy(
                    proAccessChecked = true,
                    proAccessEnabled = false,
                    proAccessError = "Choose Local or connect a Cloud store before importing or exporting business data.",
                )
            }
            return false
        }
        return try {
            var plan = cloud.planSnapshot()
            if (plan == null && !cloud.hasSignedInSession()) {
                val restored = withTimeoutOrNull(5_000) {
                    cloud.authSessionStatus.first {
                        it is SessionStatus.Authenticated && cloud.hasSignedInSession()
                    }
                }
                if (restored != null) plan = cloud.planSnapshot()
            }
            val enabled = plan?.hasActiveProAccess() == true
            _ui.update { it.copy(proAccessChecked = true, proAccessEnabled = enabled, proAccessError = null) }
            enabled
        } catch (error: Exception) {
            Log.e(TAG, "Could not check Pro access", error)
            _ui.update {
                it.copy(
                    proAccessChecked = true,
                    proAccessEnabled = false,
                    proAccessError = error.message ?: "Could not check Pro access. Sign in and connect your store, then retry.",
                )
            }
            false
        }
    }

    /** Imports are small CSVs; refuse anything over 5 MB. */
    private fun readCapped(input: java.io.InputStream, limit: Int = 5 * 1024 * 1024): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun importText(bytes: ByteArray): String =
        ImportFileText.decode(bytes)

    private companion object {
        const val TAG = "ImportExport"
    }
}
