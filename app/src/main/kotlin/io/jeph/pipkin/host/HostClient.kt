package io.jeph.pipkin.host

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Suspend API over `host.mjs` modes. Each call launches one host process, sends one
 * `pipkin_request` and, except for [openRpc], reads lines until `pipkin_result`.
 * Throws [HostFailure] when the host could not run and [HostRejected] when it refused.
 *
 * A launch waits until Pipkin is in the [foreground]. In the background Android may refuse to start Termux, and in
 * Doze it blocks the host's connection back to Pipkin once Pipkin's foreground service stops, so the launch would
 * fail. Nothing launched from the background needs to run before the user returns: reconnects and session lists
 * are only seen on screen.
 */
internal class HostClient(context: Context, private val foreground: Flow<Boolean>) {
  private val context = context.applicationContext
  private val helloState = MutableStateFlow<HostHello?>(null)
  private val failureState = MutableStateFlow<HostFailure?>(null)
  private val installingState = MutableStateFlow(false)

  /** The bundled `pi/package.json`: Pi and the Pi packages Pipkin ships; `ensure` installs it, `rpc` requires it. */
  private val packageJson: String by lazy { asset(PACKAGE_JSON) }

  /** The Pi version the bundled `pi/package.json` pins. */
  val expectedPiVersion: String by lazy {
    JSONObject(packageJson).getJSONObject("dependencies").getString(PI_PACKAGE)
  }

  /** Hello of the most recent host that connected; Pi's version is updated after [ensure]. */
  val lastHello: StateFlow<HostHello?> = helloState.asStateFlow()

  /** Launch failure of the most recent operation; cleared by the next host that connects. */
  val lastFailure: StateFlow<HostFailure?> = failureState.asStateFlow()

  /** True while [ensure] runs. */
  val installing: StateFlow<Boolean> = installingState.asStateFlow()

  /** Installs the pinned Pi runtime if needed and returns the installed version. */
  suspend fun ensure(onProgress: (String) -> Unit): String {
    installingState.value = true
    try {
      val params = withContext(Dispatchers.IO) {
        JSONObject()
          .put("packageJson", packageJson)
          .put("packageLock", asset(PACKAGE_LOCK))
          .put("expectedVersion", expectedPiVersion)
      }
      val data = call("ensure", params, ENSURE_TIMEOUT_MILLIS) { _, line ->
        if (line.optString("type") == HostProtocol.PROGRESS) onProgress(line.optString("line"))
      }
      val version = data.getString("piVersion")
      helloState.update { it?.copy(piVersion = version) }
      return version
    } finally {
      installingState.value = false
    }
  }

  /** Newest first. */
  suspend fun sessions(): List<SessionInfo> =
    call("sessions", JSONObject()).optJSONArray("sessions").objects().map(SessionInfo::from)

  suspend fun deleteSession(path: String) {
    call("delete_session", JSONObject().put("path", path))
  }

  suspend fun authStatus(): AuthStatus = AuthStatus.from(call("auth_status", JSONObject()))

  /** OAuth sign-in for `anthropic` or `openai-codex`. Cancelling the caller aborts the login. */
  suspend fun login(providerId: String, ui: LoginUi) {
    call("login", JSONObject().put("providerId", providerId), LOGIN_TIMEOUT_MILLIS) { channel, line ->
      when (line.optString("type")) {
        HostProtocol.AUTH_EVENT -> line.optJSONObject("event")?.let(ui::onEvent)

        HostProtocol.AUTH_PROMPT -> ui.onPrompt(
          line.optJSONObject("prompt") ?: JSONObject(),
          AuthReply(channel, line.optLong("id")),
        )

        HostProtocol.AUTH_PROMPT_DISMISS -> ui.onPromptDismiss(line.optLong("id"))
      }
    }
  }

  suspend fun logout(providerId: String) {
    call("logout", JSONObject().put("providerId", providerId))
  }

  /** The endpoint's models, each prefilled from its own metadata and then Pi's catalogue. */
  suspend fun providerModels(baseUrl: String, apiKey: String, api: String): List<ModelConfig> =
    call("provider_models", JSONObject().put("baseUrl", baseUrl).put("apiKey", apiKey).put("api", api))
      .optJSONArray("models").objects().map(ModelConfig::from)

  /** Pi's catalogue settings for [ids], in order; an unknown id comes back with only its id. */
  suspend fun modelDefaults(ids: List<String>, api: String): List<ModelConfig> =
    call("model_defaults", JSONObject().put("ids", JSONArray(ids)).put("api", api))
      .optJSONArray("models").objects().map(ModelConfig::from)

  suspend fun providerSave(provider: ProviderConfig) {
    call("provider_save", provider.toJson())
  }

