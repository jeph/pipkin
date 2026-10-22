package io.jeph.pipkin.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Back on the left, where the user is among the steps in the middle, and close on the right once setup was done. */
@Composable
internal fun WizardTopBar(state: OnboardingState, step: SetupStep?, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = TOP_BAR_HEIGHT).padding(horizontal = PipkinTokens.Spacing.s12),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(BAR_BUTTON_SIZE), contentAlignment = Alignment.Center) {
      if (onBack != null) {
        RoundIconButton(
          icon = R.drawable.ic_lucide_chevron_left,
          contentDescription = stringResource(R.string.onboarding_back),
          onClick = onBack,
        )
      }
    }
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
      val steps = state.visibleSteps
      if (step != null && step in steps) {
        ProgressDots(done = steps.map { state.status(it) == StepStatus.Done }, current = steps.indexOf(step))
      }
    }
    Box(Modifier.size(BAR_BUTTON_SIZE), contentAlignment = Alignment.Center) {
      if (onClose != null) {
        RoundIconButton(
          icon = R.drawable.ic_lucide_x,
          contentDescription = stringResource(R.string.onboarding_close),
          onClick = onClose,
        )
      }
    }
  }
}

/**
 * The page frame: content centered in the space above the buttons and scrolling when it doesn't fit (large fonts,
 * landscape), at most [PAGE_MAX_WIDTH] wide. A page shorter than [ILLUSTRATED_MIN_HEIGHT], as in landscape, leaves out
 * its illustration, which would otherwise fill the screen and push the title and text out of view.
 */
@Composable
internal fun WizardPage(
  illustration: @Composable () -> Unit,
  title: String,
  body: String,
  buttons: @Composable ColumnScope.() -> Unit,
  tag: String? = null,
  content: @Composable ColumnScope.() -> Unit = {},
) {
  Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
      val illustrated = maxHeight >= ILLUSTRATED_MIN_HEIGHT
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .heightIn(min = maxHeight)
          .padding(horizontal = PipkinTokens.Spacing.s24, vertical = PipkinTokens.Spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s16, Alignment.CenterVertically),
      ) {
        if (illustrated) illustration()
        PageHeading(tag, title, body)
        Column(
          modifier = Modifier.widthIn(max = PAGE_MAX_WIDTH).fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
          content = content,
        )
      }
    }
    Column(
      modifier = Modifier
        .widthIn(max = PAGE_MAX_WIDTH)
        .fillMaxWidth()
        .padding(horizontal = PipkinTokens.Spacing.s24)
        .padding(top = PipkinTokens.Spacing.s8, bottom = PipkinTokens.Spacing.s16),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      content = buttons,
    )
  }
}

/** "Recommended" or "Optional" in small caps when it applies, the page's title, and a sentence or two under it. */
@Composable
private fun PageHeading(tag: String?, title: String, body: String) {
  val colors = pipkinColors
  Column(
    modifier = Modifier.widthIn(max = PAGE_MAX_WIDTH).padding(top = PipkinTokens.Spacing.s8),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    tag?.let {
      Text(
        text = it.uppercase(),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        letterSpacing = TAG_SPACING,
      )
    }
    Text(
      text = title,
      color = colors.foreground,
      fontSize = TITLE_SIZE,
      fontWeight = FontWeight.SemiBold,
      textAlign = TextAlign.Center,
    )
    Text(
      text = body,
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.content,
      lineHeight = BODY_LINE_HEIGHT,
      textAlign = TextAlign.Center,
    )
  }
}

/** Continue once done; otherwise the step's actions, the first as the main one, and Skip unless it's required. */
@Composable
internal fun ColumnScope.StepButtons(spec: StepSpec, onContinue: () -> Unit, onSkip: () -> Unit) {
  if (spec.status == StepStatus.Done) {
    WizardPrimary(stringResource(R.string.onboarding_continue), onContinue)
    return
  }
  spec.buttons.forEachIndexed { index, button ->
    if (index == 0) {
      WizardPrimary(button.label, button.onClick, button.enabled)
    } else {
      SecondaryButton(
        button.label,
        button.onClick,
        Modifier.fillMaxWidth().heightIn(min = BUTTON_HEIGHT),
        button.enabled,
      )
    }
  }
  if (spec.step.requirement == Requirement.Required) return
  TextButton(
    onClick = onSkip,
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.textButtonColors(contentColor = pipkinColors.foregroundMuted),
  ) {
    Text(text = stringResource(R.string.onboarding_skip), fontSize = PipkinTokens.FontSize.base)
  }
}

/** A page's main action, full width. */
@Composable
internal fun WizardPrimary(text: String, onClick: () -> Unit, enabled: Boolean = true) {
  PrimaryButton(text, onClick, Modifier.fillMaxWidth().heightIn(min = BUTTON_HEIGHT), enabled)
}

private val TOP_BAR_HEIGHT = 56.dp
private val BAR_BUTTON_SIZE = 40.dp
private val BUTTON_HEIGHT = 48.dp
private val PAGE_MAX_WIDTH = 480.dp
private val ILLUSTRATED_MIN_HEIGHT = 480.dp

// Larger than the app's type scale: each page has one title, which carries the page.
private val TITLE_SIZE = 24.sp
private val BODY_LINE_HEIGHT = 22.sp
private val TAG_SPACING = 1.sp
