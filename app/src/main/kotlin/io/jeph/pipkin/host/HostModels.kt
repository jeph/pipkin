package io.jeph.pipkin.host

import java.io.IOException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** What the last connecting `host.mjs` reported about its Termux environment. */
internal data class HostHello(
  val piVersion: String?,
  val nodeVersion: String,
  val configDir: String,
  val home: String,
) {
  companion object {
    fun from(hello: JSONObject) = HostHello(
      piVersion = hello.opt("piVersion") as? String,
      nodeVersion = hello.optString("nodeVersion"),
      configDir = hello.optString("configDir"),
      home = hello.optString("home"),
    )
  }
}

/**
 * One file in the backups folder. [createdAt] is the backup's own time (epoch milliseconds), or the file's for one that
 * isn't a readable backup, which carries the reason in [error].
 */
internal data class BackupInfo(
  val name: String,
  val size: Long,
  val createdAt: Long,
  val piVersion: String?,
  val error: String?,
) {
  companion object {
    fun from(json: JSONObject) = BackupInfo(
      name = json.getString("name"),
      size = json.optLong("size"),
      createdAt = runCatching { Instant.parse(json.optString("createdAt")).toEpochMilli() }.getOrDefault(0L),
      piVersion = json.opt("piVersion") as? String,
      error = json.opt("error") as? String,
    )
  }
}

/** The backups folder and the backups in it, newest first. */
internal data class Backups(val dir: String, val backups: List<BackupInfo>)

/** One Pi session file; times are epoch milliseconds. [cwdExists] is false once its workspace folder is gone. */
internal data class SessionInfo(
  val path: String,
  val id: String,
  val cwd: String,
  val cwdExists: Boolean,
  val name: String?,
  val created: Long,
  val modified: Long,
  val messageCount: Int,
  val firstMessage: String,
) {
  companion object {
    fun from(json: JSONObject) = SessionInfo(
      path = json.getString("path"),
      id = json.optString("id"),
      cwd = json.optString("cwd"),
      cwdExists = json.optBoolean("cwdExists", true),
      name = json.opt("name") as? String,
      created = json.optLong("created"),
      modified = json.optLong("modified"),
      messageCount = json.optInt("messageCount"),
      firstMessage = json.optString("firstMessage"),
    )
  }
}

/**
 * A provider Pi ships: `anthropic` or `openai-codex`. [type] is the configured credential type, if
 * any. [models] are every model Pi offers for it with `models.json` overrides applied; [extraIds]
 * are the ones `models.json` adds. [enabled] lists the ids settings.json `enabledModels` shows,
 * or is null when that list is empty and every model shows.
 */
internal data class BuiltinProvider(
  val id: String,
  val name: String,
  val configured: Boolean,
  val type: String?,
  val models: List<ModelConfig> = emptyList(),
  val extraIds: List<String> = emptyList(),
  val enabled: List<String>? = null,
)

/** An entry of `models.json` `providers`, with the model fields Pipkin edits. */
internal data class CustomProvider(
  val id: String,
  val baseUrl: String,
  val api: String,
  val apiKey: String,
  val models: List<ModelConfig>,
)

internal data class AuthStatus(
  val builtin: List<BuiltinProvider>,
  val custom: List<CustomProvider>,
  /** Set when `models.json` exists but could not be loaded. */
  val modelsError: String?,
  /** Set when settings.json exists but could not be loaded. */
  val settingsError: String? = null,
  /** `provider/id` of every model `enabledModels` shows; null when every model shows. */
  val scope: Set<String>? = null,
) {
  val anyConfigured: Boolean get() = custom.isNotEmpty() || builtin.any { it.configured }

  companion object {
    fun from(json: JSONObject) = AuthStatus(
      builtin = json.optJSONArray("builtin").objects().map {
        BuiltinProvider(
          id = it.getString("id"),
          name = it.optString("name", it.getString("id")),
          configured = it.optBoolean("configured"),
          type = it.opt("type") as? String,
          models = it.optJSONArray("models").objects().map(ModelConfig::from),
          extraIds = it.optJSONArray("extraIds").strings(),
          enabled = it.optJSONArray("enabled")?.strings(),
        )
      },
      custom = json.optJSONArray("custom").objects().map {
        CustomProvider(
          id = it.getString("id"),
          baseUrl = it.optString("baseUrl"),
          api = it.optString("api"),
          apiKey = it.optString("apiKey"),
          models = it.optJSONArray("models").objects().map(ModelConfig::from),
        )
      },
      modelsError = json.opt("modelsError") as? String,
      settingsError = json.opt("settingsError") as? String,
      scope = json.optJSONArray("scope")?.strings()?.toSet(),
    )
  }
}

/** `provider_save` params. */
internal data class ProviderConfig(
  val id: String,
  val baseUrl: String,
  val api: String,
  val apiKey: String,
  val models: List<ModelConfig>,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("baseUrl", baseUrl)
    .put("api", api)
    .put("apiKey", apiKey)
    .put("models", JSONArray(models.map(ModelConfig::toJson)))

  companion object {
    const val API_COMPLETIONS = "openai-completions"
    const val API_RESPONSES = "openai-responses"
  }
}

/**
 * Receives a `login` flow's events and prompts. Callbacks run on the collecting coroutine and must
 * not block; answer prompts later through [AuthReply].
 */
internal interface LoginUi {
  /** A Pi `AuthEvent`: `auth_url`, `device_code`, `info`, `progress`, … */
  fun onEvent(event: JSONObject)

  /** A Pi `AuthPrompt` (`type`, `message`, optional `placeholder` and `options`). */
  fun onPrompt(prompt: JSONObject, reply: AuthReply)

  /** Pi no longer needs the prompt [id] (for example the browser redirect won the race). */
  fun onPromptDismiss(id: Long)
}

/** Answers exactly one auth prompt over its login channel. */
internal class AuthReply(private val channel: HostChannel, val id: Long) {
  /** False when the login already ended; the login call reports why. */
  suspend fun answer(value: String): Boolean = send(
    JSONObject().put("type", HostProtocol.AUTH_RESPONSE).put("id", id).put("value", value),
  )

  suspend fun cancel(): Boolean = send(JSONObject().put("type", HostProtocol.AUTH_CANCEL).put("id", id))

  private suspend fun send(message: JSONObject): Boolean = try {
    channel.send(message)
    true
  } catch (_: IOException) {
    false
  }
}

internal fun JSONArray?.objects(): List<JSONObject> =
  if (this == null) emptyList() else List(length()) { getJSONObject(it) }

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
