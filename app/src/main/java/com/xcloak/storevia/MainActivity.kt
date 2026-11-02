package com.xcloak.storevia

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.feature.staff.StaffLoginScreen
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

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) appLock.unlock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // With the lock on, hide the app from screenshots and the recent-apps preview.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                prefs.settings.map { it.lockEnabled }.distinctUntilChanged().collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            StoreviaTheme {
                val locked by appLock.locked.collectAsStateWithLifecycle()
                val actor by session.actorState.collectAsStateWithLifecycle()
                val needsLogin by session.needsLogin.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalActor provides actor) {
                    Box(Modifier.fillMaxSize()) {
                        StoreviaNavHost()
                        if (needsLogin) StaffLoginScreen()          // staff accounts exist and nobody is signed in
                        if (locked) LockScreen(onUnlock = ::promptUnlock)   // the phone's own lock sits on top of everything
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); appLock.onStart(); session.onStart() }
    override fun onStop() { super.onStop(); appLock.onStop(); session.onStop() }

    private fun promptUnlock() {
        val intent = DeviceAuth.confirmIntent(this, getString(R.string.app_name), getString(R.string.unlock_prompt))
        // No screen lock on the phone any more: nothing to check against, so don't trap the owner out.
        if (intent == null) appLock.unlock() else authLauncher.launch(intent)
    }
}
