package com.epiccrown.directstream.showcase

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.incoming.IncomingCallNotifications
import com.epiccrown.directstream.showcase.ui.DirectStreamShowcase
import com.epiccrown.directstream.showcase.ui.theme.DirectStreamShowcaseTheme
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var client: DirectStreamClient
    private var pairingLink by mutableStateOf<String?>(null)
    private var notificationRequest by mutableStateOf<String?>(null)
    private var notificationEvent by mutableIntStateOf(0)
    private var incomingLockPresentation by mutableStateOf(false)
    private var returnToCallEvent by mutableIntStateOf(0)

    private fun readIntent(intent: Intent) {
        pairingLink = intent.dataString
        notificationRequest = intent.getStringExtra("requestId")
        if (notificationRequest != null) notificationEvent++
        if (intent.getBooleanExtra("returnToCall", false)) returnToCallEvent++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        updateIncomingWindow()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.state.collect { state ->
                    updateIncomingWindow()
                    if (state.ready && state.session !is StreamSessionState.IncomingRequest)
                        IncomingCallNotifications.cancel(this@MainActivity)
                }
            }
        }
        enableEdgeToEdge()
        setContent {
            DirectStreamShowcaseTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    DirectStreamShowcase(
                        client,
                        pairingLink,
                        notificationRequest,
                        notificationEvent,
                        returnToCallEvent,
                        unlockForCall = ::unlockForCall,
                        lockScreenOnly = incomingLockPresentation,
                        sampleVersion = BuildConfig.VERSION_NAME,
                    ) { completed ->
                        Timber.tag("PairingUi").d("Opening QR scanner")
                        GmsBarcodeScanning.getClient(this@MainActivity)
                            .startScan()
                            .addOnSuccessListener { barcode ->
                                Timber.tag("PairingUi").d("QR scan completed")
                                completed(barcode.rawValue.orEmpty())
                            }
                            .addOnCanceledListener {
                                Timber.tag("PairingUi").d("QR scan cancelled")
                            }
                            .addOnFailureListener { error ->
                                Timber.tag("PairingUi")
                                    .w("QR scanner failed (%s)", error.javaClass.simpleName)
                                completed("")
                            }
                    }
                }
            }
        }
    }

    private var showingIncomingLockScreen = false

    private fun updateIncomingWindow() {
        val request = (client.state.value.session as? StreamSessionState.IncomingRequest)?.request
        val incoming = request != null && IncomingCallNotifications.valid(request)
        val finishedOnLockScreen =
            showingIncomingLockScreen &&
                !incoming &&
                getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                (client.state.value.session is StreamSessionState.Idle ||
                    client.state.value.session is StreamSessionState.Failed)
        incomingLockPresentation =
            incoming && getSystemService(KeyguardManager::class.java).isKeyguardLocked
        showingIncomingLockScreen = incoming
        setShowWhenLocked(incoming)
        setTurnScreenOn(incoming)
        if (incoming) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (finishedOnLockScreen) finish()
    }

    private fun unlockForCall(completed: (Boolean) -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            completed(true)
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    incomingLockPresentation = false
                    completed(true)
                }

                override fun onDismissCancelled() {
                    completed(false)
                }

                override fun onDismissError() {
                    completed(false)
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        updateIncomingWindow()
    }

    override fun onStart() {
        super.onStart()
        Timber.tag("App").d("Activity visible")
        client.setVisible(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) client.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        client.setVisible(true)
    }

    override fun onStop() {
        Timber.tag("App").d("Activity stopped; changingConfiguration=%s", isChangingConfigurations)
        if (!isChangingConfigurations) client.setVisible(false)
        super.onStop()
    }
}
