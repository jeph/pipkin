package io.jeph.pipkin.localmodel.catalog

import io.jeph.pipkin.localmodel.InstalledModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** SKELETON: the UI agent replaces this with the catalog, downloads and engine choice. */
internal class ModelStore {
  private val installedState = MutableStateFlow<List<InstalledModel>>(emptyList())

  /** The models downloaded and verified, each with the build chosen for this phone. */
  val installed: StateFlow<List<InstalledModel>> = installedState
}
