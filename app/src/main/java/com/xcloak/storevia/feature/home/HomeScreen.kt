package com.xcloak.storevia.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.core.money.Money
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    onNewSale: () -> Unit,
    onAddProduct: () -> Unit,
    onViewSales: () -> Unit,
    onViewStock: () -> Unit,
    onCustomers: () -> Unit,
    onReorder: () -> Unit,
    onSetupStore: () -> Unit,
    onOnboarding: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
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
        Text(stringResource(R.string.dashboard_overview), style = MaterialTheme.typography.titleLarge)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.today_sales), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(Money(s.todaySalesPaise).format(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.dashboard_bill_count, s.todayCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                TextButton(onClick = onViewSales, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.home_view_sales), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        SalesTrend(
            days = s.weeklySales,
            previousTotal = s.previousWeekSalesPaise,
        )

        Text(stringResource(R.string.home_store_health), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.products_in_stock), s.productCount.toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.low_stock_items), s.lowStockCount.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.expiring_soon, s.expiryWarnDays), s.expiringCount.toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.khata_due_home), Money(s.khataDuePaise).format(), Modifier.weight(1f))
        }

        Text(stringResource(R.string.home_quick_actions), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(stringResource(R.string.new_sale), Icons.Filled.PointOfSale, onNewSale, Modifier.weight(1f), primary = true)
            QuickAction(stringResource(R.string.home_add_product), Icons.Filled.AddBox, onAddProduct, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(stringResource(R.string.home_view_stock), Icons.Filled.Inventory2, onViewStock, Modifier.weight(1f))
            QuickAction(stringResource(R.string.home_view_sales), Icons.Filled.ShoppingCart, onViewSales, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(stringResource(R.string.home_customers), Icons.Filled.Groups, onCustomers, Modifier.weight(1f))
            QuickAction(stringResource(R.string.home_reorder), Icons.Filled.Inventory2, onReorder, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SalesTrend(days: List<HomeDaySales>, previousTotal: Long) {
    val currentTotal = days.sumOf { it.totalPaise }
    val comparison = if (previousTotal > 0) {
        val change = ((currentTotal - previousTotal) * 100.0 / previousTotal).toInt()
        stringResource(R.string.home_week_change, if (change > 0) "+$change" else change.toString())
    } else {
        stringResource(R.string.home_week_no_comparison)
    }
    val chartDescription = stringResource(
        R.string.home_chart_accessibility,
        Money(currentTotal).format(),
        comparison,
    )
    val todayBarColor = MaterialTheme.colorScheme.primary
    val previousBarColor = MaterialTheme.colorScheme.tertiary
    val formatter = DateTimeFormatter.ofPattern("EE", Locale.getDefault())
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.home_week_title), style = MaterialTheme.typography.titleMedium)
            Text(Money(currentTotal).format(), style = MaterialTheme.typography.headlineSmall)
            Text(comparison, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(
                Modifier.fillMaxWidth().height(104.dp).semantics {
                    contentDescription = chartDescription
                },
            ) {
                if (days.isEmpty()) return@Canvas
                val maxValue = days.maxOfOrNull { it.totalPaise }?.coerceAtLeast(1L) ?: 1L
                val gap = size.width * 0.035f
                val barWidth = (size.width - gap * (days.size + 1)) / days.size
                val maxBarHeight = size.height - 4.dp.toPx()
                days.forEachIndexed { index, day ->
                    val barHeight = (day.totalPaise.toDouble() / maxValue * maxBarHeight)
                        .toFloat()
                        .coerceAtLeast(4.dp.toPx())
                    val color = if (index == days.lastIndex) {
                        todayBarColor
                    } else {
                        previousBarColor
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            x = gap + index * (barWidth + gap),
                            y = size.height - barHeight,
                        ),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    Text(
                        day.day.format(formatter),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val buttonModifier = modifier.height(58.dp)
    if (primary) {
        Button(onClick = onClick, modifier = buttonModifier) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, maxLines = 1)
        }
    } else {
        FilledTonalButton(onClick = onClick, modifier = buttonModifier) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, maxLines = 1)
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
