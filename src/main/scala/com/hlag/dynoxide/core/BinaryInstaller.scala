package com.hlag.dynoxide.core

import java.io.File
import java.net.{HttpURLConnection, URI}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.zip.{GZIPInputStream, ZipEntry, ZipInputStream}
import scala.util.Try

private[core] sealed abstract class Platform(val target: String, val isZip: Boolean) {
  val archiveExtension: String = if (isZip) ".zip" else ".tar.gz"
  val binaryName: String       = if (isZip) "dynoxide.exe" else "dynoxide"
  def assetName: String        = s"dynoxide-$target$archiveExtension"
}

private[core] object Platform {
  case object MacOsArm   extends Platform("aarch64-apple-darwin", isZip = false)
  case object MacOsX86   extends Platform("x86_64-apple-darwin", isZip = false)
  case object LinuxArm   extends Platform("aarch64-unknown-linux-musl", isZip = false)
  case object LinuxX86   extends Platform("x86_64-unknown-linux-musl", isZip = false)
  case object WindowsX86 extends Platform("x86_64-pc-windows-msvc", isZip = true)

  // Due to old Scala 2.12/2.13 compatibility we can't use Scala 3's `enum` and `.values`, so this list is maintained by hand.
  val values: List[Platform] = List(MacOsArm, MacOsX86, LinuxArm, LinuxX86, WindowsX86)

  def detect(): Platform =
    fromOsArch(sys.props("os.name"), sys.props("os.arch"))

  private[core] def fromOsArch(osName: String, archName: String): Platform = {
    val os    = osName.toLowerCase(java.util.Locale.ROOT)
    val arch  = archName.toLowerCase(java.util.Locale.ROOT)
    val isArm = arch == "aarch64" || arch == "arm64"
    if (os.contains("mac")) if (isArm) MacOsArm else MacOsX86
    else if (os.contains("win")) WindowsX86
    else if (isArm) LinuxArm
    else LinuxX86
  }
}

/**
 * Resolves the platform binary under `<baseDir>/.dynoxide/<version>/dynoxide[.exe]`, downloading
 * it from GitHub Releases on first use and reusing the cached executable on later runs.
 */
