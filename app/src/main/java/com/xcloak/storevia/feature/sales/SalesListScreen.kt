package com.xcloak.storevia.feature.sales

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
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
import com.xcloak.storevia.core.database.entity.SaleStatus
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
    val recentSales: StateFlow<List<SaleEntity>?> = activeBranchId.filterNotNull()
        .flatMapLatest { saleDao.observeRecent(200, it) }
        .map<List<SaleEntity>, List<SaleEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sales: StateFlow<List<SaleEntity>?> = combine(recentSales, query, statusFilter) { rows, search, status ->
        rows?.filter { sale ->
            (search.isBlank() || sale.invoiceNo.contains(search.trim(), ignoreCase = true)) &&
                when (status) {
                    SalesStatusFilter.ALL -> true
                    SalesStatusFilter.OUTSTANDING -> sale.status != SaleStatus.CANCELLED &&
                        sale.totalPaise - sale.returnedPaise > sale.paidPaise
                    SalesStatusFilter.CANCELLED -> sale.status == SaleStatus.CANCELLED
                }
        }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSearchQuery(value: String) { query.value = value }
    fun setStatusFilter(value: SalesStatusFilter) { statusFilter.value = value }
}

enum class SalesStatusFilter { ALL, OUTSTANDING, CANCELLED }

private val ROW_DATE = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
fun SalesListScreen(onNewSale: () -> Unit, onOpen: (String) -> Unit, vm: SalesListViewModel = hiltViewModel()) {
    val sales by vm.sales.collectAsStateWithLifecycle()
    val recentSales by vm.recentSales.collectAsStateWithLifecycle()
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
            val recent = recentSales.orEmpty()
            val completedSales = recent.filter { it.status != SaleStatus.CANCELLED }
            Text(
                stringResource(R.string.sales_recent_summary),
                Modifier.padding(start = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SalesOverview(
                billCount = recent.size,
                collectedPaise = completedSales.sumOf { it.paidPaise },
                duePaise = completedSales.sumOf { (it.totalPaise - it.returnedPaise - it.paidPaise).coerceAtLeast(0) },
            )
            OutlinedTextField(
                value = query,
                onValueChange = vm::setSearchQuery,
                singleLine = true,
                label = { Text(stringResource(R.string.sales_search_hint)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setSearchQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = status == SalesStatusFilter.ALL,
                        onClick = { vm.setStatusFilter(SalesStatusFilter.ALL) },
                        label = { Text(stringResource(R.string.sales_filter_all_count, recent.size)) },
                    )
                }
                item {
                    FilterChip(
                        selected = status == SalesStatusFilter.OUTSTANDING,
                        onClick = { vm.setStatusFilter(SalesStatusFilter.OUTSTANDING) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.sales_filter_outstanding_count,
                                    recent.count { it.status != SaleStatus.CANCELLED && it.totalPaise - it.returnedPaise > it.paidPaise },
                                )
                            )
                        },
                    )
                }
                item {
                    FilterChip(
                        selected = status == SalesStatusFilter.CANCELLED,
                        onClick = { vm.setStatusFilter(SalesStatusFilter.CANCELLED) },
                        label = {
                            Text(stringResource(R.string.sales_filter_cancelled_count, recent.count { it.status == SaleStatus.CANCELLED }))
                        },
                    )
                }
            }
            when {
                sales == null -> LoadingState(Modifier.weight(1f).fillMaxWidth())
                sales!!.isEmpty() -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (query.isBlank() && status == SalesStatusFilter.ALL) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
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
                        SaleRow(s, onOpen = { onOpen(s.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SalesOverview(billCount: Int, collectedPaise: Long, duePaise: Long) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SalesMetric(billCount.toString(), stringResource(R.string.sales_metric_bills))
            SalesMetric(Money(collectedPaise).format(), stringResource(R.string.sales_metric_collected))
            SalesMetric(Money(duePaise).format(), stringResource(R.string.sales_metric_due))
        }
    }
}

@Composable
private fun SalesMetric(value: String, label: String) {
    Column(
        Modifier.widthIn(max = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SaleRow(sale: SaleEntity, onOpen: () -> Unit) {
    val due = (sale.totalPaise - sale.returnedPaise - sale.paidPaise).coerceAtLeast(0)
    val statusText = when {
        sale.status == SaleStatus.CANCELLED -> stringResource(R.string.status_cancelled)
        sale.status == SaleStatus.RETURNED -> stringResource(R.string.status_returned)
        sale.status == SaleStatus.PARTIAL_RETURN -> stringResource(R.string.status_partial_return)
        due > 0 -> stringResource(R.string.due_chip, Money(due).format())
        else -> stringResource(R.string.paid)
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        ListItem(
            headlineContent = { Text(sale.invoiceNo, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Instant.ofEpochMilli(sale.createdAt).atZone(ZoneId.systemDefault()).format(ROW_DATE))
                    Text(
                        stringResource(R.string.sales_received_total, Money(sale.paidPaise).format(), Money(sale.totalPaise).format()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Money(sale.totalPaise).format(), style = MaterialTheme.typography.titleSmall)
                    AssistChip(onClick = onOpen, label = { Text(statusText) })
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }
}
