package com.xcloak.storevia.feature.data

import com.xcloak.storevia.core.access.*
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.util.Csv
import com.xcloak.storevia.data.export.CsvExporter
import com.xcloak.storevia.data.export.ExportKind
import com.xcloak.storevia.domain.imports.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PendingImport(
    val parse: ParseResult,
    val preview: ImportPreview,
    val policy: DuplicatePolicy = DuplicatePolicy.SKIP,
)

data class DataUi(
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
    val messageArgs: List<Any> = emptyList(),
    val pending: PendingImport? = null,
)

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: CsvExporter,
    private val importer: ProductImportUseCase,
    private val unitDao: UnitDao,
    private val access: Access,
    private val audit: AuditSink,
) : ViewModel() {

    private val _ui = MutableStateFlow(DataUi())
    val ui: StateFlow<DataUi> = _ui.asStateFlow()

    fun dismissMessage() = _ui.update { it.copy(message = null, messageArgs = emptyList()) }
    fun cancelImport() = _ui.update { it.copy(pending = null) }
    fun setPolicy(p: DuplicatePolicy) = _ui.update { s -> s.copy(pending = s.pending?.copy(policy = p)) }

    fun export(kind: ExportKind, uri: Uri) = viewModelScope.launch {
        if (kind != ExportKind.TEMPLATE && !access.actor().can(Permission.EXPORT_DATA)) {
            _ui.update { it.copy(message = R.string.err_permission_denied) }; return@launch
        }
        _ui.update { it.copy(busy = true) }
        val ok = try {
            val text = exporter.build(kind)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null }
        } catch (_: Exception) { false }
        if (ok && kind != ExportKind.TEMPLATE) audit.log(AuditAction.EXPORT, "Exported ${kind.name.lowercase()} to CSV")
        _ui.update { it.copy(busy = false, message = if (ok) R.string.export_done else R.string.export_failed) }
    }

    fun readImport(uri: Uri) = viewModelScope.launch {
        _ui.update { it.copy(busy = true) }
        val bytes = try {
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }
        } catch (_: Exception) { null }
        if (bytes == null) { _ui.update { it.copy(busy = false, message = R.string.import_too_big) }; return@launch }

        val table = Csv.parse(bytes.toString(Charsets.UTF_8))
        val parse = ProductCsvParser.parse(table, unitDao.observeAll().first())
        val preview = importer.preview(parse.rows)
        _ui.update { it.copy(busy = false, pending = PendingImport(parse, preview)) }
    }

    fun confirmImport() {
        val p = _ui.value.pending ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, pending = null) }
            try {
                val s = importer.import(p.parse.rows, p.policy)
                _ui.update { it.copy(busy = false, message = R.string.import_result, messageArgs = listOf(s.created, s.updated, s.skipped)) }
            } catch (e: PermissionDeniedException) {
                _ui.update { it.copy(busy = false, message = R.string.err_permission_denied) }
            }
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
}
