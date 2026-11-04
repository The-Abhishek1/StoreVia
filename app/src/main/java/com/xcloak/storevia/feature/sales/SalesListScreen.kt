package com.xcloak.storevia.feature.sales

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SalesListViewModel @Inject constructor(saleDao: SaleDao, branchDao: BranchDao) : ViewModel() {
    val sales: StateFlow<List<SaleEntity>?> = branchDao.observeSelectedId().map { it ?: "default" }
        .distinctUntilChanged().flatMapLatest { branchId -> saleDao.observeRecent(200, branchId) }
        .map<List<SaleEntity>, List<SaleEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private val ROW_DATE = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
fun SalesListScreen(onNewSale: () -> Unit, onOpen: (String) -> Unit, vm: SalesListViewModel = hiltViewModel()) {
    val sales by vm.sales.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewSale, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.new_sale)) })
        },
    ) { padding ->
        val list = sales
        when {
            list == null -> LoadingState(Modifier.padding(padding))
            list.isEmpty() -> Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.sales_empty), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.sales_empty_hint), style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { s ->
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
