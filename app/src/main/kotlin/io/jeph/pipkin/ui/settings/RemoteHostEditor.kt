package io.jeph.pipkin.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.RemoteHosts
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteAuth
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.components.DialogConfirm
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinDialog
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.remote.RemoteConnectDialogs
import io.jeph.pipkin.ui.remote.RemoteConnector
import io.jeph.pipkin.ui.remote.rememberRemoteConnector
import io.jeph.pipkin.ui.sidebar.pathTextStyle
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adds a remote host ([hostId] null) or edits one: its name, address, port and sign-in, and for a saved host its
 * connection, its host key and removing it. A new host first needs OpenSSH in Termux, which [OpenSshGate] offers.
 * [onDone] gets the host's id after Save, or after Connect on a new host connected; [onRemoved] runs once a host is
 * removed; [onBack] leaves, asking first when there are unsaved edits.
 */
@Composable
internal fun RemoteHostEditorScreen(
  hostId: String?,
  actions: ShellActions,
  onBack: () -> Unit,
  onDone: (hostId: String) -> Unit,
  onRemoved: () -> Unit,
) {
  val editor = rememberHostEditor(hostId, actions)
  val remotes by editor.remoteHosts.state.collectAsStateWithLifecycle()
  val saved = remotes.host(editor.form.id)
  val dirty = editor.form.dirty(saved)
  LaunchedEffect(editor) { editor.remoteHosts.refresh() }
  BackHandler(enabled = dirty) { editor.dialog = EditorDialog.Discard }
  SettingsPage(
    title = saved?.name ?: stringResource(R.string.remote_add_host),
    onBack = { if (dirty) editor.dialog = EditorDialog.Discard else onBack() },
  ) {
    when {
      hostId != null && saved == null -> SettingsCaption(text = stringResource(R.string.remote_host_gone))

      // Once saved, the host exists and its editor no longer needs the install offer.
      !editor.form.editing && !OpenSshGate(editor.remoteHosts) -> Unit

      else -> {
        HostFields(editor.form, editor.remoteHosts, saved)
        SignInSection(editor, saved)
        if (saved != null) StatusSection(editor, saved)
        editor.form.formError?.let { ErrorPanel(title = null, message = it) }
        EditorActions(editor, saved, dirty, onDone)
      }
    }
  }
  RemoteConnectDialogs(editor.connector)
  EditorDialogs(editor, saved, onDiscard = onBack, onRemoved = onRemoved)
}

/** A dialog the editor asks with. */
private enum class EditorDialog { Discard, ForgetKey, ForgetPassword, Remove }

/** The editor's [form] and what its buttons do, run in the screen's [scope] so a dialog closing doesn't cancel them. */
@Stable
private class HostEditor(
  val remoteHosts: RemoteHosts,
  val actions: ShellActions,
  val form: RemoteHostForm,
  val connector: RemoteConnector,
  private val scope: CoroutineScope,
) {
  var dialog by mutableStateOf<EditorDialog?>(null)

  val busy: Boolean get() = form.saving || connector.connecting

  /** Saves the fields, then gives [onSaved] the host's id. */
  fun save(onSaved: (hostId: String) -> Unit) {
    scope.launch { saveFields()?.let { onSaved(it.id) } }
  }

  /** Connects the host, then runs [onConnected]; saves first when the fields changed, so it uses what the user sees. */
  fun connect(onConnected: () -> Unit) {
    scope.launch {
      val current = remoteHosts.state.value.host(form.id)
      val host = if (current == null || form.dirty(current)) saveFields() else current
      if (host != null) connector.connect(host.id, host.name, onConnected)
    }
  }

  fun disconnect(hostId: String) = attempt { remoteHosts.disconnect(hostId) }

  fun forgetHostKey(hostId: String) = attempt { remoteHosts.forgetHostKey(hostId) }

  fun forgetPassword(hostId: String) = attempt { withContext(Dispatchers.IO) { remoteHosts.forgetPassword(hostId) } }

  /** Removes the workspaces [cwds] the way the sidebar does, archiving their chats, then forgets host [hostId]. */
  fun remove(hostId: String, cwds: List<String>, onRemoved: () -> Unit) {
    actions.removeWorkspaces(cwds)
    attempt {
      remoteHosts.remove(hostId)
      onRemoved()
    }
  }

  private suspend fun saveFields(): RemoteHost? = form.save(remoteHosts, remoteHosts.state.value.host(form.id))

  /** Runs [action] on the saved host, showing why it failed in the form. */
  private fun attempt(action: suspend () -> Unit) {
    form.formError = null
    scope.launch {
      try {
        action()
      } catch (rejected: HostRejected) {
        form.formError = rejected.error
      } catch (failure: IOException) {
        form.formError = failure.message.orEmpty()
      }
    }
  }
}

