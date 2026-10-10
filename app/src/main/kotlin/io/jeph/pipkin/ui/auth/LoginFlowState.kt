package io.jeph.pipkin.ui.auth

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.host.AuthReply
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.LoginUi
import io.jeph.pipkin.host.objects
import java.io.IOException
import org.json.JSONObject

/** Pi `auth_url` event: the page the browser should show. */
internal data class BrowserStep(val url: String, val instructions: String?)

/** Pi `device_code` event. */
internal data class DeviceCodeStep(val userCode: String, val verificationUri: String)

internal data class AuthLink(val url: String, val label: String?)

/** Latest Pi `info` or `progress` event. */
internal data class AuthStatusLine(val message: String, val links: List<AuthLink>)

internal data class AuthOption(val id: String, val label: String, val description: String?)

/** A Pi `AuthPrompt` waiting for the user; answer it through [reply]. */
internal data class PendingPrompt(
  val type: String,
  val message: String,
  val placeholder: String?,
  val options: List<AuthOption>,
  val reply: AuthReply,
)

internal sealed interface LoginOutcome {
  data object Running : LoginOutcome

  data object SignedIn : LoginOutcome

  /** [message] is null when the host gave no reason. */
  data class Failed(val message: String?) : LoginOutcome
}

/** Observable state of one sign-in attempt; the host drives it through [LoginUi]. */
@Stable
internal class LoginFlowState : LoginUi {
  var browser by mutableStateOf<BrowserStep?>(null)
    private set
  var deviceCode by mutableStateOf<DeviceCodeStep?>(null)
    private set
  var status by mutableStateOf<AuthStatusLine?>(null)
  var prompt by mutableStateOf<PendingPrompt?>(null)
  var outcome by mutableStateOf<LoginOutcome>(LoginOutcome.Running)
    private set

  /** Runs the login to completion; cancelling the caller closes the host channel, which aborts it. */
  suspend fun run(accounts: AccountRepository, providerId: String) {
    outcome = try {
      accounts.login(providerId, this)
      LoginOutcome.SignedIn
    } catch (failure: IOException) {
      LoginOutcome.Failed(failure.message)
    } catch (rejected: HostRejected) {
      LoginOutcome.Failed(rejected.error)
    }
    prompt = null
  }

  override fun onEvent(event: JSONObject) {
    when (event.optString("type")) {
      "auth_url" -> browser = BrowserStep(event.optString("url"), event.optionalString("instructions"))

      "device_code" -> deviceCode = DeviceCodeStep(event.optString("userCode"), event.optString("verificationUri"))

      "info", "progress" -> status = AuthStatusLine(
        message = event.optString("message"),
        links = event.optJSONArray("links").objects().map { AuthLink(it.optString("url"), it.optionalString("label")) },
      )
    }
  }

  override fun onPrompt(prompt: JSONObject, reply: AuthReply) {
    this.prompt = PendingPrompt(
      type = prompt.optString("type"),
      message = prompt.optString("message"),
      placeholder = prompt.optionalString("placeholder"),
      options = prompt.optJSONArray("options").objects().map {
        AuthOption(it.optString("id"), it.optString("label", it.optString("id")), it.optionalString("description"))
      },
      reply = reply,
    )
  }

  override fun onPromptDismiss(id: Long) {
    if (prompt?.reply?.id == id) prompt = null
  }

  private fun JSONObject.optionalString(name: String): String? = (opt(name) as? String)?.takeIf { it.isNotEmpty() }
}
