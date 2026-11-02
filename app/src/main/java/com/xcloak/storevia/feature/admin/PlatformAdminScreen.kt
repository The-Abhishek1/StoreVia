package com.xcloak.storevia.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xcloak.storevia.R

private data class SummaryCard(val title: String, val value: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformAdminScreen(onBack: () -> Unit) {
    val cards = listOf(
        SummaryCard("Stores", "12 active", Icons.Filled.Store),
        SummaryCard("Payments", "₹4.8L", Icons.Filled.MonetizationOn),
        SummaryCard("Actions", "3 holds", Icons.Filled.Delete),
    )

    val stores = listOf(
        "City General Mart",
        "Wellness Medical",
        "Metro Wholesale Hub",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.platform_admin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.platform_admin_overview),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    cards.forEach { card ->
                        Card(modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(card.icon, contentDescription = null)
                                Text(card.title, style = MaterialTheme.typography.labelMedium)
                                Text(card.value, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text(stringResource(R.string.platform_admin_add_store))
                    }
                    OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.platform_admin_insights))
                    }
                }
            }

            item {
                Text(stringResource(R.string.platform_admin_store_list), style = MaterialTheme.typography.titleMedium)
            }

            items(stores) { name ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.platform_admin_store_summary), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {}) { Text(stringResource(R.string.platform_admin_edit)) }
                            OutlinedButton(onClick = {}) { Text(stringResource(R.string.platform_admin_hold)) }
                            OutlinedButton(onClick = {}) { Text(stringResource(R.string.platform_admin_delete)) }
                        }
                    }
                }
            }
        }
    }
}
