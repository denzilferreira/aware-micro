package com.awareframework.micro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir

@ExtendWith(VertxExtension::class)
class TestMainVerticle {
  @TempDir
  @JvmField
  var tempDir: Path? = null

  /**
   * Locates aware-config.json for testing tentatively, before each test case.
   *
   * If the working directory already has aware-config.json, this method backs it up to a tentative directory beforehand.
   */
  @BeforeEach
  fun locateAwareConfigJson() {
    val targetAwareConfigJson = Paths.get("").toAbsolutePath().resolve("aware-config.json")
    if (Files.exists(targetAwareConfigJson)) {
      println("aware-config.json exists at: " + targetAwareConfigJson)
      val backupAwareConfigJson = tempDir?.resolve("aware-config.json")
      println("Backing up aware-config.json to: " + backupAwareConfigJson)
      Files.move(targetAwareConfigJson, backupAwareConfigJson, StandardCopyOption.ATOMIC_MOVE)
    } else {
      println("aware-config.json does not exist at: " + targetAwareConfigJson)
    }

    val testAwareConfigJson = Paths.get(javaClass.getClassLoader().getResource("aware-config.json").toURI())
    Files.copy(testAwareConfigJson, targetAwareConfigJson, StandardCopyOption.REPLACE_EXISTING)
  }

  /**
   * If aware-config.json was backed up in locateAwareConfigJson, restores the original aware-config.json after each test case.
   */
  @AfterEach
  fun restoreAwareConfigJson() {
    val targetAwareConfigJson = Paths.get("").toAbsolutePath().resolve("aware-config.json")
    val backupAwareConfigJson = tempDir?.resolve("aware-config.json")
    if (Files.exists(backupAwareConfigJson)) {
      println("Restoring aware-config.json from: " + backupAwareConfigJson)
      Files.move(backupAwareConfigJson, targetAwareConfigJson, StandardCopyOption.ATOMIC_MOVE)
    } else {
      println("Removing aware-config.json at: " + targetAwareConfigJson)
      Files.delete(targetAwareConfigJson)
    }
  }

  @Test
  fun testGetRoot(vertx: Vertx, testContext: VertxTestContext) {
    val webClient = WebClient.create(vertx)

    val deploymentCheckpoint = testContext.checkpoint()
    val requestCheckpoint = testContext.checkpoint()

    vertx.deployVerticle(MainVerticle(), testContext.succeeding { _ ->
      deploymentCheckpoint.flag();

      webClient.get(8080, "localhost", "/")
        .`as`(BodyCodec.string())
        .send(testContext.succeeding { resp ->
          testContext.verify {
            assertEquals(200, resp.statusCode())
            assertTrue(resp.body().startsWith("Hello from AWARE Micro!"))
            requestCheckpoint.flag();
          }
        })
    })
  }
}