@Composable
private fun rememberHostEditor(hostId: String?, actions: ShellActions): HostEditor {
  val remoteHosts = LocalContext.current.appGraph.remoteHosts
  val form = rememberSaveable(saver = RemoteHostForm.Saver) {
    RemoteHostForm.of(hostId?.let(remoteHosts.state.value::host))
  }
  val connector = rememberRemoteConnector()
  val scope = rememberCoroutineScope()
  return remember(form, connector, scope, actions) { HostEditor(remoteHosts, actions, form, connector, scope) }
}

/**
 * The name, address and port. A change of address or port that would make Save forget the saved password says so under
 * the address, since the password was for another server.
 */
@Composable
private fun HostFields(form: RemoteHostForm, remoteHosts: RemoteHosts, saved: RemoteHost?) {
  val config = form.auth == RemoteAuth.Config
  val savedPasswords by remoteHosts.savedPasswords.collectAsStateWithLifecycle()
  val draft = form.draft(saved)
  val forgetsPassword = saved != null && saved.id in savedPasswords && draft != null &&
    draft.auth == RemoteAuth.Password && remoteHosts.forgetsPassword(saved, draft)
  EditorField(
    label = stringResource(R.string.remote_name),
    value = form.name,
    hint = stringResource(R.string.remote_name_hint),
    error = form.errors[RemoteHostField.Name],
    keyboardType = KeyboardType.Text,
    onChange = {
      form.name = it
      form.edited(RemoteHostField.Name)
    },
  )
  // A config host's address is its Host alias in ~/.ssh/config, which also sets its port.
  EditorField(
    label = stringResource(if (config) R.string.remote_alias else R.string.remote_address),
    value = form.address,
    hint = stringResource(if (config) R.string.remote_alias_hint else R.string.remote_address_hint),
    error = form.errors[RemoteHostField.Address],
    warning = saved?.takeIf { forgetsPassword }?.let {
      stringResource(R.string.remote_address_forgets_password, it.displayAddress)
    },
    keyboardType = KeyboardType.Uri,
    onChange = {
      form.address = it
      form.edited(RemoteHostField.Address)
    },
  )
  if (config) return
  EditorField(
    label = stringResource(R.string.remote_port),
    value = form.port,
    hint = null,
    error = form.errors[RemoteHostField.Port],
    keyboardType = KeyboardType.Number,
    onChange = {
      form.port = it
      form.edited(RemoteHostField.Port)
    },
  )
}

/** A field with its [error] under it, or else its [warning], which doesn't stop Save. */
@Composable
private fun EditorField(
  label: String,
  value: String,
  hint: String?,
  error: String?,
  keyboardType: KeyboardType,
  onChange: (String) -> Unit,
  warning: String? = null,
) {
  val note = error ?: warning
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(label)
    OutlinedTextField(
      value = value,
      onValueChange = onChange,
      modifier = Modifier.fillMaxWidth(),
      shape = SettingsFieldShape,
      colors = settingsFieldColors(),
      placeholder = hint?.let { { Text(it) } },
      supportingText = note?.let {
        { Text(it, color = if (error == null) pipkinColors.dotWarning else Color.Unspecified) }
      },
      isError = error != null,
      keyboardOptions = KeyboardOptions(
        keyboardType = keyboardType,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Next,
      ),
      singleLine = true,
    )
  }
}

/**
 * The three ways to sign in, as radio rows. Pipkin's key shows its public line to copy, made first if needed; a saved
 * password host's Password says the password is saved, with Forget.
 */
@Composable
private fun SignInSection(editor: HostEditor, saved: RemoteHost?) {
  val form = editor.form
  val savedPasswords by editor.remoteHosts.savedPasswords.collectAsStateWithLifecycle()
  val rejected = saved?.let { savedPasswords[it.id] }
  SettingsSection(title = stringResource(R.string.remote_sign_in)) {
    RemoteAuth.entries.forEachIndexed { index, auth ->
      if (index > 0) SettingsDivider()
      AuthRow(auth = auth, passwordSaved = rejected == false, selected = form.auth == auth, onSelect = {
        form.auth =
          auth
      }) {
        if (auth == RemoteAuth.Key && form.auth == RemoteAuth.Key) PublicKeyLine(editor.remoteHosts)
        if (auth == RemoteAuth.Password && form.auth == RemoteAuth.Password && rejected != null) {
          SavedPasswordLine(rejected, enabled = !editor.busy) { editor.dialog = EditorDialog.ForgetPassword }
        }
      }
    }
  }
}

