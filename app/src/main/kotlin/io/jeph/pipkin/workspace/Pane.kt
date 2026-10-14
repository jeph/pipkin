package io.jeph.pipkin.workspace

/**
 * A place a chat shows in: the main pane, or the second pane that split screen adds beside it on a wide window. A chat,
 * and a terminal, file or commit shown in place of one, shows in one pane at a time.
 */
internal enum class Pane {
  Primary,
  Secondary,
  ;

  val other: Pane get() = if (this == Primary) Secondary else Primary
}
