package io.jeph.pipkin

import android.app.Application

class PipkinApplication : Application() {
  internal lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    AppStrings.init(this)
    graph = AppGraph(this)
  }
}
