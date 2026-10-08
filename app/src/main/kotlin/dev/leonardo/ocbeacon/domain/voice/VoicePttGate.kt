package dev.leonardo.ocbeacon.domain.voice

/** Outcome of gating a PTT press against the RECORD_AUDIO runtime permission. */
enum class VoicePttAction { REQUEST_PERMISSION, PRESS }

/**
 * Pure PTT permission gating: without RECORD_AUDIO the press must become a
 * runtime permission request first; with the permission, every connection
 * state maps to PRESS — reconnection (Disconnected / MovedToAnotherSurface)
 * and hold-through-connecting are the repository's job.
 */
object VoicePttGate {
    fun resolve(permissionGranted: Boolean): VoicePttAction =
        if (permissionGranted) VoicePttAction.PRESS else VoicePttAction.REQUEST_PERMISSION
}
