package com.hlag.dynoxide.core

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

import java.io.{ByteArrayOutputStream, File}
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipOutputStream}

/** End-to-end test for the server lifecycle. */
class DynoxideServerSuite extends munit.FunSuite {

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  private def recordingLogger(): (DynoxideLogger, scala.collection.mutable.Buffer[String]) = {
    val warnings = scala.collection.mutable.Buffer.empty[String]
    val logger   = new DynoxideLogger {
      def info(message: String): Unit  = ()
      def debug(message: String): Unit = ()
      def warn(message: String): Unit  = { val _ = warnings += message }
    }
    (logger, warnings)
  }

  /** POSIX shell script that reuses this JVM as a fake `dynoxide` binary. */
  private def fakeBinaryScript(): String = {
    val javaBin   = sys.props("java.home") + File.separator + "bin" + File.separator + "java"
    val classpath = sys.props("dynoxide.test.classpath")
    s"""#!/bin/sh
       |exec "$javaBin" -cp "$classpath" com.hlag.dynoxide.core.FakeDynoxideMain "$$@"
       |""".stripMargin
  }

  // Hand-crafted to match BinaryInstaller's own minimal tar reader (name + octal size fields
  // only) rather than pulling in a general-purpose tar library for a single test fixture.
  private def buildTarGzArchive(entryName: String, content: String): Array[Byte] = {
    val bytes     = content.getBytes(StandardCharsets.US_ASCII)
    val header    = Array.fill[Byte](512)(0)
    val name      = entryName.getBytes(StandardCharsets.US_ASCII)
    System.arraycopy(name, 0, header, 0, name.length)
    val sizeOctal = (f"${bytes.length}%011o" + "\u0000").getBytes(StandardCharsets.US_ASCII)
    System.arraycopy(sizeOctal, 0, header, 124, sizeOctal.length)

    val paddedLen = ((bytes.length + 511) / 512) * 512
    val padded    = Array.fill[Byte](paddedLen)(0)
    System.arraycopy(bytes, 0, padded, 0, bytes.length)

    val raw = new ByteArrayOutputStream()
    raw.write(header)
    raw.write(padded)

    val gzBytes = new ByteArrayOutputStream()
    val gzOut   = new GZIPOutputStream(gzBytes)
    gzOut.write(raw.toByteArray)
    gzOut.close()
    gzBytes.toByteArray
  }

  private def buildZipArchive(entryName: String, content: String): Array[Byte] = {
    val out    = new ByteArrayOutputStream()
    val zipOut = new ZipOutputStream(out)
    zipOut.putNextEntry(new ZipEntry(entryName))
    zipOut.write(content.getBytes(StandardCharsets.US_ASCII))
    zipOut.closeEntry()
    zipOut.close()
    out.toByteArray
  }

  private def buildFakeArchive(platform: Platform): Array[Byte] = {
    val script = fakeBinaryScript()
    if (platform.isZip) buildZipArchive(platform.binaryName, script)
    else buildTarGzArchive(platform.binaryName, script)
  }

  private def eventually(maxAttempts: Int, intervalMs: Long)(cond: => Boolean): Boolean =
    (1 to maxAttempts).exists { _ =>
      if (cond) true else { Thread.sleep(intervalMs); false }
    }

  test(
    "ensureRunning makes the emulator reachable on the requested port, and forceStop makes it unreachable again"
  ) {
    // Skipped on Windows because the fake launcher is a shell script.
    val platform = Platform.detect()
    assume(!platform.isZip, "fake-binary launcher requires a POSIX shell, skipped on Windows")

    val wireMock = new WireMockServer(wireMockConfig().dynamicPort())
    wireMock.start()
    try {
      val version = "v0.0.0-test"
      wireMock.stubFor(
        get(urlEqualTo(s"/download/$version/${platform.assetName}"))
          .willReturn(aResponse().withStatus(200).withBody(buildFakeArchive(platform)))
      )

      val baseDir            = Files.createTempDirectory("dynoxide-server-suite").toFile
      val (logger, warnings) = recordingLogger()
      val port               = freePort()

      DynoxideServer.ensureRunning(port, version, baseDir, logger, releasesBase = s"http://localhost:${wireMock.port()}")
      try {
        assert(ReadinessProbe.check(port), "expected the emulator to be reachable after ensureRunning")
        assert(warnings.isEmpty, s"expected no warnings while starting, got: $warnings")
      } finally DynoxideServer.forceStop(logger)

      assert(
        eventually(maxAttempts = 50, intervalMs = 20)(!ReadinessProbe.check(port)),
        "expected the emulator to stop responding after forceStop",
      )
    } finally wireMock.stop()
  }

  test("forceStop is a no-op when nothing is running") {
    val (logger, warnings) = recordingLogger()
    DynoxideServer.forceStop(logger)
    assert(warnings.isEmpty)
  }

  test("release is a no-op when nothing is running") {
    DynoxideServer.release()
  }
}
