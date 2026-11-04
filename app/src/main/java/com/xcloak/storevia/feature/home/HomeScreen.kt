package com.xcloak.storevia.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.core.money.Money

@Composable
fun HomeScreen(onNewSale: () -> Unit, onSetupStore: () -> Unit, onOnboarding: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val needsSetup by vm.needsSetup.collectAsStateWithLifecycle()
    val needsOnboarding by vm.needsOnboarding.collectAsStateWithLifecycle()
    LaunchedEffect(needsOnboarding) { if (needsOnboarding) onOnboarding() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (needsSetup && can(Permission.MANAGE_SETTINGS)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.finish_setup_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.finish_setup_body), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onSetupStore) { Text(stringResource(R.string.finish_setup_action)) }
                }
            }
        }
        Text(stringResource(R.string.dashboard_overview), style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.today_sales), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(Money(s.todaySalesPaise).format(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.dashboard_bill_count, s.todayCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.products_in_stock), s.productCount.toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.low_stock_items), s.lowStockCount.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.expiring_soon, s.expiryWarnDays), s.expiringCount.toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.khata_due_home), Money(s.khataDuePaise).format(), Modifier.weight(1f))
        }

        Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.new_sale))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
