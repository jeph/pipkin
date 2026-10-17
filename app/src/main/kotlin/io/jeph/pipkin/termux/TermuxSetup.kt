package io.jeph.pipkin.termux

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.host.HostLauncher
import io.jeph.pipkin.host.PlaySetup

/**
 * One-paste Termux preparation. Every part checks before it acts, so pasting a command again after a failure (no
 * network, a package mirror outage, a closed Termux) finishes the setup instead of breaking it.
 */
internal object TermuxSetup {
  /**
   * F-Droid: Node.js 22.19+, allow-external-apps, then back to Pipkin. `dpkg --configure -a` finishes an upgrade an
   * earlier paste left half done. Packages are upgraded before Node.js is installed: a fresh F-Droid bootstrap ships an
   * older openssl that the current nodejs package cannot link against ("CANNOT LINK EXECUTABLE node"), and a Node.js
   * that can't start fails the version check the same way. `yes` answers dpkg's conffile prompts. A failed install
   * stops before "done", so Pipkin isn't reopened as if setup had worked. Storage access is its own setup step.
   */
  val FDROID_COMMAND = inBash(
    "{ node -e \"const [a,b]=process.versions.node.split(/[.]/).map(Number);" +
      "process.exit(a>22||(a===22&&b>=19)?0:1)\" " +
      "2>/dev/null || { dpkg --configure -a; yes | pkg upgrade -y && pkg install -y nodejs; }; } && " +
      "mkdir -p ~/.termux && { grep -qx allow-external-apps=true ~/.termux/termux.properties 2>/dev/null " +
      "|| echo allow-external-apps=true >> ~/.termux/termux.properties; } && termux-reload-settings " +
      "&& echo \"Pipkin setup done\" && am start -n io.jeph.pipkin/.MainActivity >/dev/null " +
      "|| echo \"Pipkin setup did not finish. Check your internet connection, then paste the command again.\"",
  )

  /**
   * F-Droid: Termux's own storage prompt, shown in front by the session [requestFDroidStorage] opens. The broadcast in
   * `termux-setup-storage` only reaches a Termux on screen, so it waits a moment for Termux to open; its `y` answers
   * the question about rebuilding an existing `~/storage`, which holds only links. Pipkin comes back once access is
   * granted, or after a minute; the line it prints is what a user who declined sees meanwhile.
   */
  private const val FDROID_STORAGE_COMMAND = "echo \"Pipkin: waiting for storage access. Switch back to Pipkin " +
    "any time.\"; sleep 1; echo y | termux-setup-storage >/dev/null; " +
    "for i in $(seq 60); do [ -w ~/storage/shared ] && break; sleep 1; done; " +
    "am start -n io.jeph.pipkin/.MainActivity >/dev/null"

  /** Opens Termux on a session that shows its storage prompt; F-Droid's build only, as it needs RUN_COMMAND. */
  fun requestFDroidStorage(context: Context) {
    HostLauncher.runInTerminal(context, "storage", FDROID_STORAGE_COMMAND)
    TermuxInstall.openTermux(context)
  }

  /**
   * A command that runs [script] with bash, so it works in whichever shell Termux runs, fish included. [script] has
   * no single quotes or backslashes: fish reads `\'` and `\\` inside single quotes as escapes and POSIX shells don't,
   * so neither could be written the same way for both.
   */
  fun inBash(script: String): String = "bash -c '$script'"

  /**
   * The setup command for the installed build. The Play build's carries a fresh single-use pairing token, so ask for
   * it only when it's about to be copied; [keepAlive] keeps Pipkin serving its installer while the user is in Termux.
   */
  fun command(context: Context, keepAlive: KeepAlive): String =
    if (TermuxInstall.status(context).isPlay) PlaySetup.newCommand(context, keepAlive) else FDROID_COMMAND

  /** Puts [command] on the clipboard and opens Termux so the user can paste it. */
  fun copyAndOpen(context: Context, command: String) {
    copy(context, command)
    TermuxInstall.openTermux(context)
  }

  /**
   * Copies [command]; marked sensitive so Android 13+ doesn't preview it, since the Play build's carries a pairing
   * token.
   */
  fun copy(context: Context, command: String) {
    val clip = ClipData.newPlainText("Termux command", command)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
  }
}
