package com.xcloak.storevia.feature.sales

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.ui.LoadingState
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.entity.SaleEntity
import com.xcloak.storevia.core.money.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.*
import com.xcloak.storevia.core.database.dao.observeActiveBranchId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SalesListViewModel @Inject constructor(saleDao: SaleDao, branchDao: BranchDao) : ViewModel() {
    private val activeBranchId = branchDao.observeActiveBranchId()
    private val query = MutableStateFlow("")
    private val statusFilter = MutableStateFlow(SalesStatusFilter.ALL)

    val branchName: StateFlow<String?> = combine(branchDao.observeAll(), activeBranchId) { branches, id ->
        branches.firstOrNull { it.id == id }?.name
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val searchQuery: StateFlow<String> = query.asStateFlow()
    val selectedStatus: StateFlow<SalesStatusFilter> = statusFilter.asStateFlow()
    val sales: StateFlow<List<SaleEntity>?> = combine(activeBranchId.filterNotNull(), query, statusFilter) { branchId, search, status ->
        Triple(branchId, search.trim(), status)
    }.flatMapLatest { (branchId, search, status) ->
        saleDao.observeRecent(200, branchId).map { rows ->
            rows.filter { sale ->
                (search.isEmpty() || sale.invoiceNo.contains(search, ignoreCase = true)) &&
                    when (status) {
                        SalesStatusFilter.ALL -> true
                        SalesStatusFilter.OUTSTANDING -> sale.status != com.xcloak.storevia.core.database.entity.SaleStatus.CANCELLED &&
                            sale.totalPaise - sale.returnedPaise > sale.paidPaise
                        SalesStatusFilter.CANCELLED -> sale.status == com.xcloak.storevia.core.database.entity.SaleStatus.CANCELLED
                    }
            }
        }
    }
        .map<List<SaleEntity>, List<SaleEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSearchQuery(value: String) { query.value = value }
    fun setStatusFilter(value: SalesStatusFilter) { statusFilter.value = value }
}

enum class SalesStatusFilter { ALL, OUTSTANDING, CANCELLED }

private val ROW_DATE = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
fun SalesListScreen(onNewSale: () -> Unit, onOpen: (String) -> Unit, vm: SalesListViewModel = hiltViewModel()) {
    val sales by vm.sales.collectAsStateWithLifecycle()
    val branchName by vm.branchName.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val status by vm.selectedStatus.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewSale, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.new_sale)) })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            branchName?.let {
                Text(stringResource(R.string.stock_branch_scope, it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = query,
                onValueChange = vm::setSearchQuery,
                singleLine = true,
                label = { Text(stringResource(R.string.sales_search_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SalesStatusFilter.ALL to R.string.sales_filter_all,
                    SalesStatusFilter.OUTSTANDING to R.string.sales_filter_outstanding,
                    SalesStatusFilter.CANCELLED to R.string.sales_filter_cancelled,
                ).forEach { (filter, label) ->
                    FilterChip(selected = status == filter, onClick = { vm.setStatusFilter(filter) }, label = { Text(stringResource(label)) })
                }
            }
            when {
                sales == null -> LoadingState(Modifier.weight(1f).fillMaxWidth())
                sales!!.isEmpty() -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (query.isBlank() && status == SalesStatusFilter.ALL) {
                        Icon(Icons.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.sales_empty), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.sales_empty_hint), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.sales_no_matches), style = MaterialTheme.typography.titleMedium)
                    }
                }
                else -> LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sales.orEmpty(), key = { it.id }) { s ->
                        val due = s.totalPaise - s.returnedPaise - s.paidPaise
                        Card(Modifier.fillMaxWidth().clickable { onOpen(s.id) }) {
                            ListItem(
                                headlineContent = { Text(Money(s.totalPaise).format(), style = MaterialTheme.typography.titleMedium) },
                                supportingContent = {
                                    Text(s.invoiceNo + " · " + Instant.ofEpochMilli(s.createdAt).atZone(ZoneId.systemDefault()).format(ROW_DATE))
                                },
                                trailingContent = {
                                    if (s.status == com.xcloak.storevia.core.database.entity.SaleStatus.CANCELLED) AssistChip(onClick = { onOpen(s.id) }, label = { Text(stringResource(R.string.status_cancelled)) })
                                    else if (due > 0) AssistChip(onClick = { onOpen(s.id) }, label = { Text(stringResource(R.string.due_chip, Money(due).format())) })
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            )
                        }
                    }
                }
            }
        }
    }
}
