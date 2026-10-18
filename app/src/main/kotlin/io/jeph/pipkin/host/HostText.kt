package io.jeph.pipkin.host

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import org.json.JSONObject

/**
 * A text the host wrote for the user: its [english] wording, and the [key] and [args] that name it among the app's
 * string resources (`strings_host.xml`), so it shows in the app's language. The host keeps sending English for its logs
 * and for a key this build doesn't know yet, and an argument the host left out falls back to it too.
 */
internal data class HostText(val english: String, val key: String? = null, val args: Map<String, Any> = emptyMap()) {
  /**
   * In the app's language, read through [AppStrings] when it's called, so a text kept in an exception follows a change
   * of language. A text with a [key] needs the application, which JVM unit tests don't have.
   */
  fun resolve(): String {
    val template = key?.let(templates::get) ?: return english
    val values = template.argNames.map { name ->
      val value = args[name] ?: return english
      if (value is HostText) value.resolve() else value
    }
    // Android's string APIs take only varargs; spreading an array built in place skips the copy a spread makes.
    return when (template) {
      is Template.Text -> AppStrings.get(template.id, *Array(values.size, values::get))

      is Template.Plural -> {
        val count = (values[0] as? Number)?.toInt() ?: return english
        AppStrings.plural(template.id, count, *Array(values.size, values::get))
      }
    }
  }

  /** [resolve] for Compose, which recomposes when the app's language changes. */
  @Composable
  fun localized(): String {
    val template = key?.let(templates::get) ?: return english
    val values = template.argNames.map { name ->
      val value = args[name] ?: return english
      if (value is HostText) value.localized() else value
    }
    return when (template) {
      is Template.Text -> stringResource(template.id, *Array(values.size, values::get))

      is Template.Plural -> {
        val count = (values[0] as? Number)?.toInt() ?: return english
        pluralStringResource(template.id, count, *Array(values.size, values::get))
      }
    }
  }

  companion object {
    private const val I18N_SUFFIX = "I18n"

    /**
     * [english] with the `{key, args}` object the host sent beside it, if any. An argument with an `<name>I18n` sibling
     * is itself a [HostText].
     */
    fun of(english: String, i18n: JSONObject?): HostText {
      if (i18n == null) return HostText(english)
      val key = i18n.optString("key").takeIf(String::isNotEmpty) ?: return HostText(english)
      val args = i18n.optJSONObject("args") ?: return HostText(english, key)
      val values = args.keys().asSequence()
        .filterNot { it.endsWith(I18N_SUFFIX) && args.has(it.removeSuffix(I18N_SUFFIX)) }
        .associateWith { name ->
          val value = args.get(name)
          val nested = args.optJSONObject("$name$I18N_SUFFIX")
          if (value is String && nested != null) of(value, nested) else value
        }
        .filterValues { it != JSONObject.NULL }
      return HostText(english, key, values)
    }

    /** [json]'s text field [name] with its `<name>I18n` sibling; null when the field is missing, null or empty. */
    fun field(json: JSONObject, name: String): HostText? {
      val english = json.opt(name) as? String
      if (english.isNullOrEmpty()) return null
      return of(english, json.optJSONObject("$name$I18N_SUFFIX"))
    }
  }
}

/** A string resource and the names of the host's arguments for its `%1$s`, `%2$s`, … placeholders, in order. */
private sealed interface Template {
  val argNames: List<String>

  class Text(@StringRes val id: Int, override val argNames: List<String> = emptyList()) : Template

  /** A plural resource whose quantity is the argument `count`, which is also its `%1$d`. */
  class Plural(@PluralsRes val id: Int) : Template {
    override val argNames = listOf("count")
  }
}

/**
 * The resource for each key the host sends (see `HostText` in `host/src/env.ts`), named `host_<key>`; none for a key this
 * build doesn't know.
 */
