package io.jeph.pipkin.host

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.jeph.pipkin.termux.TermuxInstall
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts one short-lived `host.mjs` through Termux's RUN_COMMAND service and waits for it to
 * connect back to [HostServer]. The host script travels as a bash argument; the token only on stdin.
 */
internal object HostLauncher {
  @Suppress("SdCardPath") // Termux's own prefix, not this app's storage.
  private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
  private val SERVICE = ComponentName("com.termux", "com.termux.app.RunCommandService")
  private const val HOST_ASSET = "pipkin-host.mjs"
  private const val ARGUMENT_LIMIT = 256 * 1024
  private const val CONNECT_TIMEOUT_MILLIS = 30_000L
  private const val TOKEN_BYTES = 32
  private const val INTERRUPTED_RETRIES = 2
  private const val RETRY_DELAY_MILLIS = 300L

  private val random = SecureRandom()

  @Volatile
  private var hostScript: String? = null

  /**
   * Retries a launch that Termux cancelled while stopping its service (its last command just exited);
   * the retry starts a fresh Termux service.
   */
  suspend fun launch(context: Context, mode: String): HostChannel {
    repeat(INTERRUPTED_RETRIES) {
      try {
        return launchOnce(context, mode)
      } catch (_: HostFailure.Interrupted) {
        delay(RETRY_DELAY_MILLIS)
      }
    }
    return launchOnce(context, mode)
  }

  private suspend fun launchOnce(context: Context, mode: String): HostChannel = withContext(Dispatchers.IO) {
    val server = HostServer.shared
    val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).toHexString()
    val id = UUID.randomUUID().toString()
    val connected = server.register(token)
    var channel: HostChannel? = null
    try {
      HostResultReceiver.register(id) { failure -> connected.completeExceptionally(failure) }
      start(context, mode, server.port, token, resultIntent(context, id))
      val result = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { connected.await() } ?: throw HostFailure.Timeout()
      channel = result
      result
    } finally {
      server.unregister(token)
      // Once connected, the channel reports the host's end; Termux's result only adds the host's diagnostics.
      if (channel != null) HostResultReceiver.logExit(id, mode) else HostResultReceiver.unregister(id)
      // If the host matched in the same instant, it gets no request and exits on its own after 10 s.
      if (channel == null) connected.cancel()
    }
  }

  private fun resultIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, HostResultReceiver::class.java).setData("pipkin://termux-result/$id".toUri()),
    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
  )

  private fun start(context: Context, mode: String, port: Int, token: String, resultIntent: PendingIntent) {
    // Checked on every launch, not only during onboarding: Termux can be swapped for another build after setup.
    when (TermuxInstall.status(context)) {
      TermuxInstall.Status.NotInstalled -> throw HostFailure.MissingTermux()
      TermuxInstall.Status.PlayStore, TermuxInstall.Status.UnsupportedBuild -> throw HostFailure.UntrustedTermux()
      TermuxInstall.Status.TooOld, TermuxInstall.Status.Supported -> Unit
    }
    val script = script(context)
    val intent = Intent("com.termux.RUN_COMMAND").setComponent(SERVICE)
      .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
      .putExtra(
        "com.termux.RUN_COMMAND_ARGUMENTS",
        arrayOf("--noprofile", "--norc", "-c", BOOTSTRAP, "pipkin-host", script, mode, port.toString()),
      )
      .putExtra("com.termux.RUN_COMMAND_STDIN", token + "\n")
      .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
      .putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", "0")
      .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Pipkin: $mode")
      .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultIntent)
    try {
      // A plain startService is refused while Termux is idle in the background. Termux's
      // RunCommandService calls startForeground() on every start, so this path is always allowed.
      if (context.startForegroundService(intent) == null) throw HostFailure.MissingTermux()
    } catch (_: SecurityException) {
      throw HostFailure.Permission()
    } catch (_: IllegalStateException) {
      throw HostFailure.Background()
    }
  }

  private fun script(context: Context): String {
    hostScript?.let { return it }
    val text = context.assets.open(HOST_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    check(text.toByteArray(Charsets.UTF_8).size <= ARGUMENT_LIMIT) { "$HOST_ASSET exceeds the Intent size limit" }
    hostScript = text
    return text
  }

  /**
   * `$1` host script, then `<mode> <port>` for `host.mjs`; the token is the one stdin line.
   * Exit codes: 70 Termux env, 72 node missing, 73 node too old, 74 npm missing,
   * 75 shared storage unavailable, 80 token missing.
   */
  private val BOOTSTRAP = $$$"""
    set -eu
    [ -n "${PREFIX:-}" ] && [ -n "${HOME:-}" ] || exit 70
    IFS= read -r PIPKIN_TOKEN || exit 80
    [ -n "$PIPKIN_TOKEN" ] || exit 80
    export PIPKIN_TOKEN
    exec </dev/null
    command -v node >/dev/null 2>&1 || exit 72
    node -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>22||(a===22&&b>=19)?0:1)' || exit 73
    command -v npm >/dev/null 2>&1 || exit 74
    [ -d "$HOME/storage/shared" ] && [ -w "$HOME/storage/shared" ] || exit 75
    export PIPKIN_ROOT="$HOME/.local/share/pipkin"
    PI_CODING_AGENT_DIR="$(realpath "$HOME/storage/shared")/Pipkin/pi"
    export PI_CODING_AGENT_DIR
    mkdir -p "$PIPKIN_ROOT" "$PI_CODING_AGENT_DIR"
    host="$PIPKIN_ROOT/host.mjs"
    if [ ! -f "$host" ] || ! printf '%s' "$1" | cmp -s - "$host"; then
      printf '%s' "$1" > "$host.$$.tmp" && mv -f "$host.$$.tmp" "$host"
    fi
    shift
    exec node "$host" "$@"
  """.trimIndent()
}
