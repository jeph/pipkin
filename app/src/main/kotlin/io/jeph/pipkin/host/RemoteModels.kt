package io.jeph.pipkin.host

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** How Pipkin signs in to a remote host, as `hosts.json` names it in [id]. */
internal enum class RemoteAuth(val id: String) {
  /** Pipkin's own ed25519 key, whose public line the user adds to the server. */
  Key("key"),

  /** A password, asked each time Pipkin connects. */
  Password("password"),

  /** An entry of the user's own `~/.ssh/config` in Termux; the address is its `Host` alias and there is no port. */
  Config("config"),
  ;

  companion object {
    fun of(id: String): RemoteAuth = entries.firstOrNull { it.id == id } ?: Key
  }
}

/**
 * A host the user added, as `hosts.json` keeps it. [id] is fixed for the host's life and empty for one not saved yet;
 * [home] is the remote home folder, learned at the first connect.
 */
internal data class RemoteHost(
  val id: String,
  val name: String,
  val auth: RemoteAuth,
  val address: String,
  val port: Int?,
  val home: String?,
) {
  /** The address with the port when it isn't SSH's own, as the machine menu shows it: `pi@192.168.1.40:2222`. */
  val displayAddress: String get() = remoteAddress(address, port.takeIf { auth != RemoteAuth.Config })

  /** `remote_host_save`'s `host`: without an [id] for a new host, and without a port for a `config` one. */
  fun toJson(): JSONObject {
    val json = JSONObject()
      .put("name", name)
      .put("auth", auth.id)
      .put("address", address)
      .put("port", if (auth == RemoteAuth.Config || port == null) JSONObject.NULL else port)
      .put("home", home ?: JSONObject.NULL)
    if (id.isNotEmpty()) json.put("id", id)
    return json
  }

  companion object {
    fun from(json: JSONObject) = RemoteHost(
      id = json.optString("id"),
      name = json.optString("name"),
      auth = RemoteAuth.of(json.optString("auth")),
      address = json.optString("address"),
      port = (json.opt("port") as? Number)?.toInt(),
      home = (json.opt("home") as? String)?.takeIf(String::isNotEmpty),
    )
  }
}

/** The port SSH uses when none is given, which addresses leave out. */
internal const val DEFAULT_SSH_PORT = 22

/**
 * [address] (`user@host`, `host` or `user@[v6 address]`) with [port] after a colon unless it is null or SSH's own.
 * A v6 address comes in brackets already, so the colon can't be read as part of it.
 */
internal fun remoteAddress(address: String, port: Int?): String =
  if (port == null || port == DEFAULT_SSH_PORT) address else "$address:$port"

/**
 * The fingerprint `ssh-keygen -l` shows for the public key [line] (`ssh-ed25519 AAAA… comment`): `SHA256:` and the
 * unpadded base64 of the key's SHA-256. Null when the line holds no key.
 */
