package io.jeph.pipkin.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import io.jeph.pipkin.data.RemoteHosts
import io.jeph.pipkin.host.DEFAULT_SSH_PORT
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteAuth
import io.jeph.pipkin.host.RemoteHost
import java.io.IOException

/** A field of the host editor, by the name `remote_host_invalid` gives it in `i18n.args.field`. */
internal enum class RemoteHostField(val id: String) {
  Name("name"),
  Address("address"),
  Port("port"),
  ;

  companion object {
    /** The field [rejected] is about, or null when it isn't a check of one field. */
    fun of(rejected: HostRejected): RemoteHostField? {
      if (rejected.code != INVALID) return null
      val field = rejected.text?.args?.get("field") ?: return null
      return entries.firstOrNull { it.id == field }
    }

    private const val INVALID = "remote_host_invalid"
  }
}

/**
 * [text] as an SSH port: SSH's own for a blank field, null for anything but a number from 1 to 65535. Digits only, so
 * `+22` or a number too long for an Int is refused rather than read some other way.
 */
internal fun parseSshPort(text: String): Int? {
  val trimmed = text.trim()
  if (trimmed.isEmpty()) return DEFAULT_SSH_PORT
  if (trimmed.length > MAX_PORT_DIGITS || !trimmed.all { it in '0'..'9' }) return null
  return trimmed.toInt().takeIf { it in 1..MAX_PORT }
}

private const val MAX_PORT = 65_535
private const val MAX_PORT_DIGITS = 5

/**
 * The host editor's fields for host [id], empty for a host not saved yet, and the errors the last save found. The
 * host validates; only a port that isn't a number is caught here, since it can't be sent as one.
 */
@Stable
internal class RemoteHostForm(id: String, name: String, address: String, port: String, auth: RemoteAuth) {
  /** Set by the first save, so later saves update the same host. */
  var id by mutableStateOf(id)
    private set
  var name by mutableStateOf(name)
  var address by mutableStateOf(address)
  var port by mutableStateOf(port)
  var auth by mutableStateOf(auth)

  var errors by mutableStateOf(emptyMap<RemoteHostField, String>())
    private set

  /** Why the last save or action failed when it isn't about one field. */
  var formError by mutableStateOf<String?>(null)

  var saving by mutableStateOf(false)
    private set

  val editing: Boolean get() = id.isNotEmpty()

  /** Clears [field]'s error once the user changes it. */
  fun edited(field: RemoteHostField) {
    if (field in errors) errors = errors - field
  }

  /**
   * Whether leaving would lose edits: fields that differ from [saved], the host as `hosts.json` has it, or for a host
   * not saved yet ([saved] null), a name or address typed.
   */
  fun dirty(saved: RemoteHost?): Boolean {
    if (saved == null) return name.isNotBlank() || address.isNotBlank()
    return name != saved.name ||
      address != saved.address ||
      auth != saved.auth ||
      (auth != RemoteAuth.Config && parseSshPort(port) != (saved.port ?: DEFAULT_SSH_PORT))
  }

  /** The host the fields describe, over [saved] (null for a new host); null while the port isn't one. */
  fun draft(saved: RemoteHost?): RemoteHost? {
    val parsedPort = parseSshPort(port)
    if (auth != RemoteAuth.Config && parsedPort == null) return null
    return RemoteHost(
      id = id,
      name = name.trim(),
      auth = auth,
      address = address.trim(),
      port = parsedPort.takeIf { auth != RemoteAuth.Config },
      home = saved?.home,
    )
  }

  /** Saves the fields over [saved] (null for a new host); returns the host as saved, or null with the errors set. */
  suspend fun save(remoteHosts: RemoteHosts, saved: RemoteHost?): RemoteHost? {
    val host = draft(saved)
    if (host == null) {
      errors = mapOf(RemoteHostField.Port to AppStrings.get(R.string.host_remote_port_invalid))
      return null
    }
    errors = emptyMap()
    formError = null
    saving = true
    return try {
      // The fields take the host as saved, trimmed, so they match it and don't count as unsaved edits.
      remoteHosts.save(host).also { stored ->
        id = stored.id
        name = stored.name
        address = stored.address
        if (stored.auth != RemoteAuth.Config) port = (stored.port ?: DEFAULT_SSH_PORT).toString()
      }
    } catch (rejected: HostRejected) {
      val field = RemoteHostField.of(rejected)
      if (field == null) formError = rejected.error else errors = mapOf(field to rejected.error)
      null
    } catch (failure: IOException) {
      formError = failure.message?.takeIf(String::isNotEmpty) ?: AppStrings.get(R.string.remote_save_failed)
      null
    } finally {
      saving = false
    }
  }

  companion object {
    fun of(host: RemoteHost?) = RemoteHostForm(
      id = host?.id.orEmpty(),
      name = host?.name.orEmpty(),
      address = host?.address.orEmpty(),
      port = (host?.port ?: DEFAULT_SSH_PORT).toString(),
      auth = host?.auth ?: RemoteAuth.Key,
    )

    /** Keeps the fields across activity recreation; errors are dropped, as they come back with the next save. */
    val Saver: Saver<RemoteHostForm, List<String>> = Saver(
      save = { listOf(it.id, it.name, it.address, it.port, it.auth.id) },
      restore = { RemoteHostForm(it[0], it[1], it[2], it[3], RemoteAuth.of(it[4])) },
    )
  }
}
