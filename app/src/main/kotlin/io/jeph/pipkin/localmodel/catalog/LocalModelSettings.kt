package io.jeph.pipkin.localmodel.catalog

/** Bounds of the local model settings in [io.jeph.pipkin.data.Preferences]. */
internal object LocalModelSettings {
  /**
   * A loaded model holds hundreds of megabytes to gigabytes of memory, so it's given back after a few idle minutes,
   * long enough that the next turn of a conversation usually finds it still loaded.
   */
  const val DEFAULT_IDLE_UNLOAD_MINUTES = 5
  val IDLE_UNLOAD_MINUTES = 1..60
}
