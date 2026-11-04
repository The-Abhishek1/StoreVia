package com.xcloak.storevia.feature.suppliers

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
import com.xcloak.storevia.core.ui.LoadingState
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.ui.can

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuppliersScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: SuppliersViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val payable by vm.totalPayable.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var payableOnly by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.suppliers_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            if (can(Permission.MANAGE_PURCHASES)) {
                ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_supplier)) })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.total_payable), style = MaterialTheme.typography.labelLarge)
                Text(Money(payable).format(), style = MaterialTheme.typography.headlineMedium)
            }
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, singleLine = true,
                placeholder = { Text(stringResource(R.string.customers_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = rows
            FilterChip(
                selected = payableOnly,
                onClick = { payableOnly = !payableOnly },
                label = { Text(stringResource(R.string.suppliers_payable_filter)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            val filtered = list?.filter { !payableOnly || it.balancePaise > 0 }
            when {
                list == null -> LoadingState(Modifier.weight(1f).fillMaxWidth())
                filtered.isNullOrEmpty() -> Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    val noSuppliersYet = list.isEmpty() && query.isBlank() && !payableOnly
                    Text(
                        stringResource(if (noSuppliersYet) R.string.suppliers_empty else R.string.suppliers_no_matches),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (noSuppliersYet) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.suppliers_empty_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(filtered, key = { it.id }) { r ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(r.id) },
                            headlineContent = { Text(r.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = r.phone?.let { { Text(it) } },
                            trailingContent = { PayableText(r.balancePaise) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (adding && can(Permission.MANAGE_PURCHASES)) {
        SupplierFormDialog(null, onSave = { n, p, g -> vm.add(n, p, g) }, onDismiss = { adding = false })
    }
}

/** "You owe ₹x" in red, "Settled", or "In credit ₹x" when you've overpaid. */
@Composable
fun PayableText(balancePaise: Long) {
    when {
        balancePaise > 0 -> Text(stringResource(R.string.you_owe, Money(balancePaise).format()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
        balancePaise < 0 -> Text(stringResource(R.string.in_credit, Money(-balancePaise).format()), style = MaterialTheme.typography.titleSmall)
        else -> Text(stringResource(R.string.settled), style = MaterialTheme.typography.bodyMedium)
    }
}
