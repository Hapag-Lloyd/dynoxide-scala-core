package com.hlag.dynoxide.core

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

/** Fake `dynoxide` binary used by `DynoxideServerSuite`. */
object FakeDynoxideMain {
  def main(args: Array[String]): Unit = {
    val port = args
      .sliding(2)
      .collectFirst { case Array("--port", p) => p.toInt }
      .getOrElse(sys.error("usage: --port <port>"))

    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/", exchange => { exchange.sendResponseHeaders(200, -1); exchange.close() })
    server.setExecutor(null)
    server.start()

    new CountDownLatch(1).await()
  }
}
