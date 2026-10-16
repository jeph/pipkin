package io.jeph.pipkin.termux

import android.content.Context
import androidx.core.content.edit
import io.jeph.pipkin.host.HelperPairing
import io.jeph.pipkin.host.HostFailure

/**
 * Remembers which installation of Termux Pipkin last reached, to notice when Termux was reinstalled or swapped between
 * its F-Droid and Play builds. Either wipes Termux's home folder: Pi, its sign-in and everything Pipkin set up there.
 */
internal object TermuxIdentity {
  private const val FILE = "termux_identity"
  private const val INSTALLATION = "installation"
  private const val CHANGED = "changed"

  /**
   * Throws [HostFailure.TermuxChanged] the first time a launch finds another installation than the one a host last
   * ran in. It also forgets the Play pairing, whose key went with the old installation, so later launches report
   * the step that's actually missing.
   */
  fun verify(context: Context) {
    if (detectChange(context)) throw HostFailure.TermuxChanged()
  }

  /** Records the installation a host just ran in; the setup it needed is in place again. Called on every launch. */
  fun remember(context: Context) {
    val current = TermuxInstall.installation(context) ?: return
    val prefs = prefs(context)
    if (prefs.getString(INSTALLATION, null) == current && !prefs.getBoolean(CHANGED, false)) return
    prefs.edit {
      putString(INSTALLATION, current)
      putBoolean(CHANGED, false)
    }
  }

  /** Termux changed since a host last ran, and no host has run in the new installation yet. */
  fun changed(context: Context): Boolean {
    detectChange(context)
    return prefs(context).getBoolean(CHANGED, false)
  }

  /** A host has run in the installed Termux, so the setup it needs was in place then. */
  fun reached(context: Context): Boolean {
    val current = TermuxInstall.installation(context) ?: return false
    detectChange(context)
    return prefs(context).getString(INSTALLATION, null) == current
  }

  /** True only on the check that first sees the new installation. */
  private fun detectChange(context: Context): Boolean {
    val current = TermuxInstall.installation(context) ?: return false
    val prefs = prefs(context)
    val known = prefs.getString(INSTALLATION, null)
    if (known == null || known == current) return false
    prefs.edit {
      remove(INSTALLATION)
      putBoolean(CHANGED, true)
    }
    HelperPairing.clear(context)
    return true
  }

  private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
