package io.jeph.pipkin.localmodel.catalog

import io.jeph.pipkin.localmodel.engine.Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogTest {
  private val models = LocalModelCatalog.models
  private val artifacts = models.flatMap { it.artifacts }

  @Test
  fun modelIdsAreUnique() {
    assertEquals(models.size, models.map { it.id }.toSet().size)
  }

  @Test
  fun fileNamesAreUniqueSinceTheyShareOneFolder() {
    assertEquals(artifacts.size, artifacts.map { it.fileName }.toSet().size)
  }

  @Test
  fun everyArtifactHasAnHttpsUrlAShaAndASize() {
    for (artifact in artifacts) {
      assertTrue(artifact.url, artifact.url.startsWith("https://huggingface.co/"))
      assertTrue(artifact.url, artifact.url.endsWith("/" + artifact.fileName))
      assertTrue(artifact.sha256, Regex("[0-9a-f]{64}").matches(artifact.sha256))
      assertTrue(artifact.fileName, artifact.sizeBytes > 0)
      assertTrue(artifact.fileName, artifact.contextSize > 0)
    }
  }

  @Test
  fun npuBuildsAndOnlyThemNameTheirSoc() {
    for (artifact in artifacts) {
      assertEquals(artifact.fileName, artifact.backend == Backend.NPU, artifact.socModels.isNotEmpty())
    }
  }

  @Test
  fun everyModelRunsSomewhereWithoutAnNpu() {
    val anyPhone = DeviceProfile(socModel = null, hasOpenCl = false)
    for (model in models) {
      assertTrue(model.id, EngineChoice.choose(model, anyPhone, EngineOverride.AUTOMATIC) != null)
    }
  }
}
