package io.jeph.pipkin

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.jeph.pipkin.termux.TermuxCoordinator

class PipkinApplication :
  Application(),
  DefaultLifecycleObserver {
  internal lateinit var coordinator: TermuxCoordinator
    private set

  override fun onCreate() {
    super<Application>.onCreate()
    coordinator = TermuxCoordinator(this)
    ProcessLifecycleOwner.get().lifecycle.addObserver(this)
  }

  override fun onStart(owner: LifecycleOwner) {
    coordinator.foreground()
  }

  override fun onStop(owner: LifecycleOwner) {
    coordinator.background()
  }
}
