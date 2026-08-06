package com.svetlio.audiocast.core

/**
 * What this installed device does. Chosen at first launch, changeable in Settings.
 * One app, one role at a time.
 */
enum class Role {
    UNSET,
    SENDER,
    RECEIVER,
}