  /** Writes a subscription's model settings (`modelOverrides`, extra models) and which of its models show. */
  suspend fun subscriptionSave(providerId: String, enabled: List<String>, models: List<ModelConfig>) {
    call(
      "subscription_save",
      JSONObject()
        .put("providerId", providerId)
        .put("enabled", JSONArray(enabled))
        .put("models", JSONArray(models.map(ModelConfig::toJson))),
    )
  }

  suspend fun providerRemove(id: String) {
    call("provider_remove", JSONObject().put("id", id))
  }

  /** Makes new Pi sessions start with this model while it stays available and in scope. */
  suspend fun setDefaultModel(provider: String, modelId: String) {
    call("default_model", JSONObject().put("provider", provider).put("modelId", modelId))
  }

  /** Makes new Pi sessions start with this thinking level, clamped to their model. */
  suspend fun setDefaultThinkingLevel(level: String) {
    call("default_model", JSONObject().put("thinkingLevel", level))
  }

  /** Writes [files] (name to bytes) into a new folder in Termux; returns their absolute paths in order. */
  suspend fun saveAttachments(files: List<Pair<String, ByteArray>>): List<String> {
    val encoder = Base64.getEncoder()
    val array = JSONArray()
    files.forEach { (name, bytes) ->
      array.put(JSONObject().put("name", name).put("data", encoder.encodeToString(bytes)))
    }
    return call("save_attachments", JSONObject().put("files", array)).optJSONArray("paths").strings()
  }

  /** Opens a folder browser session; close it to end the host. Cancelling the caller closes it too. */
  suspend fun openFolderBrowser(): FolderBrowser {
    val channel = connect("browse")
    var started = false
    try {
      sendRequest(channel, JSONObject())
      return FolderBrowser.start(channel).also { started = true }
    } finally {
      if (!started) channel.close()
    }
  }

  /**
   * Starts `pi --mode rpc` and returns its channel: Pi's RPC JSONL both ways, ended by a
   * `pipkin_exit` line. A refusal (`pi_version`, `cwd_missing`) arrives instead as a
   * `pipkin_result` line; see [HostRejected.from].
   */
  suspend fun openRpc(cwd: String, sessionPath: String?): HostChannel {
    val channel = connect("rpc")
    var sent = false
    try {
      sendRequest(
        channel,
        JSONObject()
          .put("cwd", cwd)
          .put("sessionPath", sessionPath ?: JSONObject.NULL)
          .put("expectedVersion", expectedPiVersion)
          .put("packageJson", packageJson),
      )
      sent = true
    } finally {
      // Cancellation skips sendRequest's IOException path; without this close the host and Pi outlive the caller.
      if (!sent) channel.close()
    }
    return channel
  }

  private suspend fun connect(mode: String): HostChannel {
    if (!foreground.first()) {
      Log.i(TAG, "Host $mode waits until Pipkin is in the foreground")
      foreground.first { it }
    }
    val channel = try {
      HostLauncher.launch(context, mode)
    } catch (failure: HostFailure) {
      failureState.value = failure
      throw failure
    }
    failureState.value = null
    helloState.value = HostHello.from(channel.hello)
    return channel
  }

  private suspend fun call(
    mode: String,
    params: JSONObject,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    onLine: suspend (HostChannel, JSONObject) -> Unit = { _, _ -> },
  ): JSONObject {
    val channel = connect(mode)
    try {
      val result = withTimeoutOrNull(timeoutMillis) {
        sendRequest(channel, params)
        channel.lines.firstOrNull { line ->
          val isResult = line.optString("type") == HostProtocol.RESULT
          if (!isResult) onLine(channel, line)
          isResult
        } ?: throw HostFailure.Disconnected()
      } ?: throw HostFailure.Timeout()
      HostRejected.from(result)?.let { throw it }
      return result.optJSONObject("data") ?: JSONObject()
    } finally {
      channel.close()
    }
  }

  private suspend fun sendRequest(channel: HostChannel, params: JSONObject) {
    try {
      channel.send(JSONObject().put("type", HostProtocol.REQUEST).put("params", params))
    } catch (error: IOException) {
      channel.close()
      throw HostFailure.Disconnected(error)
    }
  }

  private fun asset(name: String): String =
    context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

  private companion object {
    const val TAG = "PipkinHost"
    const val PACKAGE_JSON = "pi/package.json"
    const val PACKAGE_LOCK = "pi/package-lock.json"
    const val PI_PACKAGE = "@earendil-works/pi-coding-agent"
    const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    const val ENSURE_TIMEOUT_MILLIS = 15 * 60_000L
    const val LOGIN_TIMEOUT_MILLIS = 10 * 60_000L
  }
}
