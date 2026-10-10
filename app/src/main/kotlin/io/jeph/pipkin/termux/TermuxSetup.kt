package io.jeph.pipkin.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * One-paste Termux preparation: storage access, Node.js, allow-external-apps, then back to Pipkin.
 * Packages are upgraded before Node.js is installed: a fresh F-Droid bootstrap ships an older openssl that the
 * current nodejs package cannot link against ("CANNOT LINK EXECUTABLE node"). `yes` answers dpkg's conffile prompts.
 */
internal object TermuxSetup {
  const val COMMAND = "[ -d ~/storage/shared ] || termux-setup-storage; " +
    "node -v >/dev/null 2>&1 || { yes | pkg upgrade -y && pkg install -y nodejs; }; " +
    "mkdir -p ~/.termux && { grep -qx 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null " +
    "|| echo 'allow-external-apps=true' >> ~/.termux/termux.properties; } && termux-reload-settings " +
    "&& echo 'Pipkin setup done' && am start -n io.jeph.pipkin/.MainActivity >/dev/null"

  /** Puts [command] on the clipboard and opens Termux so the user can paste it. */
  fun copyAndOpen(context: Context, command: String = COMMAND) {
    context.getSystemService(ClipboardManager::class.java)
      .setPrimaryClip(ClipData.newPlainText("Termux command", command))
    TermuxInstall.openTermux(context)
  }
}
