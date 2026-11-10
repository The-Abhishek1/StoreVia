package com.xcloak.storevia.feature.customers

import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.access.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.database.entity.CustomerLedgerEntity
import com.xcloak.storevia.core.database.entity.LedgerType
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SaleEntity
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.domain.customer.KhataException
import com.xcloak.storevia.domain.customer.ReceivePaymentUseCase
import com.xcloak.storevia.data.cloud.CloudPartyRepository
import com.xcloak.storevia.domain.customer.CloudCustomerPaymentException
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.prefs.StorageModeRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class LedgerRow(val entry: CustomerLedgerEntity, val runningPaise: Long)

data class CustomerDetailUi(
    val customer: CustomerEntity,
    val balancePaise: Long,
    /** Newest first. */
    val ledger: List<LedgerRow>,
    /** Newest first; independent from khata ledger entries. */
    val sales: List<SaleEntity>,
    val storeName: String,
    val cloudLimited: Boolean = false,
) {
    val reminderText: String get() = KhataText.reminder(storeName, customer.name, balancePaise)
}

data class CustomerDetailState(val loaded: Boolean = false, val detail: CustomerDetailUi? = null)

@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val customerDao: CustomerDao,
    saleDao: SaleDao,
    storeDao: StoreDao,
    private val receivePayment: ReceivePaymentUseCase,
    private val access: Access,
    private val audit: AuditSink,
    private val cloud: CloudPartyRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val id: String = checkNotNull(handle["id"])

    val ui: StateFlow<CustomerDetailState> = StorageModeRouter.observe(
        prefs.settings.map { it.storageMode },
        local = {
            combine(customerDao.observeById(id), customerDao.observeLedger(id), saleDao.observeCustomerSales(id), storeDao.observe()) {
                    customer, ledger, sales, store ->
                if (customer == null) CustomerDetailState(loaded = true) else {
                    var running = 0L
                    val rows = ledger.map { entry -> running += entry.amountPaise; LedgerRow(entry, running) }
                    CustomerDetailState(
                        loaded = true,
                        detail = CustomerDetailUi(customer, running, rows.asReversed(), sales, store?.name.orEmpty()),
                    )
                }
            }.onStart { emit(CustomerDetailState()) }
        },
        cloud = {
            cloud.observeCustomer(id).map { (customer, ledger) ->
                if (customer == null) CustomerDetailState(loaded = true) else {
                    var running = 0L
                    val rows = ledger.map { entry -> running += entry.amountPaise; LedgerRow(entry, running) }
                    CustomerDetailState(
                        loaded = true,
                        detail = CustomerDetailUi(customer, running, rows.asReversed(), emptyList(), "", cloudLimited = true),
                    )
                }
            }.onStart { emit(CustomerDetailState()) }
        },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomerDetailState())

    /** @return a string-resource id for the problem, or null on success. */
    suspend fun receive(amountText: String, method: PaymentMethod): Int? {
        val amount = Parsing.moneyToPaise(amountText) ?: return R.string.err_amount_invalid
        if (amount <= 0) return R.string.err_amount_positive
        return try {
            receivePayment(id, amount, method); null
        } catch (e: KhataException) {
            when (e.reason) {
                KhataException.Reason.EXCEEDS_BALANCE -> R.string.err_exceeds_balance
                KhataException.Reason.INVALID_AMOUNT -> R.string.err_amount_positive
                KhataException.Reason.CUSTOMER_NOT_FOUND -> R.string.customer_unavailable
            }
        } catch (e: PermissionDeniedException) {
            R.string.err_permission_denied
        } catch (e: CloudCustomerPaymentException) {
            R.string.err_generic
        }
    }

    suspend fun addOldBalance(amountText: String): Int? {
        val amount = Parsing.moneyToPaise(amountText) ?: return R.string.err_amount_invalid
        if (amount <= 0) return R.string.err_amount_positive
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS) ||
            !access.actor().can(Permission.EDIT_CREDIT_LIMIT)
        ) return R.string.err_permission_denied
        val customer = StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { customerDao.getById(id) },
            cloud = { cloud.getCustomer(id) },
        ) ?: return R.string.customer_unavailable
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS) ||
            !access.actor().can(Permission.EDIT_CREDIT_LIMIT)
        ) return R.string.err_permission_denied
        val entry = CustomerLedgerEntity(customerId = id, type = LedgerType.ADJUSTMENT, amountPaise = amount, refType = "MANUAL")
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = {
                customerDao.insertLedger(entry)
                audit.log(AuditAction.OLD_BALANCE_ADDED, "Old balance ${Money(amount).format()} added for ${customer.name}", "CUSTOMER", id)
            },
            cloud = { cloud.addCustomerLedger(entry) },
        )
        return null
    }

    /** @return false if the credit limit text is invalid. */
    suspend fun update(name: String, phone: String, limitText: String): Boolean {
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS)) return false
        val current = ui.value.detail?.customer ?: return false
        val limit = if (limitText.isBlank()) null else (Parsing.moneyToPaise(limitText) ?: return false)
        val fresh = StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { customerDao.getById(current.id) },
            cloud = { cloud.getCustomer(current.id) },
        ) ?: return false
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS)) return false
        val limitChanged = limit != fresh.creditLimitPaise
        if (limitChanged && !access.actor().can(Permission.EDIT_CREDIT_LIMIT)) return false
        if (limitChanged && prefs.settings.value.storageMode != StorageMode.CLOUD) audit.log(AuditAction.CREDIT_LIMIT_CHANGED,
            "${fresh.name}: credit limit ${fresh.creditLimitPaise?.let { Money(it).format() } ?: "none"} -> ${limit?.let { Money(it).format() } ?: "none"}", "CUSTOMER", fresh.id)
        val updated = fresh.copy(
                name = name.trim(), phone = phone.trim().ifEmpty { null }, creditLimitPaise = limit,
                updatedAt = System.currentTimeMillis(), dirty = true,
            )
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { customerDao.update(updated) },
            cloud = { cloud.updateCustomer(updated) },
        )
        return true
    }
}
