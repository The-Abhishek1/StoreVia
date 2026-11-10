package com.xcloak.storevia

import android.os.Bundle
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.WindowCompat
import com.xcloak.storevia.core.access.StaffSession
import com.xcloak.storevia.core.security.AppLock
import com.xcloak.storevia.core.security.DeviceAuth
import com.xcloak.storevia.core.ui.LocalActor
import com.xcloak.storevia.core.ui.LockScreen
import com.xcloak.storevia.core.ui.StoreviaNavHost
import com.xcloak.storevia.core.ui.theme.StoreviaTheme
import com.xcloak.storevia.core.ui.theme.ThemeMode
import com.xcloak.storevia.data.AppBootstrap
import com.xcloak.storevia.data.ads.AdsManager
import com.xcloak.storevia.data.cloud.CloudAccountRepository
import com.xcloak.storevia.data.cloud.CloudStoreOption
import com.xcloak.storevia.data.cloud.CloudSyncCoordinator
import com.xcloak.storevia.data.cloud.CloudSyncStatus
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.feature.admin.PlatformAdminScreen
import com.xcloak.storevia.feature.cloud.CloudAccountScreen
import com.xcloak.storevia.feature.cloud.CloudConnectionError
import com.xcloak.storevia.feature.entry.EntryWelcomeScreen
import com.xcloak.storevia.feature.entry.OnlineCloudChoiceScreen
import com.xcloak.storevia.feature.entry.StoreChoiceScreen
import com.xcloak.storevia.feature.onboarding.OnboardingScreen
import com.xcloak.storevia.feature.staff.StaffLoginScreen
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var session: StaffSession
    @Inject lateinit var supabase: SupabaseClient
    @Inject lateinit var cloudAccount: CloudAccountRepository
    @Inject lateinit var cloudSyncCoordinator: CloudSyncCoordinator
    @Inject lateinit var bootstrap: AppBootstrap
    @Inject lateinit var adsManager: AdsManager

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) appLock.unlock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adsManager.requestConsent(this)
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
            val cloudSyncStatus by cloudSyncCoordinator.status.collectAsStateWithLifecycle()
            val canRequestAds by adsManager.canRequestAds.collectAsStateWithLifecycle()
            val privacyOptionsRequired by adsManager.privacyOptionsRequired.collectAsStateWithLifecycle()
            StoreviaTheme(settings.themeMode) {
                val darkTheme = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                SideEffect {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                val locked by appLock.locked.collectAsStateWithLifecycle()
                val actor by session.actorState.collectAsStateWithLifecycle()
                val needsLogin by session.needsLogin.collectAsStateWithLifecycle()
                var entryRoute by rememberSaveable { mutableStateOf(EntryRoute.WELCOME) }
                val authenticated = authStatus is SessionStatus.Authenticated
                var authStatusResolved by rememberSaveable { mutableStateOf(false) }
                var platformAdminCheckComplete by rememberSaveable { mutableStateOf(false) }
                var platformAdminSession by rememberSaveable { mutableStateOf(false) }
                var platformAdminCheckError by rememberSaveable { mutableStateOf<String?>(null) }
                var platformAdminCheckAttempt by rememberSaveable { mutableStateOf(0) }
                var existingStoreConnectionError by rememberSaveable { mutableStateOf<String?>(null) }
                var availableCloudStores by remember { mutableStateOf(emptyList<CloudStoreOption>()) }
                var resolvingCloudAccount by remember { mutableStateOf(false) }
                var completingCloudSignup by remember { mutableStateOf(false) }
                var cloudSignupError by rememberSaveable { mutableStateOf<String?>(null) }
                var cloudWorkspaceReady by rememberSaveable { mutableStateOf(false) }
                var cloudWorkspaceStoreId by rememberSaveable { mutableStateOf<String?>(null) }
                var storeSyncedDuringConnection by remember { mutableStateOf<String?>(null) }
                var adsEnabled by remember { mutableStateOf(false) }
                var adsEligibilityRefresh by remember { mutableStateOf(0) }

                androidx.compose.runtime.LaunchedEffect(authStatus) {
                    if (authStatus !is SessionStatus.Initializing) authStatusResolved = true
                }

                androidx.compose.runtime.LaunchedEffect(authenticated, platformAdminCheckAttempt) {
                    if (shouldCheckPlatformAdminAccess(authenticated)) {
                        platformAdminCheckComplete = false
                        platformAdminCheckError = null
                        try {
                            val isPlatformAdmin = cloudAccount.checkPlatformAdminAccess()
                            platformAdminSession = isPlatformAdmin
                            when {
                                isPlatformAdmin -> {
                                    prefs.update { copy(storageMode = null, cloudSignupPending = false) }
                                    entryRoute = EntryRoute.ADMIN_PORTAL
                                }
                                shouldResumeCloudSignup(
                                    authenticated = authenticated,
                                    platformAdmin = isPlatformAdmin,
                                    signupPending = settings.cloudSignupPending,
                                    selectedStoreId = settings.cloudStoreId,
                                ) -> entryRoute = EntryRoute.CREATE_CLOUD_STORE
                                entryRoute == EntryRoute.BUSINESS_SIGN_UP ||
                                    entryRoute == EntryRoute.BUSINESS_SIGN_UP_DETAILS ->
                                    entryRoute = EntryRoute.CREATE_CLOUD_STORE
                                entryRoute == EntryRoute.ADMIN_PORTAL -> entryRoute = EntryRoute.WELCOME
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            platformAdminSession = false
                            platformAdminCheckError = CloudConnectionError.message(e)
                        } finally {
                            platformAdminCheckComplete = true
                        }
                    } else {
                        platformAdminSession = false
                        platformAdminCheckComplete = true
                        if (entryRoute == EntryRoute.ADMIN_PORTAL) entryRoute = EntryRoute.WELCOME
                    }
                }

                androidx.compose.runtime.LaunchedEffect(
                    authenticated,
                    platformAdminCheckComplete,
                    platformAdminSession,
                    platformAdminCheckError,
                    settings.cloudSignupPending,
                ) {
                    if (!authenticated ||
                        !platformAdminCheckComplete ||
                        platformAdminSession ||
                        platformAdminCheckError != null ||
                        !settings.cloudSignupPending
                    ) {
                        return@LaunchedEffect
                    }

                    completingCloudSignup = true
                    cloudSignupError = null
                    try {
                        cloudAccount.completeCloudSignup()
                        storeSyncedDuringConnection = prefs.settings.value.cloudStoreId
                        availableCloudStores = emptyList()
                        entryRoute = EntryRoute.WELCOME
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        cloudSignupError = CloudConnectionError.message(e)
                        entryRoute = EntryRoute.WELCOME
                    } finally {
                        completingCloudSignup = false
                    }
                }

                androidx.compose.runtime.LaunchedEffect(
                    authenticated,
                    settings.storageMode,
                    settings.cloudSignupPending,
                    entryRoute,
                    platformAdminCheckComplete,
                ) {
                    if (!authenticated ||
                        settings.storageMode != StorageMode.CLOUD ||
                        !platformAdminCheckComplete ||
                        platformAdminSession ||
                        platformAdminCheckError != null
                    ) {
                        return@LaunchedEffect
                    }
                    val shouldResolveStore = entryRoute == EntryRoute.BUSINESS_AUTH ||
                        entryRoute == EntryRoute.ONLINE_CLOUD ||
                        (entryRoute == EntryRoute.WELCOME &&
                            settings.cloudStoreId == null &&
                            !settings.cloudSignupPending)
                    if (!shouldResolveStore) return@LaunchedEffect

                    resolvingCloudAccount = true
                    try {
                        existingStoreConnectionError = null
                        entryRoute = if (cloudAccount.connectExistingStore() != null) {
                            storeSyncedDuringConnection = prefs.settings.value.cloudStoreId
                            availableCloudStores = emptyList()
                            EntryRoute.WELCOME
                        } else {
                            availableCloudStores = cloudAccount.availableCloudStores()
                            EntryRoute.STORE_CHOICE
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        existingStoreConnectionError = CloudConnectionError.message(e)
                        availableCloudStores = emptyList()
                        entryRoute = EntryRoute.STORE_CHOICE
                    } finally {
                        resolvingCloudAccount = false
                    }
                }

                androidx.compose.runtime.LaunchedEffect(
                    authenticated,
                    settings.cloudStoreId,
                    settings.storageMode,
                    platformAdminCheckComplete,
                    platformAdminSession,
                    platformAdminCheckError,
                ) {
                    if (platformAdminCheckComplete &&
                        !platformAdminSession &&
                        platformAdminCheckError == null &&
                        settings.storageMode == StorageMode.CLOUD &&
                        authenticated &&
                        settings.cloudStoreId != null
                    ) {
                        if (storeSyncedDuringConnection == settings.cloudStoreId) {
                            storeSyncedDuringConnection = null
                            cloudWorkspaceReady = true
                            cloudWorkspaceStoreId = settings.cloudStoreId
                            return@LaunchedEffect
                        }
                        cloudWorkspaceReady = cloudSyncCoordinator.synchronize() != null
                        cloudWorkspaceStoreId =
                            if (cloudWorkspaceReady) settings.cloudStoreId else null
                    } else if (
                        settings.storageMode != StorageMode.CLOUD ||
                        (authStatus !is SessionStatus.Initializing && !authenticated) ||
                        platformAdminSession ||
                        platformAdminCheckError != null
                    ) {
                        cloudWorkspaceReady = false
                        cloudWorkspaceStoreId = null
                    }
                }

                val showRetailApp = platformAdminCheckComplete &&
                    !platformAdminSession &&
                    platformAdminCheckError == null &&
                    entryRoute != EntryRoute.ADMIN_PORTAL &&
                    (settings.storageMode == StorageMode.LOCAL && settings.onboardingDone && !settings.cloudSignupPending ||
                        (settings.storageMode == StorageMode.CLOUD &&
                            authenticated &&
                            cloudWorkspaceReady &&
                            cloudWorkspaceStoreId == settings.cloudStoreId))
                val retainCloudNavHost = settings.storageMode == StorageMode.CLOUD &&
                    settings.cloudStoreId != null &&
                    (authenticated || authStatus is SessionStatus.Initializing) &&
                    !platformAdminSession &&
                    platformAdminCheckError == null &&
                    entryRoute != EntryRoute.ADMIN_PORTAL

                androidx.compose.runtime.LaunchedEffect(
                    showRetailApp,
                    authenticated,
                    settings.storageMode,
                    settings.cloudStoreId,
                    adsEligibilityRefresh,
                ) {
                    if (!showRetailApp) {
                        adsEnabled = false
                        return@LaunchedEffect
                    }
                    adsEnabled = when (settings.storageMode) {
                        StorageMode.LOCAL ->
                            adsManager.localRewardExpiresAt() <= System.currentTimeMillis()
                        StorageMode.CLOUD -> {
                            if (!authenticated) false
                            else try {
                                cloudAccount.planSnapshot()?.hasActiveProAccess() != true
                            } catch (error: Exception) {
                                Log.e("MainActivity", "Could not verify Cloud ad eligibility", error)
                                false
                            }
                        }
                        null -> false
                    }
                }
                androidx.compose.runtime.LaunchedEffect(showRetailApp, adsEnabled, canRequestAds, locked) {
                    if (showRetailApp && adsEnabled && canRequestAds && !locked) {
                        delay(1_500)
                        adsManager.showAppOpenIfReady(this@MainActivity)
                    }
                }
                androidx.compose.runtime.LaunchedEffect(settings.storageMode, settings.onboardingDone) {
                    if (settings.storageMode == StorageMode.LOCAL && !settings.onboardingDone) {
                        entryRoute = EntryRoute.OFFLINE_SETUP
                    }
                }
                val retryCloudWorkspaceSync: () -> Unit = {
                    lifecycleScope.launch {
                        cloudWorkspaceReady = false
                        if (prefs.settings.value.cloudSignupPending) {
                            completingCloudSignup = true
                            cloudSignupError = null
                            try {
                                cloudAccount.completeCloudSignup()
                                storeSyncedDuringConnection = prefs.settings.value.cloudStoreId
                                cloudWorkspaceReady = true
                                cloudWorkspaceStoreId = prefs.settings.value.cloudStoreId
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                cloudSignupError = CloudConnectionError.message(e)
                            } finally {
                                completingCloudSignup = false
                            }
                        } else {
                            cloudWorkspaceReady = cloudSyncCoordinator.synchronize() != null
                            cloudWorkspaceStoreId =
                                if (cloudWorkspaceReady) prefs.settings.value.cloudStoreId else null
                        }
                    }
                }
                val refreshCloudStoreAccess: () -> Unit = {
                    lifecycleScope.launch {
                        platformAdminCheckComplete = false
                        try {
                            existingStoreConnectionError = null
                            if (cloudAccount.connectExistingStore() != null) {
                                storeSyncedDuringConnection = prefs.settings.value.cloudStoreId
                                availableCloudStores = emptyList()
                                entryRoute = EntryRoute.WELCOME
                            } else {
                                availableCloudStores = cloudAccount.availableCloudStores()
                                entryRoute = EntryRoute.STORE_CHOICE
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            existingStoreConnectionError = CloudConnectionError.message(e)
                            availableCloudStores = emptyList()
                            entryRoute = EntryRoute.STORE_CHOICE
                        } finally {
                            platformAdminCheckComplete = true
                        }
                    }
                }
                val leaveAdmin: () -> Unit = {
                    lifecycleScope.launch {
                        cloudAccount.signOut()
                        entryRoute = EntryRoute.WELCOME
                    }
                }
                CompositionLocalProvider(LocalActor provides actor) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            showRetailApp || retainCloudNavHost -> {
                                StoreviaNavHost(
                                    localOnly = settings.storageMode == StorageMode.LOCAL,
                                    cloudSyncStatus = cloudSyncStatus,
                                    onRetryCloudSync = cloudSyncCoordinator::retry,
                                    adsManager = adsManager,
                                    showAds = adsEnabled,
                                    onAdsEligibilityChanged = { adsEnabled = it },
                                    privacyOptionsRequired = privacyOptionsRequired,
                                    onPrivacyOptions = { adsManager.showPrivacyOptions(this@MainActivity) },
                                    onExitLocalOnly = {
                                        lifecycleScope.launch {
                                            cloudAccount.signOut()
                                            prefs.update {
                                                copy(storageMode = null, cloudStoreId = null, cloudRole = null)
                                            }
                                            entryRoute = EntryRoute.WELCOME
                                        }
                                    },
                                )
                                if (!showRetailApp) {
                                    when {
                                        authStatus is SessionStatus.Initializing ||
                                            authenticated &&
                                            (!platformAdminCheckComplete || resolvingCloudAccount || completingCloudSignup) ->
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clickable(onClick = {}),
                                                color = MaterialTheme.colorScheme.background,
                                            ) {
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    CircularProgressIndicator()
                                                }
                                            }
                                        authenticated &&
                                            platformAdminCheckComplete &&
                                            !platformAdminSession &&
                                            platformAdminCheckError == null &&
                                            entryRoute == EntryRoute.WELCOME &&
                                            !cloudWorkspaceReady ->
                                            CloudWorkspaceGate(
                                                status = cloudSyncStatus,
                                                signedIn = authenticated,
                                                error = cloudSignupError,
                                                onRetry = retryCloudWorkspaceSync,
                                                onSignIn = { entryRoute = EntryRoute.BUSINESS_AUTH },
                                            )
                                        else ->
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clickable(onClick = {}),
                                                color = MaterialTheme.colorScheme.background,
                                            ) {
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    CircularProgressIndicator()
                                                }
                                            }
                                        }
                                } else if (needsLogin) {
                                    StaffLoginScreen()
                                }
                            }
                            authStatus is SessionStatus.Initializing && !authStatusResolved -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            authenticated && (!platformAdminCheckComplete || resolvingCloudAccount || completingCloudSignup) ->
                                CircularProgressIndicator(Modifier.align(Alignment.Center))
                            authenticated && platformAdminCheckError != null ->
                                PlatformAdminAccessGate(
                                    error = platformAdminCheckError.orEmpty(),
                                    onRetry = { platformAdminCheckAttempt += 1 },
                                    onSignOut = {
                                        lifecycleScope.launch {
                                            cloudAccount.signOut()
                                            entryRoute = EntryRoute.WELCOME
                                        }
                                    },
                                )
                            authenticated && entryRoute == EntryRoute.ADMIN_PORTAL -> {
                                PlatformAdminScreen(onSignOut = leaveAdmin)
                            }
                            (settings.storageMode == StorageMode.CLOUD || settings.cloudSignupPending) &&
                                entryRoute == EntryRoute.WELCOME &&
                                authenticated &&
                                !cloudWorkspaceReady -> {
                                CloudWorkspaceGate(
                                    status = cloudSyncStatus,
                                    signedIn = authenticated,
                                    error = cloudSignupError,
                                    onRetry = retryCloudWorkspaceSync,
                                    onSignIn = { entryRoute = EntryRoute.BUSINESS_AUTH },
                                )
                            }
                            !authenticated && entryRoute == EntryRoute.OFFLINE_SETUP -> {
                                OnboardingScreen(
                                    onCancel = {
                                        prefs.update { copy(storageMode = null) }
                                        entryRoute = EntryRoute.WELCOME
                                    },
                                    onFinish = {
                                        prefs.update { copy(storageMode = StorageMode.LOCAL) }
                                        entryRoute = EntryRoute.WELCOME
                                    },
                                    onImport = {},
                                    onAddProduct = {},
                                    localSetupOnly = true,
                                )
                            }
                            authenticated && entryRoute == EntryRoute.STORE_CHOICE -> {
                                StoreChoiceScreen(
                                    stores = availableCloudStores,
                                    onSelectStore = { storeId ->
                                        lifecycleScope.launch {
                                            platformAdminCheckComplete = false
                                            try {
                                                existingStoreConnectionError = null
                                                cloudAccount.connectSelectedStore(storeId)
                                                storeSyncedDuringConnection = prefs.settings.value.cloudStoreId
                                                availableCloudStores = emptyList()
                                                entryRoute = EntryRoute.WELCOME
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                existingStoreConnectionError = CloudConnectionError.message(e)
                                            } finally {
                                                platformAdminCheckComplete = true
                                            }
                                        }
                                    },
                                    connectionError = existingStoreConnectionError,
                                    onRetryExistingStore = refreshCloudStoreAccess,
                                    onSignOut = {
                                        lifecycleScope.launch {
                                            cloudAccount.signOut()
                                            availableCloudStores = emptyList()
                                            entryRoute = EntryRoute.WELCOME
                                        }
                                    },
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
                                    onCancel = { entryRoute = EntryRoute.STORE_CHOICE },
                                )
                            }
                            authenticated && entryRoute == EntryRoute.CREATE_CLOUD_STORE -> {
                                CloudAccountScreen(
                                    onBack = { entryRoute = EntryRoute.STORE_CHOICE },
                                    createOnly = true,
                                )
                            }
                            authenticated && entryRoute == EntryRoute.JOIN_STORE -> {
                                CloudAccountScreen(
                                    onBack = { entryRoute = EntryRoute.STORE_CHOICE },
                                    joinOnly = true,
                                )
                            }
                            authenticated -> {
                                StoreChoiceScreen(
                                    stores = availableCloudStores,
                                    connectionError = existingStoreConnectionError,
                                    onRetryExistingStore = refreshCloudStoreAccess,
                                    onSelectStore = { storeId ->
                                        lifecycleScope.launch {
                                            try {
                                                existingStoreConnectionError = null
                                                cloudAccount.connectSelectedStore(storeId)
                                                availableCloudStores = emptyList()
                                                entryRoute = EntryRoute.WELCOME
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                existingStoreConnectionError = CloudConnectionError.message(e)
                                            }
                                        }
                                    },
                                    onSignOut = {
                                        lifecycleScope.launch {
                                            cloudAccount.signOut()
                                            availableCloudStores = emptyList()
                                            entryRoute = EntryRoute.WELCOME
                                        }
                                    },
                                    onCreate = { entryRoute = if (settings.onboardingDone) EntryRoute.CREATE_CLOUD_STORE else EntryRoute.STORE_SETUP },
                                    onJoin = { entryRoute = EntryRoute.JOIN_STORE },
                                )
                            }
                            entryRoute == EntryRoute.ONLINE_CLOUD ||
                                (settings.storageMode == StorageMode.CLOUD &&
                                    entryRoute == EntryRoute.WELCOME &&
                                    !authenticated) -> {
                                OnlineCloudChoiceScreen(
                                    onBack = {
                                        prefs.update { copy(storageMode = null, cloudSignupPending = false) }
                                        entryRoute = EntryRoute.WELCOME
                                    },
                                    onSignIn = { entryRoute = EntryRoute.BUSINESS_AUTH },
                                    onSignUp = {
                                        prefs.update { copy(cloudSignupPending = true) }
                                        entryRoute = EntryRoute.BUSINESS_SIGN_UP_DETAILS
                                    },
                                )
                            }
                            entryRoute == EntryRoute.BUSINESS_SIGN_UP_DETAILS -> {
                                OnboardingScreen(
                                    onFinish = { entryRoute = EntryRoute.BUSINESS_SIGN_UP },
                                    onImport = { entryRoute = EntryRoute.BUSINESS_SIGN_UP },
                                    onAddProduct = { entryRoute = EntryRoute.BUSINESS_SIGN_UP },
                                    cloudConnectRequired = true,
                                    clearRegistrationDetailsOnStart = true,
                                    onCancel = {
                                        prefs.update { copy(cloudSignupPending = false) }
                                        entryRoute = EntryRoute.ONLINE_CLOUD
                                    },
                                )
                            }
                            entryRoute == EntryRoute.BUSINESS_AUTH || entryRoute == EntryRoute.BUSINESS_SIGN_UP -> {
                                CloudAccountScreen(
                                    onBack = {
                                        prefs.update { copy(cloudSignupPending = false) }
                                        entryRoute = EntryRoute.ONLINE_CLOUD
                                    },
                                    lockCreateAccount = entryRoute == EntryRoute.BUSINESS_AUTH || entryRoute == EntryRoute.BUSINESS_SIGN_UP,
                                    initialCreateAccount = entryRoute == EntryRoute.BUSINESS_SIGN_UP,
                                )
                            }
                            else -> EntryWelcomeScreen(
                                onOnlineCloud = {
                                    prefs.update { copy(storageMode = StorageMode.CLOUD) }
                                    entryRoute = EntryRoute.ONLINE_CLOUD
                                },
                                onWorkOffline = {
                                    if (settings.onboardingDone) {
                                        prefs.update { copy(storageMode = StorageMode.LOCAL) }
                                    } else {
                                        prefs.update { copy(storageMode = StorageMode.LOCAL) }
                                        entryRoute = EntryRoute.OFFLINE_SETUP
                                    }
                                },
                            )
                        }
                        if (locked) LockScreen(onUnlock = ::promptUnlock)   // the phone's own lock sits on top of everything
                    }
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

