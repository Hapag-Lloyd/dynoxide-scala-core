package com.hlag.dynoxide.core

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress

class ReadinessProbeSuite extends munit.FunSuite {

  private def freePort(): Int = {
    val socket = new java.net.ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  private def startServer(port: Int, status: Int = 200): HttpServer = {
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/", exchange => { exchange.sendResponseHeaders(status, -1); exchange.close() })
    server.start()
    server
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

  test("reports the emulator as not ready before anything is listening on its port") {
    assert(!ReadinessProbe.check(freePort()))
  }

  test("reports the emulator as ready once it starts responding to requests") {
    val port   = freePort()
    val server = startServer(port)
    try assert(ReadinessProbe.check(port))
    finally server.stop(0)
  }

  test("reports the emulator as ready even when it answers with a non-2xx status") {
    // Readiness only cares that the process is reachable and speaking HTTP, not what it says.
    val port   = freePort()
    val server = startServer(port, status = 500)
    try assert(ReadinessProbe.check(port))
    finally server.stop(0)
  }

  test("proceeds without warning when the emulator is already ready") {
    val port               = freePort()
    val server             = startServer(port)
    val (logger, warnings) = recordingLogger()
    try {
      ReadinessProbe.awaitWithRetryPolicy(port, logger, maxAttempts = 5, intervalMs = 10)
      assert(warnings.isEmpty)
    } finally server.stop(0)
  }

  test("keeps retrying quietly while the emulator is still starting, then proceeds once it responds") {
    val port               = freePort()
    val (logger, warnings) = recordingLogger()
    var server: HttpServer = null
    val delayedStart       = new Thread(() => {
      Thread.sleep(30)
      server = startServer(port)
    })
    delayedStart.start()
    try {
      ReadinessProbe.awaitWithRetryPolicy(port, logger, maxAttempts = 20, intervalMs = 10)
      delayedStart.join()
      assert(warnings.isEmpty)
    } finally {
      delayedStart.join()
      if (server != null) server.stop(0)
    }
  }

  test("warns the caller when the emulator never becomes ready within the retry budget") {
    val port               = freePort()
    val (logger, warnings) = recordingLogger()
    ReadinessProbe.awaitWithRetryPolicy(port, logger, maxAttempts = 2, intervalMs = 5)
    assert(warnings.exists(_.contains("not ready")), s"expected a 'not ready' warning, got: $warnings")
  }
  // Note: the production retry budget (40 attempts * 500ms = 20s) is exercised here via
  // awaitWithRetryPolicy with a tiny budget to keep the suite fast; the real constants used by
  // await() are covered end-to-end by sbt-dynoxide's/mill-dynoxide's scripted/example tests
  // against a real (or deliberately absent) Dynoxide process.
}
