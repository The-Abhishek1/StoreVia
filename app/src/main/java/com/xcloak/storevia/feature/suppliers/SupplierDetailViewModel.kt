package com.xcloak.storevia.feature.suppliers

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.SupplierDao
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.domain.purchase.PaySupplierUseCase
import com.xcloak.storevia.domain.purchase.SupplierPaymentException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class SupplierLedgerRow(val entry: SupplierLedgerEntity, val runningPaise: Long)

data class SupplierDetailUi(
    val supplier: SupplierEntity,
    val balancePaise: Long,
    /** Newest first. */
    val ledger: List<SupplierLedgerRow>,
)

data class SupplierDetailState(val loaded: Boolean = false, val detail: SupplierDetailUi? = null)

@HiltViewModel
class SupplierDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val dao: SupplierDao,
    private val paySupplier: PaySupplierUseCase,
    private val access: Access,
    private val audit: AuditSink,
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])

    val ui: StateFlow<SupplierDetailState> = combine(dao.observeById(id), dao.observeLedger(id)) { supplier, ledger ->
        if (supplier == null) SupplierDetailState(loaded = true) else {
            var running = 0L
            val rows = ledger.map { e -> running += e.amountPaise; SupplierLedgerRow(e, running) }
            SupplierDetailState(loaded = true, detail = SupplierDetailUi(supplier, running, rows.asReversed()))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SupplierDetailState())

    /** @return a string-resource id for the problem, or null on success. */
    suspend fun pay(amountText: String, method: PaymentMethod): Int? {
        val amount = Parsing.moneyToPaise(amountText) ?: return R.string.err_amount_invalid
        if (amount <= 0) return R.string.err_amount_positive
        return try { paySupplier(id, amount, method); null }
        catch (e: SupplierPaymentException) {
            when (e.reason) {
                SupplierPaymentException.Reason.EXCEEDS_BALANCE -> R.string.err_exceeds_owed
                SupplierPaymentException.Reason.INVALID_AMOUNT -> R.string.err_amount_positive
                SupplierPaymentException.Reason.SUPPLIER_NOT_FOUND -> R.string.supplier_unavailable
            }
        } catch (e: PermissionDeniedException) {
            R.string.err_permission_denied
        }
    }

    /** An amount you already owed this supplier before using Storevia. */
    suspend fun addOldBalance(amountText: String): Int? {
        val amount = Parsing.moneyToPaise(amountText) ?: return R.string.err_amount_invalid
        if (amount <= 0) return R.string.err_amount_positive
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return R.string.err_permission_denied
        val supplier = dao.getById(id) ?: return R.string.supplier_unavailable
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return R.string.err_permission_denied
        dao.insertLedger(SupplierLedgerEntity(supplierId = id, type = SupplierLedgerType.ADJUSTMENT, amountPaise = amount, refType = "MANUAL"))
        audit.log(AuditAction.OLD_BALANCE_ADDED, "Old balance ${com.xcloak.storevia.core.money.Money(amount).format()} owed to ${supplier.name}", "SUPPLIER", id)
        return null
    }

    suspend fun update(name: String, phone: String, gstin: String): Boolean {
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return false
        val current = ui.value.detail?.supplier ?: return false
        if (name.isBlank() || !gstinOk(gstin)) return false
        val fresh = dao.getById(current.id) ?: return false
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return false
        dao.update(fresh.copy(name = name.trim(), phone = phone.trim().ifEmpty { null }, gstin = gstin.trim().uppercase().ifEmpty { null },
            updatedAt = System.currentTimeMillis(), dirty = true))
        return true
    }
}
