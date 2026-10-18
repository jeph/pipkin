package io.jeph.pipkin.host

import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import org.json.JSONObject

/**
 * The host ran the operation and refused it; [code] is the host's machine code (`pi_version`, `bad_id`, …) and
 * [englishError] its reason as the host wrote it, for logs. [error] is that reason in the app's language, read when
 * it's shown so it follows a change of language; [message] is the same. [text] is null when the host gave no reason.
 */
internal class HostRejected(
  val code: String,
  val englishError: String,
  private val text: HostText? = HostText(englishError),
) : Exception() {
  val error: String get() = text?.resolve() ?: AppStrings.get(R.string.host_rejected_without_reason)

  override val message: String get() = error

  companion object {
    /** Null for an `ok:true` `pipkin_result`. */
    fun from(result: JSONObject): HostRejected? {
      if (result.optBoolean("ok")) return null
      val code = result.optString("code", "error")
      val englishError = result.opt("error") as? String
      if (englishError.isNullOrEmpty()) return HostRejected(code, "The Termux helper failed.", text = null)
      return HostRejected(code, englishError, HostText.of(englishError, result.optJSONObject("i18n")))
    }
  }
}
