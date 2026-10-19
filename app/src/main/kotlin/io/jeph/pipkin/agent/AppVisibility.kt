package io.jeph.pipkin.agent

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Pipkin's activity is on screen. ProcessLifecycleOwner reports leaving only 700ms later, which would miss a run
 * that ends just after the user switched away. A rotation stops and restarts the activity, so a stop for a
 * configuration change does not count as leaving. Main thread only.
 */
internal class AppVisibility(application: Application) {
  private val onScreenState = MutableStateFlow(false)

  val onScreen: StateFlow<Boolean> = onScreenState.asStateFlow()

  init {
    application.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
          onScreenState.value = true
        }

        override fun onActivityStopped(activity: Activity) {
          if (!activity.isChangingConfigurations) onScreenState.value = false
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
      },
    )
  }
}