internal fun keyFingerprint(line: String): String? {
  val blob = line.trim().split(' ').getOrNull(1) ?: return null
  val bytes = try {
    Base64.getDecoder().decode(blob)
  } catch (_: IllegalArgumentException) {
    return null
  }
  val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
  return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** The comment after the public key [line]'s key, which names the phone for Pipkin's key: `pipkin@Pixel 9`. */
internal fun keyComment(line: String): String = line.trim().split(' ', limit = 3).getOrNull(2).orEmpty()

/** A remote folder: the host [hostId] names and the absolute [path] on it. */
internal data class RemotePath(val hostId: String, val path: String) {
  companion object {
    /** Null when [json] is missing or names no host, as for a session from a local folder. */
    fun from(json: JSONObject?): RemotePath? {
      val hostId = json?.optString("hostId")?.takeIf(String::isNotEmpty) ?: return null
      return RemotePath(hostId, json.optString("path"))
    }
  }
}

/** A stand-in folder in Termux, the workspace [cwd] Pipkin uses for the remote folder [path] on host [hostId]. */
internal data class RemoteWorkspace(val cwd: String, val hostId: String, val path: String)

/**
 * What `remote_hosts` reported: whether Termux has OpenSSH, Pipkin's public key line (null until the key is made),
 * the hosts, and every stand-in folder, including those of removed hosts.
 */
internal data class RemoteHostsState(
  val sshInstalled: Boolean = true,
  val publicKey: String? = null,
  val hosts: List<RemoteHost> = emptyList(),
  val workspaces: List<RemoteWorkspace> = emptyList(),
) {
  fun host(id: String): RemoteHost? = hosts.firstOrNull { it.id == id }

  fun toJson(): JSONObject = JSONObject()
    .put("sshInstalled", sshInstalled)
    .put("publicKey", publicKey ?: JSONObject.NULL)
    .put("hosts", JSONArray(hosts.map(RemoteHost::toJson)))
    .put(
      "workspaces",
      JSONArray(workspaces.map { JSONObject().put("cwd", it.cwd).put("hostId", it.hostId).put("path", it.path) }),
    )

  companion object {
    fun from(json: JSONObject) = RemoteHostsState(
      sshInstalled = json.optBoolean("sshInstalled"),
      publicKey = (json.opt("publicKey") as? String)?.takeIf(String::isNotEmpty),
      hosts = json.optJSONArray("hosts").objects().map(RemoteHost::from),
      workspaces = json.optJSONArray("workspaces").objects().map {
        RemoteWorkspace(it.optString("cwd"), it.optString("hostId"), it.optString("path"))
      },
    )
  }
}

/**
 * A remote operation's failure as the host reports it beside a result (`pipkin_places`, the `remote` channel topic):
 * one of the `HostError` codes in [code] and its [text].
 */
internal data class RemoteFailure(val code: String, val text: HostText) {
  /** The user can fix it by connecting: signing in, or accepting the host's key. */
  val needsConnect: Boolean get() = needsConnect(code)

  /** It means the host has no live connection. */
  val disconnected: Boolean get() = isDisconnect(code)

  companion object {
    const val NOT_CONNECTED = "remote_not_connected"
    const val HOST_KEY_UNKNOWN = "remote_host_key_unknown"
    const val HOST_KEY_CHANGED = "remote_host_key_changed"
    const val HOST_UNKNOWN = "remote_host_unknown"

    /** The host answers, but the server has no session free: terminals and background tasks hold them all. */
    const val SESSIONS_FULL = "remote_sessions_full"

    /** Answering a prompt with null ends the connect with this code, which needs no message. */
    const val CONNECT_CANCELLED = "remote_connect_cancelled"

    private val CONNECT_CODES = setOf(NOT_CONNECTED, HOST_KEY_UNKNOWN)

    private val DISCONNECT_CODES = setOf(
      NOT_CONNECTED,
      HOST_KEY_UNKNOWN,
      HOST_KEY_CHANGED,
      "remote_auth_failed",
      "remote_unreachable",
    )

    /** Whether a failure with [code] means the host has no live connection. */
    fun isDisconnect(code: String): Boolean = code in DISCONNECT_CODES

    /** Whether the user can fix a failure with [code] by connecting. */
    fun needsConnect(code: String): Boolean = code in CONNECT_CODES

    fun of(rejected: HostRejected): RemoteFailure =
      RemoteFailure(rejected.code, rejected.text ?: HostText(rejected.englishError))

    /** `{ code, message, i18n }`; null when [json] is missing. */
    fun from(json: JSONObject?): RemoteFailure? {
      if (json == null) return null
      val message = json.optString("message")
      return RemoteFailure(json.optString("code", "remote_failed"), HostText.of(message, json.optJSONObject("i18n")))
    }
  }
}

/** One `pipkin_remote_prompt` of a `remote_connect`: what `ssh` asks, of [kind], in its own [text]. */
internal data class RemotePrompt(
  val kind: Kind,
  val text: String,
  /** For [Kind.HostKey]: the key's type, such as `ED25519`, and its `SHA256:` fingerprint. */
  val keyType: String?,
  val fingerprint: String?,
) {
  enum class Kind { HostKey, Password, Passphrase, Other }

  companion object {
    fun from(json: JSONObject) = RemotePrompt(
      kind = when (json.optString("kind")) {
        "host_key" -> Kind.HostKey
        "password" -> Kind.Password
        "passphrase" -> Kind.Passphrase
        else -> Kind.Other
      },
      text = json.optString("text"),
      keyType = (json.opt("keyType") as? String)?.takeIf(String::isNotEmpty),
      fingerprint = (json.opt("fingerprint") as? String)?.takeIf(String::isNotEmpty),
    )
  }
}

/**
 * Receives a `remote_connect`'s prompts. Callbacks run on the collecting coroutine and must not block; answer later
 * through [RemoteReply].
 */
internal fun interface RemoteConnectUi {
  fun onPrompt(prompt: RemotePrompt, reply: RemoteReply)
}

/** Answers exactly one prompt of a `remote_connect` over its channel. */
internal class RemoteReply(private val channel: HostChannel, private val id: Any) {
  /**
   * Sends [answer]; null cancels, which ends the connect. For a host key any text trusts it. False when the connect
   * already ended; the connect call reports why.
   */
  suspend fun answer(answer: String?): Boolean = try {
    channel.send(
      JSONObject()
        .put("type", HostProtocol.REMOTE_ANSWER)
        .put("id", id)
        .put("answer", answer ?: JSONObject.NULL),
    )
    true
  } catch (_: IOException) {
    false
  }
}