private enum class EntryRoute { WELCOME, ONLINE_CLOUD, BUSINESS_SIGN_UP_DETAILS, BUSINESS_AUTH, BUSINESS_SIGN_UP, OFFLINE_SETUP, STORE_CHOICE, STORE_SETUP, CREATE_CLOUD_STORE, JOIN_STORE, ADMIN_PORTAL }

internal fun shouldCheckPlatformAdminAccess(authenticated: Boolean): Boolean = authenticated

internal fun shouldResumeCloudSignup(
    authenticated: Boolean,
    platformAdmin: Boolean,
    signupPending: Boolean,
    selectedStoreId: String?,
): Boolean = authenticated && !platformAdmin && signupPending && selectedStoreId == null

@androidx.compose.runtime.Composable
private fun CloudWorkspaceGate(
    status: CloudSyncStatus,
    signedIn: Boolean,
    error: String? = null,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = {}),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.cloud_workspace_sync_required), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.cloud_workspace_sync_required_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            when {
                !signedIn -> OutlinedButton(onClick = onSignIn) {
                    Text(stringResource(R.string.cloud_mode_sign_in))
                }
                status == CloudSyncStatus.FAILED || error != null -> Button(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PlatformAdminAccessGate(
    error: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.platform_admin_access_check_title), style = MaterialTheme.typography.headlineSmall)
        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
        OutlinedButton(onClick = onSignOut) {
            Text(stringResource(R.string.cloud_sign_out))
        }
    }
}
