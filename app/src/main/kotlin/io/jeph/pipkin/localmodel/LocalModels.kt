package io.jeph.pipkin.localmodel

import io.jeph.pipkin.localmodel.catalog.ModelStore

/** On-device models: the downloaded files ([store]) and the process serving one of them to Pi ([runtime]). */
internal class LocalModels {
  val store = ModelStore()
  val runtime = LocalModelRuntime()
}
