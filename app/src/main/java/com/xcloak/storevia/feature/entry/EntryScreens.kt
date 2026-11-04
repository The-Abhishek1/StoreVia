package com.xcloak.storevia.feature.entry

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xcloak.storevia.R

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EntryWelcomeScreen(
    onOnlineCloud: () -> Unit,
    onPlatformAdmin: () -> Unit,
    onWorkOffline: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_storevia), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.entry_welcome_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.entry_welcome_body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        Button(onClick = onOnlineCloud, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(Icons.Filled.Store, contentDescription = null)
            Text(stringResource(R.string.entry_online_cloud), Modifier.padding(start = 8.dp))
        }
        Text(
            stringResource(R.string.entry_offline_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        OutlinedButton(onClick = onWorkOffline, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Icon(Icons.Filled.CloudOff, contentDescription = null)
            Text(stringResource(R.string.entry_offline_store), Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Text(
            stringResource(R.string.entry_restricted_access),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        OutlinedButton(onClick = onPlatformAdmin, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.entry_platform_admin))
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun OnlineCloudChoiceScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.entry_online_cloud)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.entry_online_cloud_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.entry_online_cloud_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.entry_new_users), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.entry_create_store), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.entry_new_store_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onSignUp, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text(stringResource(R.string.entry_online_sign_up))
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.entry_existing_users), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.entry_sign_in_existing), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.entry_existing_account_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text(stringResource(R.string.entry_online_sign_in))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StoreChoiceScreen(
    onSignOut: () -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    connectionError: String? = null,
    onRetryExistingStore: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.entry_store_choice_title)) },
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.cloud_sign_out))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.entry_store_choice_body), style = MaterialTheme.typography.bodyLarge)
            if (connectionError != null) {
                Text(
                    connectionError,
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                onRetryExistingStore?.let { retry ->
                    OutlinedButton(onClick = retry, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) {
                        Text(stringResource(R.string.entry_retry_store_connection))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(stringResource(R.string.entry_create_store))
            }
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) {
                Text(stringResource(R.string.entry_join_store))
            }
        }
    }
}