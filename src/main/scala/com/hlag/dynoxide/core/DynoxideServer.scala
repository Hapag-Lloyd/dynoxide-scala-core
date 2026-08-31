package com.hlag.dynoxide.core

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import scala.sys.process.{ProcessLogger, Process => ShellProcess}

/**
 * Shared, reference-counted Dynoxide emulator process.
 *
 * Build-tool plugins call `ensureRunning` / `release` / `forceStop` and adapt logging via [[DynoxideLogger]].
 */
object DynoxideServer {
  @volatile private var handle: Option[scala.sys.process.Process] = None
  private val refCount                                            = new AtomicInteger(0)

  /** Starts Dynoxide on `port` if not already running, downloading `version` on first use. */
  def ensureRunning(
    port: Int,
    version: String,
    baseDir: File,
    log: DynoxideLogger,
    releasesBase: String = BinaryInstaller.DefaultGithubReleasesBase,
  ): Unit =
    synchronized {
      if (isRunning(port)) {
        log.info(s"[dynoxide] Dynoxide already running on port $port")
      } else {
        stopCurrent()
        val binary = BinaryInstaller.resolve(baseDir, version, log, releasesBase)
        log.info(s"[dynoxide] Starting Dynoxide on port $port ...")
        handle = Some(launch(binary, port))
        ReadinessProbe.await(port, log)
        log.info(s"[dynoxide] Dynoxide started on port $port")
      }
      val n = refCount.incrementAndGet()
      log.debug(s"[dynoxide] Active subprojects: $n")
    }

  /** Decrements the reference count, stopping the process once the last caller releases it. */
  def release(): Unit =
    if (refCount.decrementAndGet() <= 0) synchronized(destroyAndReset())

  /** Unconditionally stops the process, ignoring the reference count. */
  def forceStop(log: DynoxideLogger): Unit = synchronized {
    if (handle.isDefined) {
      log.info("[dynoxide] Stopping Dynoxide ...")
      destroyAndReset()
      log.info("[dynoxide] Dynoxide stopped")
    } else {
      log.debug("[dynoxide] Dynoxide not running — nothing to stop")
    }
  }

  private def isRunning(port: Int): Boolean = handle.isDefined && ReadinessProbe.check(port)
  private def stopCurrent(): Unit           = { handle.foreach(_.destroy()); handle = None }
  private def destroyAndReset(): Unit       = { stopCurrent(); refCount.set(0) }

  private def launch(binary: File, port: Int): scala.sys.process.Process =
    ShellProcess(Seq(binary.getAbsolutePath, "--port", port.toString))
      .run(ProcessLogger(_ => (), _ => ()))
}
