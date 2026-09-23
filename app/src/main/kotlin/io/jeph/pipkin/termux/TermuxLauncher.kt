package io.jeph.pipkin.termux

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Starts the supervisor daemon through Termux's RUN_COMMAND service. The daemon bundle and Pi's
 * pinned package manifest travel as bash arguments; TLS credentials travel on stdin only.
 */
internal object TermuxLauncher {
  @Suppress("SdCardPath") // Termux's own prefix, not this app's storage.
  private const val PREFIX = "/data/data/com.termux/files/usr"
  private val SERVICE = ComponentName("com.termux", "com.termux.app.RunCommandService")
  private const val ARGUMENT_LIMIT = 256 * 1024

  fun launch(context: Context, pairing: TermuxPairing, resultIntent: PendingIntent) {
    val daemon = asset(context, "pipkin-daemon.mjs")
    val packageJson = asset(context, "pi/package.json")
    val packageLock = asset(context, "pi/package-lock.json")
    val size = listOf(daemon, packageJson, packageLock).sumOf { it.toByteArray(Charsets.UTF_8).size }
    check(size <= ARGUMENT_LIMIT) { "Termux launch assets exceed the Intent size limit" }
    val intent = Intent("com.termux.RUN_COMMAND").setComponent(SERVICE)
      .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
      // -p ignores inherited BASH_ENV, ENV, shell functions and SHELLOPTS before the first command.
      .putExtra(
        "com.termux.RUN_COMMAND_ARGUMENTS",
        arrayOf(
          "--noprofile", "--norc", "-p", "-c", BOOTSTRAP, "pipkin-bootstrap",
          daemon, pairing.port.toString(), pairing.installId, packageJson, packageLock,
        ),
      )
      .putExtra(
        "com.termux.RUN_COMMAND_STDIN",
        JSONObject().put("token", pairing.token)
          .put("certificate", pairing.identity.certificatePem())
          .put("privateKey", pairing.identity.privateKeyPkcs8Pem()).toString() + "\n",
      )
      .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
      .putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", "0")
      .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Pipkin agent supervisor")
      .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultIntent)
    try {
      if (context.startService(intent) == null) {
        throw RuntimeFailure(RuntimeFailureKind.MissingTermux, "Termux RUN_COMMAND service is unavailable.")
      }
    } catch (_: SecurityException) {
      throw RuntimeFailure(
        RuntimeFailureKind.ExternalApps,
        "Termux blocked external commands. Run the setup command shown below in Termux, then retry.",
      )
    } catch (_: IllegalStateException) {
      throw RuntimeFailure(
        RuntimeFailureKind.Setup,
        "Android blocked starting Termux in the background. Open Termux, then return to Pipkin.",
      )
    }
  }

  private fun asset(context: Context, name: String): String =
    context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

  /**
   * `$1` daemon source, `$2` port, `$3` install id, `$4` package.json, `$5` package-lock.json;
   * one JSON credentials line on stdin. Exit codes: 70 Termux env, 72 node missing,
   * 73 node too old, 74 npm missing, 80 credentials missing; daemon adds 78 (port) and 79 (lock).
   */
  private val BOOTSTRAP = $$"""
    unset BASH_ENV ENV NODE_OPTIONS NODE_PATH
    set -eu
    [ -n "${PREFIX:-}" ] && [ -n "${HOME:-}" ] || exit 70
    export PATH="$PREFIX/bin:/system/bin" LC_ALL=C
    IFS= read -r PIPKIN_CREDENTIALS || exit 80
    [ -n "$PIPKIN_CREDENTIALS" ] || exit 80
    export PIPKIN_CREDENTIALS
    exec </dev/null
    command -v node >/dev/null 2>&1 || exit 72
    node -e 'const v=process.versions.node.split(".").map(Number);process.exit(v[0]>22||(v[0]===22&&v[1]>=19)?0:1)' || exit 73
    command -v npm >/dev/null 2>&1 || exit 74
    root="$HOME/.local/share/pipkin/$3"
    mkdir -p "$root/runtime/pi"
    for pair in "runtime/pipkin-daemon.mjs:$1" "runtime/pi/package.json:$4" "runtime/pi/package-lock.json:$5"; do
      name=${pair%%:*}; body=${pair#*:}
      target="$root/$name"
      if [ ! -f "$target" ] || ! printf '%s' "$body" | cmp -s - "$target"; then
        printf '%s' "$body" > "$target.tmp" && mv -f "$target.tmp" "$target"
      fi
    done
    exec "$PREFIX/bin/node" "$root/runtime/pipkin-daemon.mjs" "$2" "$3"
  """.trimIndent()
}
