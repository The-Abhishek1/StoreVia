package com.xcloak.storevia.feature.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.entity.StaffEntity
import com.xcloak.storevia.data.staff.AuthResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full-screen sign-in shown whenever staff accounts exist and nobody is signed in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaffLoginScreen(vm: StaffLoginViewModel = hiltViewModel()) {
    val staff by vm.staff.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<StaffEntity?>(null) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(lockedUntil) { while (System.currentTimeMillis() < lockedUntil) { now = System.currentTimeMillis(); delay(500) }; now = System.currentTimeMillis() }
    val locked = lockedUntil > now

    val wrong = stringResource(R.string.login_wrong_pin)
    val cantSignIn = stringResource(R.string.login_cant_sign_in)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(rememberScrollState()).statusBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Person, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            staff.forEach { s ->
                FilterChip(selected = selected?.id == s.id, onClick = { selected = s; pin = ""; message = null }, label = { Text(s.name) })
            }
        }
        selected?.let { s ->
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = pin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { pin = it; message = null } }, singleLine = true,
                label = { Text(stringResource(R.string.field_pin)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = !locked,
                isError = message != null || locked,
            )
            if (locked) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.login_locked, ((lockedUntil - now) / 1000 + 1).toInt()), color = MaterialTheme.colorScheme.error)
            } else message?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = pin.length >= 4 && !locked && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        when (val r = vm.signIn(s.id, pin)) {
                            is AuthResult.Success -> Unit   // the overlay disappears by itself
                            is AuthResult.WrongPin -> { message = "$wrong (${r.triesLeft})"; pin = "" }
                            is AuthResult.LockedOut -> { lockedUntil = r.untilMillis; pin = "" }
                            AuthResult.Unknown -> message = cantSignIn
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.login_action)) }
        }
    }
}
