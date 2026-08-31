package com.hlag.dynoxide.core

class PlatformSuite extends munit.FunSuite {

  test("fromOsArch resolves macOS ARM to MacOsArm") {
    assertEquals(Platform.fromOsArch("Mac OS X", "aarch64"), Platform.MacOsArm)
  }

  test("fromOsArch resolves macOS x86_64 to MacOsX86") {
    assertEquals(Platform.fromOsArch("Mac OS X", "x86_64"), Platform.MacOsX86)
  }

  test("fromOsArch resolves Windows to WindowsX86 regardless of arch") {
    assertEquals(Platform.fromOsArch("Windows 11", "amd64"), Platform.WindowsX86)
    assertEquals(Platform.fromOsArch("Windows 11", "aarch64"), Platform.WindowsX86)
  }

  test("fromOsArch resolves Linux ARM to LinuxArm") {
    assertEquals(Platform.fromOsArch("Linux", "aarch64"), Platform.LinuxArm)
    assertEquals(Platform.fromOsArch("Linux", "arm64"), Platform.LinuxArm)
  }

  test("fromOsArch resolves Linux x86_64 to LinuxX86") {
    assertEquals(Platform.fromOsArch("Linux", "amd64"), Platform.LinuxX86)
  }

  test("fromOsArch is case-insensitive") {
    assertEquals(Platform.fromOsArch("MAC OS X", "AARCH64"), Platform.MacOsArm)
  }

  test("detect() delegates to fromOsArch using system properties") {
    val expected = Platform.fromOsArch(sys.props("os.name"), sys.props("os.arch"))
    assertEquals(Platform.detect(), expected)
  }

  test("binaryName is dynoxide.exe only for the zip-packaged Windows platform") {
    assertEquals(Platform.WindowsX86.binaryName, "dynoxide.exe")
    assertEquals(Platform.MacOsArm.binaryName, "dynoxide")
    assertEquals(Platform.MacOsX86.binaryName, "dynoxide")
    assertEquals(Platform.LinuxArm.binaryName, "dynoxide")
    assertEquals(Platform.LinuxX86.binaryName, "dynoxide")
  }

  test("assetName combines target and archive extension") {
    assertEquals(Platform.MacOsArm.assetName, "dynoxide-aarch64-apple-darwin.tar.gz")
    assertEquals(Platform.MacOsX86.assetName, "dynoxide-x86_64-apple-darwin.tar.gz")
    assertEquals(Platform.LinuxArm.assetName, "dynoxide-aarch64-unknown-linux-musl.tar.gz")
    assertEquals(Platform.LinuxX86.assetName, "dynoxide-x86_64-unknown-linux-musl.tar.gz")
    assertEquals(Platform.WindowsX86.assetName, "dynoxide-x86_64-pc-windows-msvc.zip")
  }

  test("archiveExtension is .tar.gz for Unix platforms and .zip for Windows") {
    assertEquals(Platform.MacOsArm.archiveExtension, ".tar.gz")
    assertEquals(Platform.MacOsX86.archiveExtension, ".tar.gz")
    assertEquals(Platform.LinuxArm.archiveExtension, ".tar.gz")
    assertEquals(Platform.LinuxX86.archiveExtension, ".tar.gz")
    assertEquals(Platform.WindowsX86.archiveExtension, ".zip")
  }

  test("use zip only for Windows platform") {
    assertEquals(Platform.MacOsArm.isZip, false)
    assertEquals(Platform.MacOsX86.isZip, false)
    assertEquals(Platform.LinuxArm.isZip, false)
    assertEquals(Platform.LinuxX86.isZip, false)
    assertEquals(Platform.WindowsX86.isZip, true)
  }

  test("all platforms have distinct, non-blank targets") {
    val expectedPlatformCount = 5
    val targets               = Platform.values.map(_.target)
    assert(targets.forall(_.trim.nonEmpty), "every target must be non-blank")
    assertEquals(targets.distinct.size, expectedPlatformCount)
  }

  test("all platforms have distinct, non-blank asset names") {
    val expectedPlatformCount = 5
    val assetNames            = Platform.values.map(_.assetName)
    assert(assetNames.forall(_.trim.nonEmpty), "every assetName must be non-blank")
    assertEquals(assetNames.distinct.size, expectedPlatformCount)
  }
}
