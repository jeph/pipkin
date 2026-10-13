package io.jeph.pipkin.ui.chat

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinSheet
import io.jeph.pipkin.ui.components.SheetRow
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import org.json.JSONObject

private enum class PrimaryAction { Send, Stop, Idle, Sending }

private const val MAX_PICKED_IMAGES = 6

/**
 * Paseo's composer: attachment tray, growing text field and a toolbar with attach, model chip, the
 * context meter, [trailingControls] (reserved for voice input) and the primary send/stop button.
 */
@Composable
internal fun Composer(
  controller: ChatController,
  model: JSONObject?,
  thinkingLevel: String?,
  busy: Boolean,
  onOpenModelSheet: () -> Unit,
  onOpenThinkingSheet: () -> Unit,
  modifier: Modifier = Modifier,
  trailingControls: @Composable RowScope.() -> Unit = {},
) {
  val colors = pipkinColors
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current
  val hideKeyboard = {
    keyboard?.hide()
    focus.clearFocus()
  }
  val send = { if (controller.send()) hideKeyboard() }
  val shape = RoundedCornerShape(PipkinTokens.Radius.xxl)
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s16),
    contentAlignment = Alignment.TopCenter,
  ) {
    // Not clipped: the attachment remove buttons overhang their thumbnails.
    Column(
      modifier =
        Modifier
          .widthIn(max = PipkinTokens.contentMaxWidth)
          .fillMaxWidth()
          .background(colors.surface1, shape)
          .border(1.dp, colors.borderAccent, shape)
          .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s8),
    ) {
      if (controller.attachments.isNotEmpty()) {
        AttachmentTray(controller.attachments, onRemove = { controller.attachments.remove(it) })
      }
      // Weighted, so the tray and toolbar are measured first and long drafts scroll instead of pushing them off.
      ComposerField(controller, onRan = hideKeyboard, Modifier.weight(1f, fill = false))
      Spacer(modifier = Modifier.height(PipkinTokens.Spacing.s12))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      ) {
        // The left group takes what the trailing buttons leave, and inside it the model chip is
        // measured last, so on a narrow screen the model name is cut before send is pushed off.
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
        ) {
          AttachButton(controller, model)
          ModelChip(model, onOpenModelSheet, Modifier.weight(1f, fill = false))
          if (supportsThinking(model)) ThinkingChip(thinkingLevel, onOpenThinkingSheet)
        }
        val contextUsage by controller.process.contextUsage.collectAsStateWithLifecycle()
        ContextMeter(contextUsage, controller.contextDetailsOpen) { controller.contextDetailsOpen = it }
        trailingControls()
        // Pi answers a prompt within moments by starting its run, so Stop shows from the press on.
        val action = when {
          controller.sendingCommand -> PrimaryAction.Sending
          controller.sending -> PrimaryAction.Stop
          controller.canSend -> PrimaryAction.Send
          busy -> PrimaryAction.Stop
          else -> PrimaryAction.Idle
        }
        PrimaryButton(action, onSend = send, onStop = controller::abort)
      }
    }
  }
}

/** One horizontally scrolling row, so any number of attachments never pushes the toolbar off screen. */
@Composable
private fun AttachmentTray(attachments: List<Attachment>, onRemove: (Attachment) -> Unit) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(bottom = PipkinTokens.Spacing.s8)
        .horizontalScroll(rememberScrollState())
        // Inside the scroll, which clips: room for the remove badges that overhang each chip by 8dp.
        .padding(start = PipkinTokens.Spacing.s8, top = PipkinTokens.Spacing.s8),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    attachments.forEach { attachment ->
      when (attachment) {
        is Attachment.Image -> AttachmentThumbnail(attachment, onRemove = { onRemove(attachment) })
        is Attachment.File -> FileChip(attachment, onRemove = { onRemove(attachment) })
      }
    }
  }
}

@Composable
private fun AttachmentThumbnail(attachment: Attachment.Image, onRemove: () -> Unit) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.md)
  Box(modifier = Modifier.size(48.dp)) {
    Image(
      bitmap = attachment.thumbnail,
      contentDescription = stringResource(R.string.chat_image),
      modifier = Modifier.matchParentSize().clip(shape).border(1.dp, colors.borderAccent, shape),
      contentScale = ContentScale.Crop,
    )
    RemoveBadge(onRemove)
  }
}

