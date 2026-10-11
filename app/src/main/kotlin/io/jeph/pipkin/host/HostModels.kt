package io.jeph.pipkin.host

import java.io.IOException
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

/** One Pi session file; times are epoch milliseconds. */
internal data class SessionInfo(
  val path: String,
  val id: String,
  val cwd: String,
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
      name = json.opt("name") as? String,
      created = json.optLong("created"),
      modified = json.optLong("modified"),
      messageCount = json.optInt("messageCount"),
      firstMessage = json.optString("firstMessage"),
    )
  }
}

/** A provider Pi ships: `anthropic` or `openai-codex`. [type] is the configured credential type, if any. */
internal data class BuiltinProvider(val id: String, val name: String, val configured: Boolean, val type: String?)

/** An entry of `models.json` `providers`. [images] is true when any of its models accepts image input. */
internal data class CustomProvider(
  val id: String,
  val baseUrl: String,
  val api: String,
  val apiKey: String,
  val models: List<String>,
  val images: Boolean,
)

internal data class AuthStatus(
  val builtin: List<BuiltinProvider>,
  val custom: List<CustomProvider>,
  /** Set when `models.json` exists but could not be loaded. */
  val modelsError: String?,
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
        )
      },
      custom = json.optJSONArray("custom").objects().map {
        CustomProvider(
          id = it.getString("id"),
          baseUrl = it.optString("baseUrl"),
          api = it.optString("api"),
          apiKey = it.optString("apiKey"),
          models = it.optJSONArray("models").strings(),
          images = it.optBoolean("images"),
        )
      },
      modelsError = json.opt("modelsError") as? String,
    )
  }
}

/** `provider_save` params. */
internal data class ProviderConfig(
  val id: String,
  val baseUrl: String,
  val api: String,
  val apiKey: String,
  val images: Boolean,
  val models: List<String>,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("baseUrl", baseUrl)
    .put("api", api)
    .put("apiKey", apiKey)
    .put("images", images)
    .put("models", JSONArray(models))

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
