package io.jeph.pipkin

import android.app.Application
import io.jeph.pipkin.ui.theme.LauncherIcon
import io.jeph.pipkin.ui.theme.PipkinFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PipkinApplication : Application() {
  internal lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    AppStrings.init(this)
    graph = AppGraph(this)
    // Started with the process rather than the activity, so the fonts are usually loaded by the first frame.
    graph.scope.launch(Dispatchers.Default) { PipkinFonts.preload(this@PipkinApplication) }
    graph.scope.launch(Dispatchers.IO) { LauncherIcon.follow(this@PipkinApplication, graph.preferences.theme) }
  }
}
