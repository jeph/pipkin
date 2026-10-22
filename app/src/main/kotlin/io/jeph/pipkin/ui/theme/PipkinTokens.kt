package io.jeph.pipkin.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pipkin's spacing, type and shape scale. */
object PipkinTokens {
  object Spacing {
    val s2 = 2.dp
    val s4 = 4.dp
    val s6 = 6.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s24 = 24.dp
    val s32 = 32.dp
    val s48 = 48.dp
  }

  object FontSize {
    val code = 12.sp
    val content = 15.sp
    val sm = 12.sp
    val base = 14.sp
    val lg = 16.sp
    val xl = 18.sp
    val xxl = 20.sp
    val meta = 13.sp
  }

  object LineHeight {
    val markdown = 22.sp
    val code = 18.sp
  }

  object Radius {
    val sm = 4.dp
    val base = 7.dp
    val md = 12.dp
    val lg = 14.dp
    val xl = 18.dp
    val xxl = 22.dp
  }

  object IconSize {
    val xs = 12.dp
    val sm = 14.dp
    val md = 16.dp
    val lg = 20.dp
  }

  val contentMaxWidth = 820.dp
}
