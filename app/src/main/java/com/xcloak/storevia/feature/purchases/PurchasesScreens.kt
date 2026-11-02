package com.xcloak.storevia.feature.purchases

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.*
import com.xcloak.storevia.core.database.entity.PurchaseEntity
import com.xcloak.storevia.core.database.entity.PurchaseLineEntity
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.domain.purchase.ReorderPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
private fun Long.asDate() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(DATE)

// ------------------------------------------------------------------ purchase list

@HiltViewModel
class PurchasesListViewModel @Inject constructor(dao: PurchaseDao) : ViewModel() {
    val rows: StateFlow<List<PurchaseListRow>?> = dao.observeRecent()
        .map<List<PurchaseListRow>, List<PurchaseListRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchasesListScreen(onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit, vm: PurchasesListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.purchases_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.new_purchase)) }) },
    ) { padding ->
        val list = rows
        when {
            list == null -> Unit
            list.isEmpty() -> Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.purchases_empty), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.purchases_empty_hint), style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(list, key = { it.id }) { r ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(r.id) },
                        headlineContent = { Text(r.supplierName ?: stringResource(R.string.cash_purchase), style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(listOfNotNull(r.invoiceNo, r.purchaseDate.asDate()).joinToString(" · ")) },
                        trailingContent = { Text(Money(r.totalPaise).format(), style = MaterialTheme.typography.titleMedium) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ purchase detail

data class PurchaseDetailUi(val purchase: PurchaseEntity, val supplierName: String?, val lines: List<PurchaseLineEntity>)

@HiltViewModel
class PurchaseDetailViewModel @Inject constructor(handle: SavedStateHandle, purchaseDao: PurchaseDao, supplierDao: SupplierDao) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])
    val ui: StateFlow<PurchaseDetailUi?> = combine(purchaseDao.observeById(id), purchaseDao.observeLines(id)) { p, lines ->
        if (p == null) null else PurchaseDetailUi(p, p.supplierId?.let { supplierDao.getById(it)?.name }, lines)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseDetailScreen(onBack: () -> Unit, vm: PurchaseDetailViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val d = ui
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.purchase_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
        if (d == null) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.supplierName ?: stringResource(R.string.cash_purchase), style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(d.purchase.invoiceNo, d.purchase.purchaseDate.asDate()).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            d.lines.forEach { l ->
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(l.nameSnapshot, style = MaterialTheme.typography.bodyLarge)
                        Text("${Parsing.milliToInput(l.qtyMilli)} × ${Money(l.unitCostPaise).format()}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(Money(l.lineTotalPaise).format(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row { Text(stringResource(R.string.field_bill_total), Modifier.weight(1f)); Text(Money(d.purchase.totalPaise).format(), style = MaterialTheme.typography.titleMedium) }
            Row { Text(stringResource(R.string.paid), Modifier.weight(1f)); Text(Money(d.purchase.paidPaise).format()) }
            val owed = d.purchase.totalPaise - d.purchase.paidPaise
            if (owed > 0) Row { Text(stringResource(R.string.put_on_account), Modifier.weight(1f)); Text(Money(owed).format(), color = MaterialTheme.colorScheme.error) }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text(stringResource(R.string.done)) }
    }
}

// ------------------------------------------------------------------ reorder list

data class ReorderUi(val storeName: String, val rows: List<ReorderRow>)

@HiltViewModel
class ReorderViewModel @Inject constructor(reportDao: ReportDao, storeDao: StoreDao) : ViewModel() {
    val ui: StateFlow<ReorderUi?> = combine(reportDao.reorderRows(), storeDao.observe()) { rows, store -> ReorderUi(store?.name.orEmpty(), rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderScreen(onBack: () -> Unit, onNewPurchase: () -> Unit, vm: ReorderViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val d = ui
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.reorder_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (d != null && d.rows.isNotEmpty()) IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ReorderPlanner.shareText(d.storeName, d.rows)) }
                    context.startActivity(Intent.createChooser(send, null))
                }) { Icon(Icons.Filled.Share, stringResource(R.string.share_list)) }
            },
        )
        if (d == null) return@Column
        if (d.rows.isEmpty()) {
            Text(stringResource(R.string.reorder_none), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Text(stringResource(R.string.reorder_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            items(d.rows, key = { it.productId }) { r ->
                val suggest = ReorderPlanner.suggestMilli(r.onHandMilli, r.minStockMilli, r.allowDecimal)
                ListItem(
                    headlineContent = { Text(r.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(stringResource(R.string.reorder_row, Parsing.milliToInput(r.onHandMilli), Parsing.milliToInput(r.minStockMilli), r.unitSymbol)) },
                    trailingContent = { Text("+" + Parsing.milliToInput(suggest) + " " + r.unitSymbol, style = MaterialTheme.typography.titleSmall) },
                )
                HorizontalDivider()
            }
        }
        Button(onClick = onNewPurchase, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text(stringResource(R.string.new_purchase)) }
    }
}
