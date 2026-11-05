package com.xcloak.storevia.feature.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.CategoryDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.ProductListRow
import com.xcloak.storevia.core.database.dao.observeActiveBranchId
import com.xcloak.storevia.core.database.entity.CategoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StockListViewModel @Inject constructor(
    productDao: ProductDao,
    categoryDao: CategoryDao,
    branchDao: BranchDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow<String?>(null)
    val category: StateFlow<String?> = _category.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeBranchId = branchDao.observeActiveBranchId()
    val activeBranchName: StateFlow<String?> = combine(branchDao.observeAll(), activeBranchId) { branches, branchId ->
        branches.firstOrNull { it.id == branchId }?.name
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val rows: StateFlow<List<ProductListRow>?> = combine(_query, _category, activeBranchId.filterNotNull()) { q, c, branchId ->
        Triple(q.trim(), c, branchId)
    }.flatMapLatest { (q, c, branchId) ->
        if (c == null) productDao.listWithStock(q, branchId) else productDao.listWithStockByCategory(q, c, branchId)
    }
        .map<List<ProductListRow>, List<ProductListRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            categoryDao.observeAll().collect { list -> if (_category.value != null && list.none { it.id == _category.value }) _category.value = null }
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun onBarcodeScanned(barcode: String) {
        if (barcode.isBlank()) return
        _query.value = barcode
        _category.value = null
    }
    fun setCategory(id: String?) { _category.value = id }
}
