package io.jeph.pipkin.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.net.toUri
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.settings.copyToClipboard
import io.jeph.pipkin.ui.settings.subscriptions
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in to a provider Pi ships: OAuth for a subscription, or Pi asking for the API key. The login
 * runs while the sheet is shown; dismissing it cancels the login, which closes the host channel and
 * aborts Pi's flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoginSheet(providerId: String, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val accounts = context.appGraph.accountRepository
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val currentOnDismiss by rememberUpdatedState(onDismiss)
  var attempt by remember { mutableIntStateOf(0) }
  val flow = remember(attempt) { LoginFlowState() }
  val noBrowser = stringResource(R.string.login_no_browser)
  val provider = subscriptions.firstOrNull { it.id == providerId }
  val open: (String) -> Unit = { url ->
    if (!openUrl(context, url)) flow.status = AuthStatusLine(noBrowser, emptyList())
  }
  LaunchedEffect(flow) {
    flow.run(accounts, providerId)
    if (flow.outcome == LoginOutcome.SignedIn) {
      delay(1_000)
      sheetState.hide()
      currentOnDismiss()
    }
  }
  val browserUrl = flow.browser?.url
  // Pi waits for the browser round trip, so open the page as soon as it is known.
  LaunchedEffect(browserUrl) {
    if (browserUrl != null) open(browserUrl)
  }
  PipkinSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = PipkinTokens.Spacing.s16),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    ) {
      Text(
        text = provider?.let { stringResource(it.loginTitle) } ?: providerId,
        color = pipkinColors.foreground,
        fontSize = PipkinTokens.FontSize.lg,
      )
      LoginBody(
        flow = flow,
        usesApiKey = provider?.usesApiKey == true,
        onOpen = open,
        onRetry = { attempt += 1 },
      )
    }
  }
}

@Composable
private fun LoginBody(flow: LoginFlowState, usesApiKey: Boolean, onOpen: (String) -> Unit, onRetry: () -> Unit) {
  val colors = pipkinColors
  when (val outcome = flow.outcome) {
    LoginOutcome.SignedIn -> LeadingRow(
      text = stringResource(if (usesApiKey) R.string.login_key_saved else R.string.login_signed_in),
    ) {
      LucideIcon(icon = R.drawable.ic_lucide_check, contentDescription = null, tint = colors.accent)
    }

    is LoginOutcome.Failed -> {
      LeadingRow(text = outcome.message ?: stringResource(R.string.login_failed)) {
        LucideIcon(icon = R.drawable.ic_lucide_circle_alert, contentDescription = null, tint = colors.destructive)
      }
      Button(onClick = onRetry) { Text(stringResource(R.string.login_try_again)) }
    }

    LoginOutcome.Running -> RunningContent(flow = flow, onOpen = onOpen)
  }
}

@Composable
private fun RunningContent(flow: LoginFlowState, onOpen: (String) -> Unit) {
  val scope = rememberCoroutineScope()
  val browser = flow.browser
  val deviceCode = flow.deviceCode
  val status = flow.status
  val prompt = flow.prompt
  if (browser == null && deviceCode == null && status == null && prompt == null) {
    LeadingRow(text = stringResource(R.string.login_starting), color = pipkinColors.foregroundMuted) { SimmeringPot() }
  }
  browser?.let { BrowserBlock(step = it, onOpen = onOpen) }
  deviceCode?.let { DeviceCodeBlock(step = it, onOpen = onOpen) }
  status?.let { line ->
    Text(text = line.message, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.base)
    line.links.forEach { link -> TextButton(onClick = { onOpen(link.url) }) { Text(link.label ?: link.url) } }
  }
  prompt?.let { pending ->
    PromptBlock(
      prompt = pending,
      onAnswer = { value ->
        flow.prompt = null
        scope.launch { pending.reply.answer(value) }
      },
    )
  }
}

@Composable
private fun BrowserBlock(step: BrowserStep, onOpen: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    LeadingRow(text = stringResource(R.string.login_browser)) { SimmeringPot() }
    step.instructions?.let {
      Text(text = it, color = pipkinColors.foregroundMuted, fontSize = PipkinTokens.FontSize.base)
    }
    OutlinedButton(onClick = { onOpen(step.url) }) { Text(stringResource(R.string.login_open_again)) }
  }
}

@Composable
private fun DeviceCodeBlock(step: DeviceCodeStep, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val colors = pipkinColors
  val copyLabel = stringResource(R.string.login_copy_code)
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    Text(
      text = stringResource(R.string.login_device_code),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      Text(
        text = step.userCode,
        color = colors.foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = PipkinTokens.FontSize.xxl,
      )
      IconButton28(
        icon = R.drawable.ic_lucide_copy,
        contentDescription = copyLabel,
        onClick = { copyToClipboard(context, copyLabel, step.userCode) },
        tint = colors.foregroundMuted,
      )
    }
    OutlinedButton(onClick = { onOpen(step.verificationUri) }) {
      Text(stringResource(R.string.login_open_uri, step.verificationUri))
    }
  }
}

@Composable
private fun PromptBlock(prompt: PendingPrompt, onAnswer: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    Text(text = prompt.message, color = pipkinColors.foreground, fontSize = PipkinTokens.FontSize.base)
    if (prompt.type == "select") {
      prompt.options.forEach { option ->
        SheetRow(label = option.label, value = option.description, onClick = { onAnswer(option.id) })
      }
    } else {
      TextPrompt(prompt = prompt, onAnswer = onAnswer)
    }
  }
}

/** `text`, `secret` and `manual_code` prompts. */
@Composable
private fun TextPrompt(prompt: PendingPrompt, onAnswer: (String) -> Unit) {
  var value by rememberSaveable(prompt.reply.id) { mutableStateOf("") }
  val secret = prompt.type == "secret"
  OutlinedTextField(
    value = value,
    onValueChange = { value = it },
    modifier = Modifier.fillMaxWidth(),
    placeholder = prompt.placeholder?.let { { Text(it) } },
    supportingText =
      if (prompt.type == "manual_code") {
        { Text(stringResource(R.string.login_manual_code_helper)) }
      } else {
        null
      },
    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
    singleLine = true,
  )
  Button(onClick = { onAnswer(value.trim()) }, enabled = value.isNotBlank()) {
    Text(stringResource(R.string.login_continue))
  }
}

/** A 14sp line with a 16dp leading icon or spinner. */
@Composable
private fun LeadingRow(text: String, color: Color = pipkinColors.foreground, leading: @Composable () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    leading()
    Text(text = text, color = color, fontSize = PipkinTokens.FontSize.base)
  }
}

private fun openUrl(context: Context, url: String): Boolean = try {
  context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
  true
} catch (_: ActivityNotFoundException) {
  false
}
