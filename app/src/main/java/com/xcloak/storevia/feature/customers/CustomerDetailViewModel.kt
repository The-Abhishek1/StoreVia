package com.xcloak.storevia.feature.customers

import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.access.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.database.entity.CustomerLedgerEntity
import com.xcloak.storevia.core.database.entity.LedgerType
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.domain.customer.KhataException
import com.xcloak.storevia.domain.customer.ReceivePaymentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class LedgerRow(val entry: CustomerLedgerEntity, val runningPaise: Long)

data class CustomerDetailUi(
    val customer: CustomerEntity,
    val balancePaise: Long,
    /** Newest first. */
    val ledger: List<LedgerRow>,
    val storeName: String,
) {
    val reminderText: String get() = KhataText.reminder(storeName, customer.name, balancePaise)
}

data class CustomerDetailState(val loaded: Boolean = false, val detail: CustomerDetailUi? = null)

@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val customerDao: CustomerDao,
    storeDao: StoreDao,
    private val receivePayment: ReceivePaymentUseCase,
    private val access: Access,
    private val audit: AuditSink,
) : ViewModel() {

    private val id: String = checkNotNull(handle["id"])

    val ui: StateFlow<CustomerDetailState> = combine(
        customerDao.observeById(id), customerDao.observeLedger(id), storeDao.observe(),
    ) { customer, ledger, store ->
        if (customer == null) CustomerDetailState(loaded = true) else {
            var running = 0L
            val rows = ledger.map { e -> running += e.amountPaise; LedgerRow(e, running) }
            CustomerDetailState(
                loaded = true,
                detail = CustomerDetailUi(customer, running, rows.asReversed(), store?.name.orEmpty()),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomerDetailState())

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
        }
    }

    suspend fun addOldBalance(amountText: String): Int? {
        val amount = Parsing.moneyToPaise(amountText) ?: return R.string.err_amount_invalid
        if (amount <= 0) return R.string.err_amount_positive
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS) ||
            !access.actor().can(Permission.EDIT_CREDIT_LIMIT)
        ) return R.string.err_permission_denied
        val customer = customerDao.getById(id) ?: return R.string.customer_unavailable
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS) ||
            !access.actor().can(Permission.EDIT_CREDIT_LIMIT)
        ) return R.string.err_permission_denied
        customerDao.insertLedger(
            CustomerLedgerEntity(customerId = id, type = LedgerType.ADJUSTMENT, amountPaise = amount, refType = "MANUAL")
        )
        audit.log(AuditAction.OLD_BALANCE_ADDED, "Old balance ${Money(amount).format()} added for ${customer.name}", "CUSTOMER", id)
        return null
    }

    /** @return false if the credit limit text is invalid. */
    suspend fun update(name: String, phone: String, limitText: String): Boolean {
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS)) return false
        val current = ui.value.detail?.customer ?: return false
        val limit = if (limitText.isBlank()) null else (Parsing.moneyToPaise(limitText) ?: return false)
        val fresh = customerDao.getById(current.id) ?: return false
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS)) return false
        val limitChanged = limit != fresh.creditLimitPaise
        if (limitChanged && !access.actor().can(Permission.EDIT_CREDIT_LIMIT)) return false
        if (limitChanged) audit.log(AuditAction.CREDIT_LIMIT_CHANGED,
            "${fresh.name}: credit limit ${fresh.creditLimitPaise?.let { Money(it).format() } ?: "none"} -> ${limit?.let { Money(it).format() } ?: "none"}", "CUSTOMER", fresh.id)
        customerDao.update(
            fresh.copy(
                name = name.trim(), phone = phone.trim().ifEmpty { null }, creditLimitPaise = limit,
                updatedAt = System.currentTimeMillis(), dirty = true,
            )
        )
        return true
    }
}
