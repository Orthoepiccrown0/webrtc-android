package com.epiccrown.directstream.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.epiccrown.directstream.DirectStreamClient

/**
 * Demo navigation with Home, QR, recents, installation guide and an offline screen gallery.
 * Production hosts integrate sdk-ui and own their addressing/user experience instead.
 */
@Composable
fun DirectStreamShowcase(
    client: DirectStreamClient,
    initialCode: String? = null,
    notificationRequestId: String? = null,
    notificationEvent: Int = 0,
    returnToCallEvent: Int = 0,
    unlockForCall: ((Boolean) -> Unit) -> Unit = { it(true) },
    lockScreenOnly: Boolean = false,
    sampleVersion: String = "—",
    scan: (((String) -> Unit) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val viewModel: PairingViewModel =
        viewModel(
            key = "direct-stream-${System.identityHashCode(client)}",
            factory =
                object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(
                        modelClass: Class<T>,
                    ): T {
                        @Suppress("UNCHECKED_CAST")
                        return PairingViewModel(
                            ClientShowcaseRepository(context, client),
                            null,
                            CodecSettingsStore(context)::save,
                        )
                            as T
                    }
                },
        )
    val setup by client.setup.collectAsStateWithLifecycle()
    PairingScreen(
        viewModel = viewModel,
        rtc = client.video,
        initialCode = initialCode,
        notificationRequestId = notificationRequestId,
        notificationEvent = notificationEvent,
        returnToCallEvent = returnToCallEvent,
        unlockForCall = unlockForCall,
        lockScreenOnly = lockScreenOnly,
        setup = setup,
        sampleVersion = sampleVersion,
        scannerAvailable = scan != null,
        scan = { completed -> scan?.invoke(completed) },
    )
}
