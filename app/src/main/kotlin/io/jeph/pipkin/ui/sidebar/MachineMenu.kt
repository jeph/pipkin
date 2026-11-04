package io.jeph.pipkin.ui.sidebar

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * The folder picker's machine row: the machine it browses, [host] or the phone when null, with a remote host's
 * connection, opening a menu of every machine, This phone and then [hosts] with their addresses and connections, and
 * Add host. Back closes the menu before the sheet.
 */
@Composable
internal fun MachineRow(
  host: RemoteHost?,
  hosts: List<RemoteHost>,
  connected: Map<String, Boolean>,
  onPick: (hostId: String?) -> Unit,
  onAddHost: () -> Unit,
) {
  val colors = pipkinColors
  var open by remember { mutableStateOf(false) }
  var width by remember { mutableIntStateOf(0) }
  val label = host?.name ?: stringResource(R.string.remote_machine_phone)
  val description = stringResource(R.string.remote_machine_choose, label)
  val padding = Modifier.padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s4)
  Box(modifier = Modifier.fillMaxWidth().then(padding)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .onSizeChanged { width = it.width }
        .clip(MACHINE_SHAPE)
        .background(colors.surface2)
        .clickable(role = Role.DropdownList) { open = true }
        .semantics { contentDescription = description }
        .heightIn(min = 44.dp)
        .padding(horizontal = PipkinTokens.Spacing.s12),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      LucideIcon(icon = machineIcon(host), contentDescription = null, tint = colors.foreground)
      Text(
        text = label,
        modifier = Modifier.weight(1f),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (host != null) ConnectionState(connected[host.id] == true)
      LucideIcon(icon = R.drawable.ic_lucide_chevron_down, contentDescription = null, tint = colors.foregroundMuted)
    }
    DropdownMenu(
      expanded = open,
      onDismissRequest = { open = false },
      modifier = Modifier.width(with(LocalDensity.current) { width.toDp() }),
      shape = RoundedCornerShape(PipkinTokens.Radius.xl),
      containerColor = colors.surface1,
    ) {
      MachineItems(
        host = host,
        hosts = hosts,
        connected = connected,
        onPick = {
          open = false
          onPick(it)
        },
        onAddHost = {
          open = false
          onAddHost()
        },
      )
    }
  }
}

/** The menu's rows: This phone, each host with its address and connection, then Add host. */
@Composable
private fun MachineItems(
  host: RemoteHost?,
  hosts: List<RemoteHost>,
  connected: Map<String, Boolean>,
  onPick: (hostId: String?) -> Unit,
  onAddHost: () -> Unit,
) {
  val colors = pipkinColors
  MachineItem(
    icon = R.drawable.ic_lucide_smartphone,
    title = stringResource(R.string.remote_machine_phone),
    selected = host == null,
    onClick = { onPick(null) },
  ) {
    MenuDetail(stringResource(R.string.remote_machine_phone_detail))
  }
  hosts.forEach { item ->
    MachineItem(
      icon = R.drawable.ic_lucide_server,
      title = item.name,
      selected = item.id == host?.id,
      onClick = { onPick(item.id) },
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusDot(if (connected[item.id] == true) colors.dotSuccess else colors.foregroundExtraMuted)
        MenuDetail(item.displayAddress)
      }
    }
  }
  HorizontalDivider(
    modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s12),
    thickness = 1.dp,
    color = colors.border,
  )
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(role = Role.Button, onClick = onAddHost)
      .heightIn(min = 48.dp)
      .padding(horizontal = PipkinTokens.Spacing.s16),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    LucideIcon(icon = R.drawable.ic_lucide_plus, contentDescription = null, tint = colors.accent)
    Text(
      text = stringResource(R.string.remote_add_host),
      color = colors.accent,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

/** The phone's icon, or a server for a remote [host]. */
@DrawableRes
internal fun machineIcon(host: RemoteHost?): Int = if (host ==
  null
) {
  R.drawable.ic_lucide_smartphone
} else {
  R.drawable.ic_lucide_server
}

/** A green dot and Connected, or a grey one and Not connected. */
@Composable
private fun ConnectionState(connected: Boolean) {
  val colors = pipkinColors
  val color = if (connected) colors.dotSuccess else colors.foregroundMuted
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    StatusDot(if (connected) colors.dotSuccess else colors.foregroundExtraMuted)
    Text(
      text = stringResource(if (connected) R.string.remote_connected else R.string.remote_not_connected),
      color = color,
      fontSize = PipkinTokens.FontSize.sm,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
    )
  }
}

/** One machine in the menu: its icon, its [title] over [detail], and a check while it is the one browsed. */
@Composable
private fun MachineItem(
  @DrawableRes icon: Int,
  title: String,
  selected: Boolean,
  onClick: () -> Unit,
  detail: @Composable () -> Unit,
) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .heightIn(min = 56.dp)
      .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    LucideIcon(icon = icon, contentDescription = null, tint = colors.foregroundMuted)
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      detail()
    }
    if (selected) LucideIcon(icon = R.drawable.ic_lucide_check, contentDescription = null, tint = colors.accent)
  }
}

@Composable
private fun MenuDetail(text: String) {
  Text(
    text = text,
    style = pathTextStyle,
    color = pipkinColors.foregroundMuted,
    fontSize = PipkinTokens.FontSize.sm,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

private val MACHINE_SHAPE = RoundedCornerShape(14.dp)
