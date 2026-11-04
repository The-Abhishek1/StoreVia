package com.xcloak.storevia.feature.stock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.CategoryDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.ProductListRow
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
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow<String?>(null)
    val category: StateFlow<String?> = _category.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeBranchId = branchDao.observeSelectedId().map { it ?: "default" }.distinctUntilChanged()

    val rows: StateFlow<List<ProductListRow>?> = combine(_query, _category, activeBranchId) { q, c, branchId ->
        Triple(q.trim(), c, branchId)
    }.flatMapLatest { (q, c, branchId) ->
        if (c == null) productDao.listWithStock(q, branchId) else productDao.listWithStockByCategory(q, c, branchId)
    }
        .map<List<ProductListRow>, List<ProductListRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // The scanner screen writes its result into this entry's SavedStateHandle under "barcode".
        viewModelScope.launch {
            handle.getStateFlow<String?>("barcode", null).filterNotNull().collect {
                _query.value = it
                handle["barcode"] = null
            }
        }
        // A deleted category can't stay selected.
        viewModelScope.launch {
            categoryDao.observeAll().collect { list -> if (_category.value != null && list.none { it.id == _category.value }) _category.value = null }
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setCategory(id: String?) { _category.value = id }
}
