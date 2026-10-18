package io.jeph.pipkin

import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * The app's string resources for code that has no Context at hand, such as an exception's message, which is read
 * wherever the exception lands. Each read goes through the application, so it follows a change of the app's language.
 * [PipkinApplication] sets it up before anything else runs.
 */
internal object AppStrings {
  private lateinit var application: Application

  fun init(application: Application) {
    this.application = application
  }

  fun get(@StringRes id: Int, vararg args: Any): String = application.getString(id, *args)

  fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    application.resources.getQuantityString(id, count, *args)
}
