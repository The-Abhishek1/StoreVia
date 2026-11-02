package com.xcloak.storevia.feature.sales

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.database.entity.PaymentEntity
import com.xcloak.storevia.core.database.entity.SaleEntity
import com.xcloak.storevia.core.database.entity.SaleLineEntity
import com.xcloak.storevia.core.database.entity.StoreEntity
import com.xcloak.storevia.domain.sale.CancelSaleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReceiptUi(
    val store: StoreEntity,
    val sale: SaleEntity,
    val lines: List<SaleLineEntity>,
    val payments: List<PaymentEntity>,
    val customerName: String?,
) {
    val items get() = ReceiptText.mergeLines(lines)
    val canCancel: Boolean get() =
        sale.status == com.xcloak.storevia.core.database.entity.SaleStatus.COMPLETED && sale.returnedPaise == 0L
    val canReturn: Boolean get() =
        sale.status != com.xcloak.storevia.core.database.entity.SaleStatus.CANCELLED && lines.any { it.returnedQtyMilli < it.qtyMilli }
    val shareText: String get() = ReceiptText.format(store, sale, lines, payments, customerName)
}

@HiltViewModel
class SaleDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    saleDao: SaleDao,
    storeDao: StoreDao,
    customerDao: CustomerDao,
    private val cancelSale: CancelSaleUseCase,
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])

    val ui: StateFlow<ReceiptUi?> = combine(
        saleDao.observeById(id), saleDao.observeLines(id), saleDao.observePayments(id), storeDao.observe(),
    ) { sale, lines, payments, store ->
        if (sale == null || store == null) null
        else ReceiptUi(store, sale, lines, payments, sale.customerId?.let { customerDao.getById(it)?.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun cancel(reason: String?) {
        viewModelScope.launch { runCatching { cancelSale(id, reason) } }
    }
}