private val templates: Map<String, Template> = mapOf(
  "config_busy" to Template.Text(R.string.host_config_busy),
  "config_lock_busy" to Template.Text(R.string.host_config_lock_busy),
  "pi_running" to Template.Text(R.string.host_pi_running, listOf("processes")),
  "session_not_found" to Template.Text(R.string.host_session_not_found, listOf("path")),
  "models_invalid" to Template.Text(R.string.host_models_invalid),
  "settings_invalid" to Template.Text(R.string.host_settings_invalid),
  "folder_not_found" to Template.Text(R.string.host_folder_not_found, listOf("path")),

  "agent_not_installed" to Template.Text(R.string.host_agent_not_installed),
  "agent_not_installed_needs" to Template.Text(R.string.host_agent_not_installed_needs, listOf("expected")),
  "agent_version_mismatch" to Template.Text(R.string.host_agent_version_mismatch, listOf("installed", "expected")),
  "agent_packages_outdated" to Template.Text(R.string.host_agent_packages_outdated),
  "agent_extensions_outdated" to Template.Text(R.string.host_agent_extensions_outdated),
  "agent_download_offline" to Template.Text(R.string.host_agent_download_offline),
  "extensions_download_offline" to Template.Text(R.string.host_extensions_download_offline),
  "command_failed" to Template.Text(R.string.host_command_failed, listOf("command", "exit")),
  "command_killed" to Template.Text(R.string.host_command_killed, listOf("command")),

  "provider_id_invalid" to Template.Text(R.string.host_provider_id_invalid),
  "provider_id_builtin" to Template.Text(R.string.host_provider_id_builtin, listOf("id")),
  "provider_unreachable" to Template.Text(R.string.host_provider_unreachable, listOf("url")),
  "provider_unreachable_because" to Template.Text(R.string.host_provider_unreachable_because, listOf("url", "cause")),
  "provider_http_status" to Template.Text(R.string.host_provider_http_status, listOf("status")),
  "provider_http_status_said" to Template.Text(R.string.host_provider_http_status_said, listOf("status", "message")),
  "provider_web_page" to Template.Text(R.string.host_provider_web_page, listOf("url")),
  "provider_not_json" to Template.Text(R.string.host_provider_not_json, listOf("body")),
  "provider_no_data_list" to Template.Text(R.string.host_provider_no_data_list),

  "backup_progress_backed_up" to Template.Plural(R.plurals.host_backup_progress_backed_up),
  "backup_progress_restored" to Template.Plural(R.plurals.host_backup_progress_restored),
  "backup_not_pipkin" to Template.Text(R.string.host_backup_not_pipkin),
  "backup_from_newer_pipkin" to Template.Text(R.string.host_backup_from_newer_pipkin),
  "backup_unreadable" to Template.Text(R.string.host_backup_unreadable),
  "backup_missing" to Template.Text(R.string.host_backup_missing),
  "backup_path_outside_config" to Template.Text(R.string.host_backup_path_outside_config, listOf("path")),
  "backup_path_unsafe" to Template.Text(R.string.host_backup_path_unsafe, listOf("path")),
  "backup_holds_excluded" to Template.Text(R.string.host_backup_holds_excluded, listOf("name")),
  "backup_path_twice" to Template.Text(R.string.host_backup_path_twice, listOf("path")),
  "backup_path_beneath_symlink" to Template.Text(R.string.host_backup_path_beneath_symlink, listOf("path")),
  "backup_files_beneath_symlink" to Template.Text(R.string.host_backup_files_beneath_symlink, listOf("path")),
  "backup_damaged" to Template.Text(R.string.host_backup_damaged, listOf("reason")),
  "archive_truncated" to Template.Text(R.string.host_archive_truncated),
  "archive_trailing_data" to Template.Text(R.string.host_archive_trailing_data),
  "archive_header" to Template.Text(R.string.host_archive_header),
  "archive_not_tar" to Template.Text(R.string.host_archive_not_tar),
  "archive_pax_header" to Template.Text(R.string.host_archive_pax_header),
  "archive_oversized_pax_header" to Template.Text(R.string.host_archive_oversized_pax_header),
  "archive_unsupported_entry" to Template.Text(R.string.host_archive_unsupported_entry, listOf("path")),
  "archive_empty" to Template.Text(R.string.host_archive_empty),

  "not_a_folder" to Template.Text(R.string.host_not_a_folder),
  "files_no_access" to Template.Text(R.string.host_files_no_access),
  "files_gone" to Template.Text(R.string.host_files_gone),
  "files_is_folder" to Template.Text(R.string.host_files_is_folder),
  "files_link_loop" to Template.Text(R.string.host_files_link_loop),
  "files_read_only" to Template.Text(R.string.host_files_read_only),
  "files_device_full" to Template.Text(R.string.host_files_device_full),
  "files_unreadable" to Template.Text(R.string.host_files_unreadable, listOf("code")),
  "files_unreadable_unknown" to Template.Text(R.string.host_files_unreadable_unknown),
  "files_outside_workspace" to Template.Text(R.string.host_files_outside_workspace),
  "files_not_regular" to Template.Text(R.string.host_files_not_regular),
  "files_folder_name_invalid" to Template.Text(R.string.host_files_folder_name_invalid),
  "files_name_taken" to Template.Text(R.string.host_files_name_taken, listOf("name")),

  "browse_no_access" to Template.Text(R.string.host_browse_no_access),
  "browse_folder_missing" to Template.Text(R.string.host_browse_folder_missing),
  "browse_path_not_absolute" to Template.Text(R.string.host_browse_path_not_absolute),
  "browse_unlistable" to Template.Text(R.string.host_browse_unlistable, listOf("code")),
  "browse_unlistable_unknown" to Template.Text(R.string.host_browse_unlistable_unknown),

  "git_missing" to Template.Text(R.string.host_git_missing),
  "git_exited" to Template.Text(R.string.host_git_exited, listOf("command", "exit")),
  "git_timed_out" to Template.Text(R.string.host_git_timed_out, listOf("command", "seconds")),
)
