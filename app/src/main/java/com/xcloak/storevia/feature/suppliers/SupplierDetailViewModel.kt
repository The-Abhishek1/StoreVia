package com.xcloak.storevia.feature.suppliers

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.PurchaseDao
import com.xcloak.storevia.core.database.dao.PurchasePayableRow
import com.xcloak.storevia.core.database.dao.SupplierDao
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.domain.purchase.PaySupplierUseCase
import com.xcloak.storevia.domain.purchase.SupplierPaymentException
import com.xcloak.storevia.data.cloud.CloudPartyRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.prefs.StorageModeRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class SupplierLedgerRow(val entry: SupplierLedgerEntity, val runningPaise: Long)

data class SupplierDetailUi(
    val supplier: SupplierEntity,
    val balancePaise: Long,
    /** Newest first. */
    val ledger: List<SupplierLedgerRow>,
    val unpaidPurchases: List<PurchasePayableRow>,
    val unallocatedBalancePaise: Long,
    val cloudLimited: Boolean = false,
)

data class SupplierDetailState(val loaded: Boolean = false, val detail: SupplierDetailUi? = null)

@HiltViewModel
class SupplierDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val dao: SupplierDao,
    private val purchaseDao: PurchaseDao,
    private val paySupplier: PaySupplierUseCase,
    private val access: Access,
    private val audit: AuditSink,
    private val cloud: CloudPartyRepository,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])

    val ui: StateFlow<SupplierDetailState> = StorageModeRouter.observe(
        prefs.settings.map { it.storageMode },
        local = {
            combine(dao.observeById(id), dao.observeLedger(id), purchaseDao.observeUnpaidForSupplier(id)) { supplier, ledger, unpaidPurchases ->
                if (supplier == null) SupplierDetailState(loaded = true) else {
                    var running = 0L
                    val rows = ledger.map { entry -> running += entry.amountPaise; SupplierLedgerRow(entry, running) }
                    val allocatedToPurchases = ledger.filter { it.refType == "PURCHASE" && it.refId != null }.sumOf { it.amountPaise }
                    SupplierDetailState(
                        loaded = true,
                        detail = SupplierDetailUi(supplier, running, rows.asReversed(), unpaidPurchases, running - allocatedToPurchases),
                    )
                }
            }.onStart { emit(SupplierDetailState()) }
        },
        cloud = {
            cloud.observeSupplier(id).map { (supplier, ledger) ->
                if (supplier == null) SupplierDetailState(loaded = true) else {
                    var running = 0L
                    val rows = ledger.map { entry -> running += entry.amountPaise; SupplierLedgerRow(entry, running) }
                    val allocatedToPurchases = ledger.filter { it.refType == "PURCHASE" && it.refId != null }.sumOf { it.amountPaise }
                    SupplierDetailState(
                        loaded = true,
                        detail = SupplierDetailUi(supplier, running, rows.asReversed(), emptyList(), running - allocatedToPurchases, cloudLimited = true),
                    )
                }
            }.onStart { emit(SupplierDetailState()) }
        },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SupplierDetailState())

    /** @return a string-resource id for the problem, or null on success. */
    suspend fun pay(amountText: String, method: PaymentMethod, purchaseId: String?): Int? {
        if (prefs.settings.value.storageMode == StorageMode.CLOUD) return R.string.purchase_unavailable
        val amount = Parsing.moneyToPaise(amountText) ?: return R.string.err_amount_invalid
        if (amount <= 0) return R.string.err_amount_positive
        return try { paySupplier(id, amount, method, purchaseId = purchaseId); null }
        catch (e: SupplierPaymentException) {
            when (e.reason) {
                SupplierPaymentException.Reason.EXCEEDS_BALANCE -> R.string.err_exceeds_owed
                SupplierPaymentException.Reason.INVALID_AMOUNT -> R.string.err_amount_positive
                SupplierPaymentException.Reason.SUPPLIER_NOT_FOUND -> R.string.supplier_unavailable
                SupplierPaymentException.Reason.PURCHASE_NOT_FOUND,
                SupplierPaymentException.Reason.PURCHASE_SUPPLIER_MISMATCH -> R.string.purchase_unavailable
                SupplierPaymentException.Reason.EXCEEDS_PURCHASE_BALANCE -> R.string.err_exceeds_purchase_balance
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
        val supplier = StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { dao.getById(id) },
            cloud = { cloud.getSupplier(id) },
        ) ?: return R.string.supplier_unavailable
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return R.string.err_permission_denied
        val entry = SupplierLedgerEntity(supplierId = id, type = SupplierLedgerType.ADJUSTMENT, amountPaise = amount, refType = "MANUAL")
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = {
                dao.insertLedger(entry)
                audit.log(AuditAction.OLD_BALANCE_ADDED, "Old balance ${com.xcloak.storevia.core.money.Money(amount).format()} owed to ${supplier.name}", "SUPPLIER", id)
            },
            cloud = { cloud.addSupplierLedger(entry) },
        )
        return null
    }

    suspend fun update(name: String, phone: String, gstin: String): Boolean {
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return false
        val current = ui.value.detail?.supplier ?: return false
        if (name.isBlank() || !gstinOk(gstin)) return false
        val fresh = StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { dao.getById(current.id) },
            cloud = { cloud.getSupplier(current.id) },
        ) ?: return false
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return false
        val updated = fresh.copy(name = name.trim(), phone = phone.trim().ifEmpty { null }, gstin = gstin.trim().uppercase().ifEmpty { null },
            updatedAt = System.currentTimeMillis(), dirty = true)
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { dao.update(updated) },
            cloud = { cloud.updateSupplier(updated) },
        )
        return true
    }
}