private[core] object BinaryInstaller {

  private[core] val DefaultGithubReleasesBase = "https://github.com/nubo-db/dynoxide/releases"
  private val BinaryCacheRoot                 = ".dynoxide"
  private val TarBlockSize                    = 512
  private val DownloadConnectMs               = 30000
  private val DownloadReadMs                  = 120000

  def resolve(
    baseDir: File,
    version: String,
    log: DynoxideLogger,
    releasesBase: String = DefaultGithubReleasesBase,
  ): File = {
    val platform = Platform.detect()
    val binary   = cachedBinaryPath(baseDir, version, platform)
    if (binary.exists() && binary.canExecute) {
      log.info(s"[dynoxide] Using cached Dynoxide $version at ${binary.getAbsolutePath}")
      binary
    } else {
      install(platform, version, binary, log, releasesBase)
    }
  }

  private def cachedBinaryPath(baseDir: File, version: String, platform: Platform): File =
    new File(new File(baseDir, BinaryCacheRoot), s"$version/${platform.binaryName}")

  private def install(
    platform: Platform,
    version: String,
    binary: File,
    log: DynoxideLogger,
    releasesBase: String,
  ): File = {
    Files.createDirectories(binary.getParentFile.toPath)
    val url = releaseDownloadUrl(version, platform, releasesBase)
    log.info(s"[dynoxide] Downloading Dynoxide $version for ${platform.target} ...")
    log.debug(s"[dynoxide] Source: $url")
    withTempFile(platform.archiveExtension) { tmp =>
      Downloader.fetch(url, tmp)
      extractBinary(tmp, platform, binary.toPath)
    }
    binary.setExecutable(true)
    log.info(s"[dynoxide] Dynoxide $version installed at ${binary.getAbsolutePath}")
    binary
  }

  private def releaseDownloadUrl(version: String, platform: Platform, releasesBase: String): String =
    s"$releasesBase/download/$version/${platform.assetName}"

  private def withTempFile[A](extension: String)(f: Path => A): A = {
    val tmp = Files.createTempFile("dynoxide-", extension)
    try f(tmp)
    finally { val _ = Files.deleteIfExists(tmp) }
  }

  private def extractBinary(archive: Path, platform: Platform, dest: Path): Unit =
    if (platform.isZip) ZipExtractor.extract(archive, platform.binaryName, dest)
    else TarGzExtractor.extract(archive, platform.binaryName, dest)

  private def baseName(path: String): String = path.split('/').last

  private object Downloader {
    def fetch(url: String, dest: Path): Unit = {
      val conn = open(url)
      conn.setConnectTimeout(DownloadConnectMs)
      conn.setReadTimeout(DownloadReadMs)
      conn.setInstanceFollowRedirects(true)
      try {
        ensureSuccess(conn, url)
        val in = conn.getInputStream
        try { val _ = Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING) }
        finally in.close()
      } finally conn.disconnect()
    }

    private def open(url: String): HttpURLConnection =
      URI.create(url).toURL.openConnection() match {
        case http: HttpURLConnection => http
        case other                   => sys.error(s"[dynoxide] Expected HttpURLConnection for $url, got ${other.getClass.getSimpleName}")
      }

    private def ensureSuccess(conn: HttpURLConnection, url: String): Unit = {
      val code = conn.getResponseCode
      if (code != 200) sys.error(s"[dynoxide] HTTP $code downloading from $url")
    }
  }

  private object ZipExtractor {
    def extract(archive: Path, targetName: String, dest: Path): Unit = {
      val zis = new ZipInputStream(Files.newInputStream(archive))
      try {
        entries(zis).find(e => baseName(e.getName) == targetName && !e.isDirectory) match {
          case Some(_) => val _ = Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING)
          case None    => sys.error(s"[dynoxide] '$targetName' not found in ZIP archive")
        }
      } finally zis.close()
    }

    private def entries(zis: ZipInputStream): Iterator[ZipEntry] =
      Iterator.continually(Option(zis.getNextEntry)).takeWhile(_.isDefined).map(_.get)
  }

  private object TarGzExtractor {
    def extract(archive: Path, targetName: String, dest: Path): Unit = {
      val gz = new GZIPInputStream(Files.newInputStream(archive))
      try findEntry(gz, targetName, dest)
      finally gz.close()
    }

    private def findEntry(gz: GZIPInputStream, targetName: String, dest: Path): Unit = {
      val header = new Array[Byte](TarBlockSize)
      var done   = false
      var found  = false
      while (!done) {
        if (readFully(gz, header) < TarBlockSize || Header.name(header).isEmpty) {
          done = true
        } else {
          val name = Header.name(header)
          val size = Header.size(header)
          if (baseName(name) == targetName && size > 0) {
            val _ = Files.copy(boundedStream(gz, size), dest, StandardCopyOption.REPLACE_EXISTING)
            found = true
            done = true
          } else {
            skipBytes(gz, Header.padded(size))
          }
        }
      }
      if (!found) sys.error(s"[dynoxide] '$targetName' not found in TAR archive")
    }

    private object Header {
      def name(h: Array[Byte]): String = nulTerminatedAscii(h, 0, 100)
      def size(h: Array[Byte]): Long   = parseOctal(h, 124, 12)
      def padded(size: Long): Long     = ((size + TarBlockSize - 1) / TarBlockSize) * TarBlockSize

      private def nulTerminatedAscii(buf: Array[Byte], off: Int, len: Int): String = {
        var i = off
        while (i < off + len && buf(i) != 0) i += 1
        new String(buf, off, i - off, "US-ASCII")
      }

      private def parseOctal(buf: Array[Byte], off: Int, len: Int): Long = {
        val s = nulTerminatedAscii(buf, off, len).trim
        if (s.isEmpty) 0L else java.lang.Long.parseLong(s, 8)
      }
    }

    private def boundedStream(in: java.io.InputStream, limit: Long): java.io.InputStream =
      new java.io.InputStream {
        private var remaining                                        = limit
        def read(): Int                                              =
          if (remaining <= 0) -1
          else { val b = in.read(); if (b >= 0) remaining -= 1; b }
        override def read(buf: Array[Byte], off: Int, len: Int): Int =
          if (remaining <= 0) -1
          else {
            val n = in.read(buf, off, math.min(len.toLong, remaining).toInt)
            if (n > 0) remaining -= n
            n
          }
      }

    private def readFully(in: java.io.InputStream, buf: Array[Byte]): Int = {
      var offset = 0
      while (offset < buf.length) {
        val n = in.read(buf, offset, buf.length - offset)
        if (n < 0) return offset
        offset += n
      }
      offset
    }

    private def skipBytes(in: java.io.InputStream, count: Long): Unit = {
      val buf       = new Array[Byte](4096)
      var remaining = count
      while (remaining > 0) {
        val toRead = math.min(remaining, buf.length.toLong).toInt
        val n      = in.read(buf, 0, toRead)
        if (n < 0) remaining = 0 else remaining -= n
      }
    }
  }
}

private[core] object ReadinessProbe {
  private val ReadinessTimeoutMs   = 500
  private val ReadinessMaxAttempts = 40
  private val ReadinessIntervalMs  = 500L

  def check(port: Int): Boolean =
    Try {
      val conn = localConnection(port)
      conn.setConnectTimeout(ReadinessTimeoutMs)
      conn.setReadTimeout(ReadinessTimeoutMs)
      conn.setRequestMethod("GET")
      val code = conn.getResponseCode
      conn.disconnect()
      code
    }.isSuccess

  def await(port: Int, log: DynoxideLogger): Unit =
    awaitWithRetryPolicy(port, log, ReadinessMaxAttempts, ReadinessIntervalMs)

  private[core] def awaitWithRetryPolicy(port: Int, log: DynoxideLogger, maxAttempts: Int, intervalMs: Long): Unit = {
    val ready = (0 until maxAttempts).exists { _ =>
      if (check(port)) true
      else { Thread.sleep(intervalMs); false }
    }
    if (!ready)
      log.warn(s"[dynoxide] Dynoxide not ready after ${maxAttempts * intervalMs}ms — tests may fail")
  }

  private def localConnection(port: Int): HttpURLConnection =
    URI.create(s"http://localhost:$port").toURL.openConnection() match {
      case http: HttpURLConnection => http
      case other                   => sys.error(s"[dynoxide] Unexpected connection type: ${other.getClass.getSimpleName}")
    }
}
