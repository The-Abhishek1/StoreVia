package com.xcloak.storevia

import android.os.Bundle
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xcloak.storevia.core.access.StaffSession
import com.xcloak.storevia.core.security.AppLock
import com.xcloak.storevia.core.security.DeviceAuth
import com.xcloak.storevia.core.ui.LocalActor
import com.xcloak.storevia.core.ui.LockScreen
import com.xcloak.storevia.core.ui.StoreviaNavHost
import com.xcloak.storevia.core.ui.theme.StoreviaTheme
import com.xcloak.storevia.data.AppBootstrap
import com.xcloak.storevia.data.cloud.CloudAccountRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.feature.admin.PlatformAdminScreen
import com.xcloak.storevia.feature.cloud.CloudAccountScreen
import com.xcloak.storevia.feature.entry.EntryWelcomeScreen
import com.xcloak.storevia.feature.entry.StoreChoiceScreen
import com.xcloak.storevia.feature.onboarding.OnboardingScreen
import com.xcloak.storevia.feature.staff.StaffLoginScreen
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var session: StaffSession
    @Inject lateinit var supabase: SupabaseClient
    @Inject lateinit var cloudAccount: CloudAccountRepository
    @Inject lateinit var bootstrap: AppBootstrap

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) appLock.unlock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthCallback(intent)
        enableEdgeToEdge()
        lifecycleScope.launch { bootstrap.ensureSeeded() }

        // With the lock on, hide the app from screenshots and the recent-apps preview.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                prefs.settings.map { it.lockEnabled }.distinctUntilChanged().collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            val settings by prefs.settings.collectAsStateWithLifecycle()
            val authStatus by cloudAccount.authSessionStatus.collectAsStateWithLifecycle()
            StoreviaTheme(settings.themeMode) {
                val locked by appLock.locked.collectAsStateWithLifecycle()
                val actor by session.actorState.collectAsStateWithLifecycle()
                val needsLogin by session.needsLogin.collectAsStateWithLifecycle()
                var entryRoute by rememberSaveable { mutableStateOf(EntryRoute.WELCOME) }
                val authenticated = authStatus is SessionStatus.Authenticated

                androidx.compose.runtime.LaunchedEffect(authStatus) {
                    when {
                        authenticated && (entryRoute == EntryRoute.BUSINESS_AUTH || entryRoute == EntryRoute.BUSINESS_SIGN_UP) -> entryRoute = EntryRoute.STORE_CHOICE
                        authenticated && entryRoute == EntryRoute.ADMIN_AUTH -> entryRoute = EntryRoute.ADMIN_PORTAL
                        authenticated && entryRoute == EntryRoute.WELCOME && settings.cloudStoreId == null -> {
                            entryRoute = if (cloudAccount.isPlatformAdmin()) EntryRoute.ADMIN_PORTAL else EntryRoute.STORE_CHOICE
                        }
                        !authenticated && authStatus is SessionStatus.NotAuthenticated && entryRoute == EntryRoute.ADMIN_PORTAL -> entryRoute = EntryRoute.WELCOME
                    }
                }

                val showRetailApp = (authenticated && settings.cloudStoreId != null) || (!authenticated && settings.localOnlyMode)
                CompositionLocalProvider(LocalActor provides actor) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            authStatus is SessionStatus.Initializing -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            showRetailApp -> {
                                StoreviaNavHost(localOnly = !authenticated && settings.localOnlyMode)
                                if (needsLogin) StaffLoginScreen()
                            }
                            authenticated && entryRoute == EntryRoute.ADMIN_PORTAL -> {
                                PlatformAdminScreen(onBack = { entryRoute = EntryRoute.ADMIN_AUTH })
                            }
                            authenticated && entryRoute == EntryRoute.ADMIN_AUTH -> {
                                CloudAccountScreen(
                                    onBack = { entryRoute = EntryRoute.STORE_CHOICE },
                                    onPlatformAdmin = { entryRoute = EntryRoute.ADMIN_PORTAL },
                                    adminOnly = true,
                                )
                            }
                            authenticated && entryRoute == EntryRoute.STORE_CHOICE -> {
                                StoreChoiceScreen(
                                    onSignOut = { lifecycleScope.launch { cloudAccount.signOut(); entryRoute = EntryRoute.WELCOME } },
                                    onCreate = { entryRoute = if (settings.onboardingDone) EntryRoute.CREATE_CLOUD_STORE else EntryRoute.STORE_SETUP },
                                    onJoin = { entryRoute = EntryRoute.JOIN_STORE },
                                )
                            }
                            authenticated && entryRoute == EntryRoute.STORE_SETUP -> {
                                OnboardingScreen(
                                    onFinish = { entryRoute = EntryRoute.CREATE_CLOUD_STORE },
                                    onImport = { entryRoute = EntryRoute.CREATE_CLOUD_STORE },
                                    onAddProduct = { entryRoute = EntryRoute.CREATE_CLOUD_STORE },
                                    cloudConnectRequired = true,
                                )
                            }
                            authenticated && entryRoute == EntryRoute.CREATE_CLOUD_STORE -> {
                                CloudAccountScreen(
                                    onBack = { entryRoute = EntryRoute.STORE_CHOICE },
                                    onPlatformAdmin = { entryRoute = EntryRoute.ADMIN_PORTAL },
                                    createOnly = true,
                                )
                            }
                            authenticated && entryRoute == EntryRoute.JOIN_STORE -> {
                                CloudAccountScreen(
                                    onBack = { entryRoute = EntryRoute.STORE_CHOICE },
                                    onPlatformAdmin = { entryRoute = EntryRoute.ADMIN_PORTAL },
                                    joinOnly = true,
                                )
                            }
                            authenticated -> {
                                StoreChoiceScreen(
                                    onSignOut = { lifecycleScope.launch { cloudAccount.signOut(); entryRoute = EntryRoute.WELCOME } },
                                    onCreate = { entryRoute = if (settings.onboardingDone) EntryRoute.CREATE_CLOUD_STORE else EntryRoute.STORE_SETUP },
                                    onJoin = { entryRoute = EntryRoute.JOIN_STORE },
                                )
                            }
                            entryRoute == EntryRoute.BUSINESS_AUTH || entryRoute == EntryRoute.BUSINESS_SIGN_UP || entryRoute == EntryRoute.ADMIN_AUTH -> {
                                CloudAccountScreen(
                                    onBack = { entryRoute = EntryRoute.WELCOME },
                                    onPlatformAdmin = { entryRoute = EntryRoute.ADMIN_PORTAL },
                                    adminOnly = entryRoute == EntryRoute.ADMIN_AUTH,
                                    initialCreateAccount = entryRoute == EntryRoute.BUSINESS_SIGN_UP,
                                )
                            }
                            else -> EntryWelcomeScreen(
                                onSignIn = { entryRoute = EntryRoute.BUSINESS_AUTH },
                                onSignUp = { entryRoute = EntryRoute.BUSINESS_SIGN_UP },
                                onPlatformAdmin = { entryRoute = EntryRoute.ADMIN_AUTH },
                                onWorkOffline = { prefs.update { copy(localOnlyMode = true) } },
                            )
                        }
                        if (locked) LockScreen(onUnlock = ::promptUnlock)   // the phone's own lock sits on top of everything
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); appLock.onStart(); session.onStart() }
    override fun onStop() { super.onStop(); appLock.onStop(); session.onStop() }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: android.content.Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "storevia" || uri.host != "auth") return
        val fragmentParams = uri.fragment?.let { Uri.parse("https://callback/?$it") }
        val error = uri.getQueryParameter("error_description")
            ?: fragmentParams?.getQueryParameter("error_description")
        if (error != null) {
            Toast.makeText(
                this,
                "Confirmation link expired or already used. Return to Cloud account and resend it.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        supabase.handleDeeplinks(intent)
    }

    private fun promptUnlock() {
        val intent = DeviceAuth.confirmIntent(this, getString(R.string.app_name), getString(R.string.unlock_prompt))
        // No screen lock on the phone any more: nothing to check against, so don't trap the owner out.
        if (intent == null) appLock.unlock() else authLauncher.launch(intent)
    }
}

private enum class EntryRoute { WELCOME, BUSINESS_AUTH, BUSINESS_SIGN_UP, ADMIN_AUTH, STORE_CHOICE, STORE_SETUP, CREATE_CLOUD_STORE, JOIN_STORE, ADMIN_PORTAL }