/** A 48dp tray chip for a picked file: icon, name and size, with the same remove badge as images. */
@Composable
private fun FileChip(file: Attachment.File, onRemove: () -> Unit) {
  val colors = pipkinColors
  val context = LocalContext.current
  val shape = RoundedCornerShape(PipkinTokens.Radius.md)
  Box {
    Row(
      modifier =
        Modifier
          .height(48.dp)
          .widthIn(max = 200.dp)
          .background(colors.surface2, shape)
          .border(1.dp, colors.borderAccent, shape)
          .padding(horizontal = PipkinTokens.Spacing.s8),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      LucideIcon(icon = R.drawable.ic_lucide_file_text, contentDescription = null, size = PipkinTokens.IconSize.md)
      Column {
        Text(
          text = file.name,
          color = colors.foreground,
          fontSize = PipkinTokens.FontSize.sm,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
          text = Formatter.formatShortFileSize(context, file.bytes.size.toLong()),
          color = colors.foregroundMuted,
          fontSize = 11.sp,
          maxLines = 1,
        )
      }
    }
    RemoveBadge(onRemove)
  }
}

@Composable
private fun BoxScope.RemoveBadge(onRemove: () -> Unit) {
  val colors = pipkinColors
  Box(
    modifier =
      Modifier
        .align(Alignment.TopStart)
        .offset(x = (-8).dp, y = (-8).dp)
        .size(24.dp)
        .clip(CircleShape)
        .background(colors.surface2)
        .border(1.dp, colors.border, CircleShape)
        .clickable(onClick = onRemove),
    contentAlignment = Alignment.Center,
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_x,
      contentDescription = stringResource(R.string.chat_remove_attachment),
      size = PipkinTokens.IconSize.xs,
    )
  }
}

/**
 * The draft field; it reports focus for the slash menu and lets the menu take its keys. It grows to half
 * the height the chat has left, so the stream keeps the other half, then scrolls.
 */
@Composable
private fun ComposerField(controller: ChatController, onRan: () -> Unit, modifier: Modifier) {
  val colors = pipkinColors
  val style = TextStyle(color = colors.foreground, fontSize = PipkinTokens.FontSize.content)
  BasicTextField(
    value = controller.draft,
    onValueChange = { controller.slash.onDraftChange(it, onRan) },
    modifier = modifier
      .fillMaxWidth()
      .layout { measurable, constraints ->
        // As a weighted child, maxHeight is what the composer has after its tray and toolbar.
        val cap = if (constraints.hasBoundedHeight) constraints.maxHeight / 2 else constraints.maxHeight
        val placeable = measurable.measure(constraints.copy(maxHeight = cap.coerceAtLeast(constraints.minHeight)))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      }
      .heightIn(min = 30.dp)
      .onFocusChanged { controller.composerFocused = it.isFocused }
      .onPreviewKeyEvent { controller.slash.onKey(it, onRan) },
    textStyle = style,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    cursorBrush = SolidColor(colors.accent),
    decorationBox = { inner ->
      Box(contentAlignment = Alignment.CenterStart) {
        if (controller.draft.text.isEmpty()) {
          Text(text = stringResource(R.string.chat_placeholder), style = style.copy(color = colors.foregroundMuted))
        }
        inner()
      }
    },
  )
}

