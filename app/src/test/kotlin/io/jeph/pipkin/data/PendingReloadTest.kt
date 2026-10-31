package io.jeph.pipkin.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReloadTest {
  @Test
  fun `asks for a reload only when the write changed something`() = runTest {
    val reload = PendingReload()
    assertTrue(reload.after { true })
    assertFalse(reload.after { false })
  }

  @Test
  fun `a write whose answer was lost leaves the reload to the retry that finds nothing to change`() = runTest {
    val reload = PendingReload()
    val lost = runCatching { reload.after { throw IOException("answer lost after the write") } }
    assertTrue(lost.exceptionOrNull() is IOException)
    assertTrue(reload.after { false })
    assertFalse(reload.after { false })
  }

  @OptIn(ExperimentalCoroutinesApi::class) // runCurrent, to let the write start before it is cancelled.
  @Test
  fun `a cancelled write leaves the reload to the next one`() = runTest {
    val reload = PendingReload()
    val hold = CompletableDeferred<Boolean>()
    val cancelled = async { reload.after { hold.await() } }
    runCurrent()
    cancelled.cancel()
    runCurrent()
    assertTrue(reload.after { false })
  }
}
