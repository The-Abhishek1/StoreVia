package com.xcloak.storevia.feature.customers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.xcloak.storevia.core.money.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: CustomersViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val totalDue by vm.totalDue.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var owingOnly by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.customers_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.add_customer)) })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.total_khata_due), style = MaterialTheme.typography.labelLarge)
                Text(Money(totalDue).format(), style = MaterialTheme.typography.headlineMedium)
            }
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, singleLine = true,
                placeholder = { Text(stringResource(R.string.customers_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = rows
            FilterChip(
                selected = owingOnly,
                onClick = { owingOnly = !owingOnly },
                label = { Text(stringResource(R.string.customers_owing_filter)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            val filtered = list?.filter { !owingOnly || it.balancePaise > 0 }
            when {
                list == null -> LoadingState(Modifier.weight(1f).fillMaxWidth())
                filtered.isNullOrEmpty() -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val noCustomersYet = list.isEmpty() && query.isBlank() && !owingOnly
                    Text(
                        stringResource(if (noCustomersYet) R.string.customers_empty else R.string.customers_no_matches),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (noCustomersYet) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.customers_empty_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(filtered, key = { it.id }) { r ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(r.id) },
                            headlineContent = { Text(r.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = r.phone?.let { { Text(it) } },
                            trailingContent = { BalanceText(r.balancePaise) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (adding) CustomerFormDialog(null, canEditLimit = can(Permission.EDIT_CREDIT_LIMIT), onSave = { n, p, l -> vm.add(n, p, l) }, onDismiss = { adding = false })
}

@Composable
fun BalanceText(balancePaise: Long) {
    when {
        balancePaise > 0 -> Text(stringResource(R.string.owes, Money(balancePaise).format()),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
        balancePaise < 0 -> Text(stringResource(R.string.advance, Money(-balancePaise).format()), style = MaterialTheme.typography.titleSmall)
        else -> Text(stringResource(R.string.settled), style = MaterialTheme.typography.bodyMedium)
    }
}
