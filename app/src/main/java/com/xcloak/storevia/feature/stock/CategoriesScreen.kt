package com.xcloak.storevia.feature.stock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import kotlinx.coroutines.launch
import com.xcloak.storevia.core.ui.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit, vm: CategoriesViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CategoryRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CategoryRow?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.categories_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_category)) })
        },
    ) { padding ->
        val list = rows
        when {
            list == null -> LoadingState(Modifier.padding(padding))
            list.isEmpty() -> Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.categories_empty), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.categories_empty_hint), style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(list, key = { it.category.id }) { r ->
                    ListItem(
                        headlineContent = { Text(r.category.name, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(stringResource(R.string.category_products, r.productCount)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { editing = r }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                                IconButton(onClick = { deleting = r }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) NameDialog(R.string.add_category, "", onSave = { vm.add(it) }, onDismiss = { adding = false })
    editing?.let { r -> NameDialog(R.string.rename_category, r.category.name, onSave = { vm.rename(r.category.id, it) }, onDismiss = { editing = null }) }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_category_title)) },
            text = { Text(stringResource(R.string.delete_category_body)) },
            confirmButton = { TextButton(onClick = { scope.launch { vm.delete(r.category.id) }; deleting = null }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** [onSave] returns a string-resource id when the name is not acceptable, or null when saved. */
@Composable
private fun NameDialog(title: Int, initial: String, onSave: suspend (String) -> Int?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(name, { name = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.field_category_name)) },
                isError = error != null, supportingText = error?.let { { Text(stringResource(it)) } })
        },
        confirmButton = { TextButton(onClick = { scope.launch { val e = onSave(name); if (e == null) onDismiss() else error = e } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