@Composable
private fun AuthRow(
  auth: RemoteAuth,
  passwordSaved: Boolean,
  selected: Boolean,
  onSelect: () -> Unit,
  extra: @Composable () -> Unit,
) {
  val colors = pipkinColors
  val (title, detail) = when (auth) {
    RemoteAuth.Key -> R.string.remote_auth_key to R.string.remote_auth_key_detail

    RemoteAuth.Password ->
      R.string.remote_auth_password to
        if (passwordSaved) R.string.remote_auth_password_saved_detail else R.string.remote_auth_password_detail

    RemoteAuth.Config -> R.string.remote_auth_config to R.string.remote_auth_config_detail
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
      .padding(horizontal = PipkinTokens.Spacing.s8, vertical = PipkinTokens.Spacing.s8),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
  ) {
    // The row is the selectable target; the button only shows the state.
    RadioButton(
      selected = selected,
      onClick = null,
      modifier = Modifier.padding(PipkinTokens.Spacing.s8),
      colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.foregroundMuted),
    )
    Column(
      modifier = Modifier.weight(1f).padding(top = PipkinTokens.Spacing.s6, end = PipkinTokens.Spacing.s8),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
    ) {
      Text(stringResource(title), color = colors.foreground, fontSize = PipkinTokens.FontSize.base)
      Text(stringResource(detail), color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
      extra()
    }
  }
}

/** That the host's password is saved on this phone, or that the server [rejected] it, with Forget. */
@Composable
private fun SavedPasswordLine(rejected: Boolean, enabled: Boolean, onForget: () -> Unit) {
  val colors = pipkinColors
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    LucideIcon(
      icon = if (rejected) R.drawable.ic_lucide_triangle_alert else R.drawable.ic_lucide_shield_check,
      contentDescription = null,
      tint = if (rejected) colors.dotWarning else colors.dotSuccess,
      size = PipkinTokens.IconSize.sm,
    )
    Text(
      text = stringResource(if (rejected) R.string.remote_password_saved_rejected else R.string.remote_password_saved),
      modifier = Modifier.weight(1f),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.sm,
      fontWeight = FontWeight.SemiBold,
    )
    SmallButton(label = stringResource(R.string.remote_forget_password), onClick = onForget, enabled = enabled)
  }
}

/** Pipkin's public key line in the code font with Copy; asking for it makes the key the first time. */
@Composable
private fun PublicKeyLine(remoteHosts: RemoteHosts) {
  val colors = pipkinColors
  val context = LocalContext.current
  val remotes by remoteHosts.state.collectAsStateWithLifecycle()
  var keyError by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(remoteHosts) {
    if (remoteHosts.state.value.publicKey != null) return@LaunchedEffect
    keyError = try {
      remoteHosts.publicKey()
      null
    } catch (rejected: HostRejected) {
      rejected.error
    } catch (failure: IOException) {
      failure.message.orEmpty()
    }
  }
  keyError?.let {
    Text(it, color = colors.destructive, fontSize = PipkinTokens.FontSize.sm)
    return
  }
  val key = remotes.publicKey ?: return
  val label = stringResource(R.string.remote_key_label)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = PipkinTokens.Spacing.s4)
      .background(colors.surface0, RoundedCornerShape(PipkinTokens.Radius.md))
      .padding(start = PipkinTokens.Spacing.s12, top = PipkinTokens.Spacing.s8, bottom = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = key,
      modifier = Modifier.weight(1f),
      style = pathTextStyle,
      color = colors.foreground,
      fontFamily = PipkinFonts.mono,
      fontSize = PipkinTokens.FontSize.code,
      lineHeight = PipkinTokens.LineHeight.code,
    )
    RoundIconButton(
      icon = R.drawable.ic_lucide_copy,
      contentDescription = stringResource(R.string.remote_copy_key),
      onClick = { copyToClipboard(context, label, key) },
      modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s4),
      tint = colors.accent,
    )
  }
}

