package io.jeph.pipkin.localmodel

import android.content.Context
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.localmodel.catalog.ModelStore
import kotlinx.coroutines.CoroutineScope

/** On-device models: the downloaded files ([store]) and the process serving one of them to Pi ([runtime]). */
internal class LocalModels(context: Context, scope: CoroutineScope, preferences: Preferences, keepAlive: KeepAlive) {
  val store = ModelStore(context, scope, preferences, keepAlive)
  val runtime = LocalModelRuntime()
}
