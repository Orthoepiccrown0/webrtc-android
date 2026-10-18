package com.epiccrown.directstream.showcase.ui

/**
 * Permission dialogs retain only the intended action; media starts through the ViewModel command.
 */
internal enum class PendingCallAction {
    CALL,
    ACCEPT,
}