/** A saved host's connection, with Connect or Disconnect. */
@Composable
private fun StatusSection(editor: HostEditor, host: RemoteHost) {
  val colors = pipkinColors
  val connected by editor.remoteHosts.connected.collectAsStateWithLifecycle()
  val isConnected = connected[host.id] == true
  SettingsSection(title = stringResource(R.string.remote_status)) {
    SettingsRow(
      title = stringResource(if (isConnected) R.string.remote_connected else R.string.remote_not_connected),
      subtitle = host.displayAddress,
      leading = {
        LucideIcon(
          icon = R.drawable.ic_lucide_server,
          contentDescription = null,
          tint = if (isConnected) colors.accent else colors.foregroundMuted,
        )
      },
    ) {
      SmallButton(
        label = stringResource(if (isConnected) R.string.remote_disconnect else R.string.remote_connect),
        enabled = !editor.busy,
        onClick = { if (isConnected) editor.disconnect(host.id) else editor.connect {} },
      )
    }
  }
}

/**
 * A new host's Connect, which saves first, and Save; a saved host's Save, Forget host key, prominent with a warning
 * after the host's key changed, and Remove host.
 */
@Composable
private fun EditorActions(editor: HostEditor, saved: RemoteHost?, dirty: Boolean, onDone: (hostId: String) -> Unit) {
  val busy = editor.busy
  if (saved == null) {
    SettingsSaveButton(
      label = stringResource(R.string.remote_connect),
      saving = busy,
      enabled = !busy,
      onClick = { editor.connect { onDone(editor.form.id) } },
    )
    TextButton(onClick = { editor.save(onDone) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
      Text(stringResource(R.string.remote_save))
    }
    return
  }
  val keyChanged by editor.remoteHosts.hostKeyChanged.collectAsStateWithLifecycle()
  val changed = saved.id in keyChanged
  val forgetKey = { editor.dialog = EditorDialog.ForgetKey }
  if (changed) {
    ErrorPanel(
      title = stringResource(R.string.remote_key_changed_title, saved.name),
      message = stringResource(R.string.remote_key_changed_warning, saved.name),
    )
    SmallButton(label = stringResource(R.string.remote_forget_key), onClick = forgetKey, enabled = !busy)
  }
  SettingsSaveButton(
    label = stringResource(R.string.remote_save),
    saving = editor.form.saving,
    enabled = dirty && !busy,
    onClick = { editor.save(onDone) },
  )
  if (!changed) {
    TextButton(onClick = forgetKey, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
      Text(stringResource(R.string.remote_forget_key))
    }
  }
  TextButton(
    onClick = { editor.dialog = EditorDialog.Remove },
    modifier = Modifier.fillMaxWidth(),
    enabled = !busy,
    colors = ButtonDefaults.textButtonColors(contentColor = pipkinColors.destructive),
  ) {
    Text(stringResource(R.string.remote_remove))
  }
}

@Composable
private fun EditorDialogs(editor: HostEditor, host: RemoteHost?, onDiscard: () -> Unit, onRemoved: () -> Unit) {
  val dismiss = { editor.dialog = null }
  when (editor.dialog) {
    null -> Unit

    EditorDialog.Discard -> DiscardDialog(
      onDiscard = {
        dismiss()
        onDiscard()
      },
      onDismiss = dismiss,
    )

    EditorDialog.ForgetKey -> if (host != null) {
      PipkinDialog(
        title = stringResource(R.string.remote_forget_key_title, host.name),
        text = stringResource(R.string.remote_forget_key_text),
        icon = R.drawable.ic_lucide_triangle_alert,
        confirm = DialogConfirm(
          label = stringResource(R.string.remote_forget_key),
          onClick = {
            dismiss()
            editor.forgetHostKey(host.id)
          },
          destructive = true,
        ),
        dismissLabel = stringResource(R.string.remote_cancel),
        onDismiss = dismiss,
      )
    }

    EditorDialog.ForgetPassword -> if (host != null) {
      PipkinDialog(
        title = stringResource(R.string.remote_forget_password_title, host.name),
        text = stringResource(R.string.remote_forget_password_text, host.name),
        icon = R.drawable.ic_lucide_key_round,
        confirm = DialogConfirm(
          label = stringResource(R.string.remote_forget_password),
          onClick = {
            dismiss()
            editor.forgetPassword(host.id)
          },
          destructive = true,
        ),
        dismissLabel = stringResource(R.string.remote_cancel),
        onDismiss = dismiss,
      )
    }

    EditorDialog.Remove -> if (host != null) {
      RemoveHostDialog(host, editor.actions, onDismiss = dismiss) { cwds -> editor.remove(host.id, cwds, onRemoved) }
    }
  }
}