/** Opens the attach sheet: photos (only when the model accepts images) or any files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachButton(controller: ChatController, model: JSONObject?) {
  var sheetOpen by remember { mutableStateOf(false) }
  val photos =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_IMAGES)) { uris ->
      if (uris.isNotEmpty()) controller.addImages(uris)
    }
  val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    if (uris.isNotEmpty()) controller.addFiles(uris)
  }
  IconButton28(
    icon = R.drawable.ic_lucide_plus,
    contentDescription = stringResource(R.string.chat_attach),
    onClick = { sheetOpen = true },
  )
  if (!sheetOpen) return
  val acceptsImages = acceptsImages(model)
  PipkinSheet(onDismissRequest = { sheetOpen = false }) {
    SheetRow(
      label = stringResource(R.string.chat_attach_photos),
      icon = R.drawable.ic_lucide_image,
      value = if (acceptsImages) null else stringResource(R.string.chat_images_text_only),
      enabled = acceptsImages,
      onClick = {
        sheetOpen = false
        photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      },
    )
    SheetRow(
      label = stringResource(R.string.chat_attach_files),
      icon = R.drawable.ic_lucide_paperclip,
      onClick = {
        sheetOpen = false
        files.launch(arrayOf("*/*"))
      },
    )
  }
}

@Composable
private fun PrimaryButton(action: PrimaryAction, onSend: () -> Unit, onStop: () -> Unit) {
  val colors = pipkinColors
  val send = stringResource(R.string.chat_send)
  when (action) {
    PrimaryAction.Sending -> Box(
      modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.accent),
      contentAlignment = Alignment.Center,
    ) {
      SimmeringPot(tint = colors.accentForeground)
    }

    PrimaryAction.Send, PrimaryAction.Idle -> IconButton28(
      icon = R.drawable.ic_lucide_arrow_up,
      contentDescription = send,
      onClick = onSend,
      enabled = action == PrimaryAction.Send,
      background = colors.accent,
      tint = colors.accentForeground,
    )

    PrimaryAction.Stop -> IconButton28(
      icon = R.drawable.ic_lucide_square,
      contentDescription = stringResource(R.string.chat_stop),
      onClick = onStop,
      background = colors.accent,
      tint = colors.accentForeground,
    )
  }
}

/**
 * The messages Pi has queued but not taken yet, one per line as typed, so the user sees what is about to steer the
 * run; attachments show only as a count. Edit takes them back into the composer; Send now stops the run and sends
 * them at once. Both are off while Pi may be taking the queue for its next turn, since it then delivers the
 * messages regardless.
 */
@Composable
internal fun SteeringBox(
  summary: SteeringSummary,
  editEnabled: Boolean,
  sendNowEnabled: Boolean,
  onEdit: () -> Unit,
  onSendNow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  Box(modifier = modifier.fillMaxWidth().padding(horizontal = PipkinTokens.Spacing.s16), Alignment.TopCenter) {
    Column(
      modifier =
        Modifier
          .widthIn(max = PipkinTokens.contentMaxWidth)
          .fillMaxWidth()
          .clip(RoundedCornerShape(PipkinTokens.Radius.lg))
          .background(colors.surface2)
          .padding(start = PipkinTokens.Spacing.s12, end = PipkinTokens.Spacing.s4, bottom = PipkinTokens.Spacing.s12),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(R.string.chat_steering),
          modifier = Modifier.weight(1f),
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
        )
        TextButton(onClick = onEdit, enabled = editEnabled) { Text(text = stringResource(R.string.chat_steering_edit)) }
        TextButton(onClick = onSendNow, enabled = sendNowEnabled) {
          Text(text = stringResource(R.string.chat_steering_send_now))
        }
      }
      if (summary.text.isNotEmpty()) {
        Text(
          text = summary.text,
          modifier = Modifier.padding(end = PipkinTokens.Spacing.s8),
          color = colors.foreground,
          fontSize = PipkinTokens.FontSize.base,
          maxLines = STEERING_MAX_LINES,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (summary.attachments > 0) {
        Row(
          modifier = Modifier.padding(top = PipkinTokens.Spacing.s4),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
        ) {
          LucideIcon(
            R.drawable.ic_lucide_paperclip,
            contentDescription = null,
            size = PipkinTokens.IconSize.sm,
            tint = colors.foregroundMuted,
          )
          Text(
            text = pluralStringResource(R.plurals.chat_steering_attachments, summary.attachments, summary.attachments),
            color = colors.foregroundMuted,
            fontSize = PipkinTokens.FontSize.sm,
          )
        }
      }
    }
  }
}

/** Long enough to read several queued messages, short enough to leave the stream most of the screen. */
private const val STEERING_MAX_LINES = 6
