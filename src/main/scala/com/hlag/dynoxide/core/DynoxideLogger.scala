package com.hlag.dynoxide.core

/** Minimal logging abstraction shared across build tools. */
trait DynoxideLogger {
  def info(message: String): Unit
  def debug(message: String): Unit
  def warn(message: String): Unit
}

object DynoxideLogger {

  /** Simple `println`-based logger, useful for tests and standalone usage. */
  val console: DynoxideLogger = new DynoxideLogger {
    def info(message: String): Unit  = println(message)
    def debug(message: String): Unit = println(message)
    def warn(message: String): Unit  = println(message)
  }
}
