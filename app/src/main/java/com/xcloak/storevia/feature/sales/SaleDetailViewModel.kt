package com.xcloak.storevia.feature.sales

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.SerialNumberDao
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.database.entity.PaymentEntity
import com.xcloak.storevia.core.database.entity.SaleEntity
import com.xcloak.storevia.core.database.entity.SaleLineEntity
import com.xcloak.storevia.core.database.entity.StoreEntity
import com.xcloak.storevia.core.database.entity.SerialNumberEntity
import com.xcloak.storevia.domain.sale.CancelSaleUseCase
import com.xcloak.storevia.data.cloud.CloudSalesInventoryRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageModeRouter
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
    val serialNumbers: List<SerialNumberEntity>,
) {
    val items get() = ReceiptText.mergeLines(lines)
    val canCancel: Boolean get() =
        sale.status == com.xcloak.storevia.core.database.entity.SaleStatus.COMPLETED && sale.returnedPaise == 0L
    val canReturn: Boolean get() =
        sale.status != com.xcloak.storevia.core.database.entity.SaleStatus.CANCELLED && lines.any { it.returnedQtyMilli < it.qtyMilli }
    val shareText: String get() = ReceiptText.format(store, sale, lines, payments, customerName) +
        if (serialNumbers.isEmpty()) "" else "\nSerial numbers: ${serialNumbers.joinToString { it.serialNumber }}"
}

@HiltViewModel
class SaleDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    saleDao: SaleDao,
    serialNumberDao: SerialNumberDao,
    storeDao: StoreDao,
    customerDao: CustomerDao,
    private val cancelSale: CancelSaleUseCase,
    cloudSales: CloudSalesInventoryRepository,
    prefs: AppPrefs,
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])

    val ui: StateFlow<ReceiptUi?> = StorageModeRouter.observe(
        prefs.settings.map { it.storageMode },
        local = {
            combine(
                saleDao.observeById(id), saleDao.observeLines(id), saleDao.observePayments(id), storeDao.observe(),
                serialNumberDao.observeForSale(id),
            ) { sale, lines, payments, store, serialNumbers ->
                if (sale == null || store == null) null
                else ReceiptUi(
                    store, sale, lines, payments, sale.customerId?.let { customerDao.getById(it)?.name },
                    serialNumbers,
                )
            }
        },
        cloud = {
            cloudSales.observeReceipt(id).map { snapshot ->
                snapshot?.let {
                    ReceiptUi(
                        it.store, it.sale, it.lines, it.payments, it.customerName, it.serialNumbers,
                    )
                }
            }
        },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun cancel(reason: String?) {
        viewModelScope.launch { runCatching { cancelSale(id, reason) } }
    }
}
