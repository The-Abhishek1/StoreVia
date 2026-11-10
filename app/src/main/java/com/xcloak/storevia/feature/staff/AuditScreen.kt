package com.xcloak.storevia.feature.staff

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.xcloak.storevia.core.access.AuditAction
import com.xcloak.storevia.core.ui.LoadingState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = DateTimeFormatter.ofPattern("d MMM, h:mm a")

fun AuditAction.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(onBack: () -> Unit, vm: AuditViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val list = rows
        when {
            list == null -> LoadingState(Modifier.padding(padding))
            loadError != null -> Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(loadError!!), color = MaterialTheme.colorScheme.error)
            }
            list.isEmpty() -> Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.audit_empty), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.audit_empty_hint), style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                val filtered = list.filter {
                    val q = query.trim()
                    q.isEmpty() || it.summary.contains(q, ignoreCase = true) ||
                        it.actorName.contains(q, ignoreCase = true) || it.action.label().contains(q, ignoreCase = true)
                }
                Column(Modifier.padding(padding).fillMaxSize()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.audit_search_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (filtered.isEmpty()) {
                        Text(
                            stringResource(R.string.audit_no_matches),
                            Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else LazyColumn(Modifier.weight(1f)) {
                        item { Text(stringResource(R.string.audit_hint), Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall) }
                        items(filtered, key = { it.id }) { e ->
                            ListItem(
                                headlineContent = { Text(e.summary) },
                                supportingContent = {
                                    Text(e.actorName + " · " + e.actorRole.lowercase() + " · " +
                                        Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()).format(WHEN) + " · " + e.action.label())
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
