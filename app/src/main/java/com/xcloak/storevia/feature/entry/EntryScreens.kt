package com.xcloak.storevia.feature.entry

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Button
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
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
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
        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(Icons.Filled.Store, contentDescription = null)
            Text(stringResource(R.string.entry_online_sign_in), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onSignUp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) {
            Text(stringResource(R.string.entry_online_sign_up))
        }
        OutlinedButton(onClick = onPlatformAdmin, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) {
            Text(stringResource(R.string.entry_platform_admin))
        }
        Text(
            stringResource(R.string.entry_offline_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        OutlinedButton(onClick = onWorkOffline, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.CloudOff, contentDescription = null)
            Text(stringResource(R.string.entry_work_offline), Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StoreChoiceScreen(
    onSignOut: () -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
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