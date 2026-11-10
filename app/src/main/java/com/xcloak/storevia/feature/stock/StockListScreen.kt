package com.xcloak.storevia.feature.stock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.core.ui.LoadingState
import com.xcloak.storevia.core.database.dao.ProductListRow
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing

private enum class StockFilter { ALL, LOW, OUT }

@Composable
fun StockListScreen(
    onAdd: () -> Unit,
    onAddWithBarcode: (String) -> Unit,
    onOpen: (String) -> Unit,
    onQuickAdjust: (String) -> Unit,
    onScan: (onResult: (String) -> Unit) -> Unit,
    vm: StockListViewModel = hiltViewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val catalogError by vm.catalogError.collectAsStateWithLifecycle()
    val selectedCategory by vm.category.collectAsStateWithLifecycle()
    val activeBranchName by vm.activeBranchName.collectAsStateWithLifecycle()
    var stockFilter by remember { mutableStateOf(StockFilter.ALL) }
    val canAdjust = can(Permission.ADJUST_STOCK)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (can(Permission.MANAGE_PRODUCTS)) ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.add_product)) })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, singleLine = true,
                placeholder = { Text(stringResource(R.string.stock_search_hint)) },
                trailingIcon = {
                    IconButton(onClick = { onScan(vm::onBarcodeScanned) }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.scan_barcode))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )

            activeBranchName?.let { branchName ->
                Text(
                    stringResource(R.string.stock_branch_scope, branchName),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val allRows = rows.orEmpty()
            val lowStockCount = allRows.count { it.onHandMilli > 0 && it.minStockMilli > 0 && it.onHandMilli < it.minStockMilli }
            val outOfStockCount = allRows.count { it.onHandMilli <= 0 }
            StockOverview(
                productCount = allRows.size,
                lowStockCount = lowStockCount,
                outOfStockCount = outOfStockCount,
            )
            StockFilters(
                selected = stockFilter,
                productCount = allRows.size,
                lowStockCount = lowStockCount,
                outOfStockCount = outOfStockCount,
                onSelected = { stockFilter = it },
            )

            if (categories.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(selectedCategory == null, { vm.setCategory(null) }, { Text(stringResource(R.string.category_all)) }) }
                    items(categories, key = { it.id }) { c -> FilterChip(selectedCategory == c.id, { vm.setCategory(c.id) }, { Text(c.name) }) }
                }
                Spacer(Modifier.height(8.dp))
            }
            val list = when (stockFilter) {
                StockFilter.ALL -> rows
                StockFilter.LOW -> rows?.filter {
                    it.onHandMilli > 0 && it.minStockMilli > 0 && it.onHandMilli < it.minStockMilli
                }
                StockFilter.OUT -> rows?.filter { it.onHandMilli <= 0 }
            }
            val openProduct = { productId: String ->
                vm.setQuery("")
                onOpen(productId)
            }
            val addProductWithBarcode = { barcode: String ->
                vm.setQuery("")
                onAddWithBarcode(barcode)
            }
            when {
                list == null -> LoadingState(Modifier.weight(1f).fillMaxWidth())
                list.isEmpty() && rows.orEmpty().isNotEmpty() -> FilterEmptyState { stockFilter = StockFilter.ALL }
                list.isEmpty() && catalogError != null -> Text(
                    catalogError.orEmpty(),
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                list.isEmpty() -> EmptyState(query.trim(), can(Permission.MANAGE_PRODUCTS), addProductWithBarcode)
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) {
                        row ->
                        ProductRow(
                            row,
                            canAdjust,
                            onOpen = { openProduct(row.id) },
                            onQuickAdjust = { onQuickAdjust(row.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StockOverview(productCount: Int, lowStockCount: Int, outOfStockCount: Int) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StockMetric(productCount, stringResource(R.string.stock_metric_products))
            StockMetric(lowStockCount, stringResource(R.string.stock_metric_low))
            StockMetric(outOfStockCount, stringResource(R.string.stock_metric_out))
        }
    }
}

@Composable
private fun StockMetric(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StockFilters(
    selected: StockFilter,
    productCount: Int,
    lowStockCount: Int,
    outOfStockCount: Int,
    onSelected: (StockFilter) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected == StockFilter.ALL,
                { onSelected(StockFilter.ALL) },
                { Text(stringResource(R.string.stock_filter_all, productCount)) },
            )
        }
        item {
            FilterChip(
                selected == StockFilter.LOW,
                { onSelected(StockFilter.LOW) },
                { Text(stringResource(R.string.stock_filter_low, lowStockCount)) },
            )
        }
        item {
            FilterChip(
                selected == StockFilter.OUT,
                { onSelected(StockFilter.OUT) },
                { Text(stringResource(R.string.stock_filter_out, outOfStockCount)) },
            )
        }
    }
}

@Composable
private fun ProductRow(
    row: ProductListRow,
    canAdjust: Boolean,
    onOpen: () -> Unit,
    onQuickAdjust: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        ListItem(
            headlineContent = {
                Text(
                    listOfNotNull(row.name, row.variantLabel).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.in_stock_line,
                        Money(row.sellPricePaise).format(),
                        Parsing.milliToInput(row.onHandMilli),
                        row.unitSymbol,
                    )
                )
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    when {
                        row.onHandMilli <= 0 -> AssistChip(onClick = onOpen, label = { Text(stringResource(R.string.out_of_stock)) })
                        row.minStockMilli > 0 && row.onHandMilli < row.minStockMilli ->
                            AssistChip(onClick = onOpen, label = { Text(stringResource(R.string.low)) })
                    }
                    if (canAdjust && row.onHandMilli > 0) {
                        TextButton(onClick = onQuickAdjust, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(stringResource(R.string.adjust_stock))
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
private fun FilterEmptyState(onShowAll: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.stock_filter_empty), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onShowAll) { Text(stringResource(R.string.stock_filter_show_all)) }
    }
}

@Composable
private fun EmptyState(query: String, canAdd: Boolean, onAddWithBarcode: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (query.isEmpty()) {
            Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.stock_empty), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.stock_empty_hint), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.stock_no_match, query), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            if (canAdd) OutlinedButton(onClick = { onAddWithBarcode(query) }) { Text(stringResource(R.string.add_with_barcode)) }
        }
    }
}
