package io.jeph.pipkin.host

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import io.jeph.pipkin.termux.TermuxInstall

/**
 * Opens Termux in a new session for [TermuxWaker] and finishes at once, without a window. An activity of Pipkin's
 * own starting and finishing is what lets Android switch back to Pipkin for a few seconds after Termux covers it.
 */
class TermuxWakeActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      TermuxInstall.openTermuxNewSession(this)
    } catch (_: ActivityNotFoundException) {
      // Termux was uninstalled since the wake began; the wake times out and Pipkin reports it.
    }
    finish()
  }
}
