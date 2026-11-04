package io.jeph.pipkin.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.RemoteHosts
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteFailure
import io.jeph.pipkin.host.RemotePrompt
import io.jeph.pipkin.host.RemoteReply
import io.jeph.pipkin.ui.components.DialogConfirm
import io.jeph.pipkin.ui.components.DialogTextField
import io.jeph.pipkin.ui.components.PipkinDialog
import io.jeph.pipkin.ui.sidebar.pathTextStyle
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One `remote_connect` at a time, from wherever a host needs connecting: the folder picker, the host editor, a chat's
 * banner. [prompt] is what `ssh` asks now and [failure] why the last connect failed, both shown by
 * [RemoteConnectDialogs]. A cancelled connect leaves no failure.
 */
@Stable
internal class RemoteConnector(private val remoteHosts: RemoteHosts, private val scope: CoroutineScope) {
  /** The host being connected, by name, for the dialogs' titles. */
  var hostName by mutableStateOf("")
    private set

  var connecting by mutableStateOf(false)
    private set

  var prompt by mutableStateOf<PendingPrompt?>(null)
    private set

  var failure by mutableStateOf<String?>(null)
    private set

  private var job: Job? = null
  private var retry: () -> Unit = {}

  /** Connects host [hostId], named [name], then runs [onConnected]; a connect already running keeps going instead. */
  fun connect(hostId: String, name: String, onConnected: () -> Unit = {}) {
    if (connecting) return
    hostName = name
    retry = { connect(hostId, name, onConnected) }
    failure = null
    connecting = true
    job = scope.launch {
      try {
        remoteHosts.connect(hostId) { asked, reply -> prompt = PendingPrompt(asked, reply) }
        onConnected()
      } catch (rejected: HostRejected) {
        if (rejected.code != RemoteFailure.CONNECT_CANCELLED) failure = rejected.error
      } catch (error: IOException) {
        failure = error.message.orEmpty()
      } finally {
        prompt = null
        connecting = false
      }
    }
  }

  /** Answers the shown prompt; null cancels the connect. */
  fun answer(text: String?) {
    val shown = prompt ?: return
    prompt = null
    scope.launch {
      // The connect has already ended when the answer can't be sent; it reports why itself.
      if (!shown.reply.answer(text) && text == null) job?.cancel()
    }
  }

  fun dismissFailure() {
    failure = null
  }

  /** Runs the failed connect again. */
  fun tryAgain() {
    failure = null
    retry()
  }
}

/** A prompt waiting for its answer. */
internal class PendingPrompt(val prompt: RemotePrompt, val reply: RemoteReply)

/** A [RemoteConnector] that lives as long as the calling screen. */
@Composable
internal fun rememberRemoteConnector(): RemoteConnector {
  val remoteHosts = LocalContext.current.appGraph.remoteHosts
  val scope = rememberCoroutineScope()
  return remember(remoteHosts, scope) { RemoteConnector(remoteHosts, scope) }
}

/** The dialogs of [connector]'s connect: the shown prompt, then the failure, if any. */
@Composable
internal fun RemoteConnectDialogs(connector: RemoteConnector) {
  val pending = connector.prompt
  if (pending != null) {
    val asked = pending.prompt
    when (asked.kind) {
      RemotePrompt.Kind.HostKey -> HostKeyDialog(connector.hostName, asked, connector::answer)
      else -> SecretDialog(connector.hostName, asked, connector::answer)
    }
    return
  }
  val failure = connector.failure ?: return
  PipkinDialog(
    title = stringResource(R.string.remote_connect_failed_title, connector.hostName),
    text = failure,
    icon = R.drawable.ic_lucide_circle_alert,
    confirm = DialogConfirm(stringResource(R.string.remote_try_again), connector::tryAgain),
    dismissLabel = stringResource(R.string.remote_close),
    onDismiss = connector::dismissFailure,
  )
}

/** The first connection to a host: its key type and fingerprint, to check before trusting it. */
@Composable
private fun HostKeyDialog(host: String, prompt: RemotePrompt, onAnswer: (String?) -> Unit) {
  val colors = pipkinColors
  PipkinDialog(
    title = stringResource(R.string.remote_host_key_title, host),
    text = stringResource(R.string.remote_host_key_text),
    icon = R.drawable.ic_lucide_shield_check,
    confirm = DialogConfirm(stringResource(R.string.remote_host_key_trust), { onAnswer(TRUST) }),
    dismissLabel = stringResource(R.string.remote_cancel),
    onDismiss = { onAnswer(null) },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 14.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(colors.surface0)
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
    ) {
      val type = prompt.keyType
      if (type != null) {
        Text(
          text = type,
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
          fontWeight = FontWeight.SemiBold,
        )
      }
      Text(
        // Without a parsed fingerprint, ssh's own words carry it.
        text = prompt.fingerprint ?: prompt.text,
        style = pathTextStyle,
        color = colors.foreground,
        fontFamily = PipkinFonts.mono,
        fontSize = PipkinTokens.FontSize.code,
      )
    }
  }
}

/** A password, a key's passphrase, or another question in `ssh`'s words, answered in a field masked for secrets. */
@Composable
private fun SecretDialog(host: String, prompt: RemotePrompt, onAnswer: (String?) -> Unit) {
  val secret = prompt.kind != RemotePrompt.Kind.Other
  var value by remember(prompt) { mutableStateOf(TextFieldValue()) }
  var shown by remember(prompt) { mutableStateOf(!secret) }
  val title = when (prompt.kind) {
    RemotePrompt.Kind.Password -> stringResource(R.string.remote_password_title, host)
    RemotePrompt.Kind.Passphrase -> stringResource(R.string.remote_passphrase_title)
    else -> stringResource(R.string.remote_question_title, host)
  }
  val hint = when (prompt.kind) {
    RemotePrompt.Kind.Password -> stringResource(R.string.remote_password_hint)
    RemotePrompt.Kind.Passphrase -> stringResource(R.string.remote_passphrase_hint)
    else -> null
  }
  PipkinDialog(
    title = title,
    // A passphrase prompt names the key file; a question is only in ssh's words.
    text = prompt.text.takeIf { prompt.kind != RemotePrompt.Kind.Password && it.isNotBlank() },
    confirm = DialogConfirm(
      label = stringResource(if (secret) R.string.remote_connect else R.string.remote_answer),
      onClick = { onAnswer(value.text) },
    ),
    dismissLabel = stringResource(R.string.remote_cancel),
    onDismiss = { onAnswer(null) },
  ) {
    DialogTextField(
      value = value,
      onValueChange = { value = it },
      placeholder = hint,
      visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(
        keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
        autoCorrectEnabled = false,
      ),
      trailing = if (secret) {
        { ShowToggle(shown) { shown = !shown } }
      } else {
        null
      },
    )
  }
}

/** Show or Hide, at the end of a secret's field. */
@Composable
internal fun ShowToggle(shown: Boolean, onToggle: () -> Unit) {
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(10.dp))
      .clickable(role = Role.Button, onClick = onToggle)
      .heightIn(min = 36.dp)
      .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(if (shown) R.string.remote_hide_secret else R.string.remote_show_secret),
      color = pipkinColors.accent,
      fontSize = PipkinTokens.FontSize.sm,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

/** Any text trusts a host key; `yes` reads well in logs. */
private const val TRUST = "yes"
