package com.epiccrown.directstream.incoming

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.epiccrown.directstream.*
import com.epiccrown.directstream.R
import com.epiccrown.directstream.api.StreamSessionState
import kotlinx.coroutines.launch

/** Private notification entry point. Only a verified incoming request can open the main call UI. */
class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client =
            DirectStreamClient.currentOrNull()
                ?: run {
                    finish()
                    return
                }
        if (!client.isActivated) {
            finish()
            return
        }
        val id = intent.getStringExtra("requestId")
        val expiry = intent.getLongExtra("expiresAt", 0)
        if (
            id == null ||
                !id.matches(Regex("[a-zA-Z0-9_-]{16,100}")) ||
                expiry <= System.currentTimeMillis()
        ) {
            finish()
            return
        }
        val decline = intent.getStringExtra("callAction") == "decline"
        timber.log.Timber.tag("Push")
            .d("Incoming notification opened; action=%s", if (decline) "decline" else "review")
        val status =
            android.widget.TextView(this).apply {
                text =
                    getString(
                        if (decline) R.string.ds_incoming_declining_call
                        else R.string.ds_incoming_checking_incoming_call,
                    )
                gravity = android.view.Gravity.CENTER
            }
        setContentView(status)
        lifecycleScope.launch {
            if (!client.isActivated || DirectStreamClient.currentOrNull() !== client) {
                finish()
                return@launch
            }
            client.reviewIncoming(id)
            if (!client.isActivated || DirectStreamClient.currentOrNull() !== client) {
                finish()
                return@launch
            }
            val request =
                (client.state.value.session as? StreamSessionState.IncomingRequest)?.request
            if (request?.requestId != id || !IncomingCallNotifications.valid(request)) {
                timber.log.Timber.tag("Push")
                    .i("Incoming notification is stale; not opening call screen")
                IncomingCallNotifications.cancel(this@IncomingCallActivity, id)
                finish()
                return@launch
            }
            setShowWhenLocked(!decline)
            setTurnScreenOn(!decline)
            if (decline) {
                client.decline(id)
                if (client.state.value.session !is StreamSessionState.IncomingRequest)
                    IncomingCallNotifications.cancel(this@IncomingCallActivity, id)
                else
                    startActivity(
                        client
                            .returnIntent()
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            )
                            .putExtra("requestId", id),
                    )
            } else {
                timber.log.Timber.tag("Push")
                    .i("Validated incoming notification; opening host call screen")
                startActivity(
                    client
                        .returnIntent()
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                        .putExtra("requestId", id),
                )
            }
            finish()
        }
    }

    override fun onDestroy() {
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        super.onDestroy()
    }
}
