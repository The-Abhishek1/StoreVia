package com.xcloak.storevia.feature.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.ProductListRow
import com.xcloak.storevia.core.database.dao.observeActiveBranchId
import com.xcloak.storevia.core.database.entity.CategoryEntity
import com.xcloak.storevia.data.product.ProductCatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StockListViewModel @Inject constructor(
    catalog: ProductCatalogRepository,
    branchDao: BranchDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow<String?>(null)
    val category: StateFlow<String?> = _category.asStateFlow()

    private val _categoryError = MutableStateFlow<String?>(null)
    private val _productError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = combine(_productError, _categoryError) { product, category ->
        product ?: category
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val categories: StateFlow<List<CategoryEntity>> = catalog.observeCategories()
        .onEach { _categoryError.value = null }
        .catch { _categoryError.value = it.message ?: "Cloud categories could not be loaded."; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeBranchId = branchDao.observeActiveBranchId()
    val activeBranchName: StateFlow<String?> = combine(branchDao.observeAll(), activeBranchId) { branches, branchId ->
        branches.firstOrNull { it.id == branchId }?.name
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val rows: StateFlow<List<ProductListRow>?> = combine(_query, _category, activeBranchId.filterNotNull()) { q, c, branchId ->
        Triple(q.trim(), c, branchId)
    }.flatMapLatest { (q, c, branchId) ->
        catalog.observeStockRows(q, c, branchId)
            .onEach { _productError.value = null }
            .catch { _productError.value = it.message ?: "Cloud products could not be loaded."; emit(emptyList()) }
    }
        .map<List<ProductListRow>, List<ProductListRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            categories.collect { list -> if (_category.value != null && list.none { it.id == _category.value }) _category.value = null }
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
