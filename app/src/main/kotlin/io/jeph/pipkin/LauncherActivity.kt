package io.jeph.pipkin

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * What the launcher's icons open: it starts [MainActivity] the way a launcher would and finishes, in a task of its
 * own that never shows. Android closes every activity opened through a launcher alias when the alias is disabled, as
 * LauncherIcon does when the theme changes, so the main screen is never opened through one. Its task looks as if the
 * launcher had opened it, so the icon brings an open one back as it was; Back is MainActivity's to handle, since
 * Android only keeps what the home screen opened itself.
 */
class LauncherActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    startActivity(
      Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClass(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
    )
    finish()
  }
}
