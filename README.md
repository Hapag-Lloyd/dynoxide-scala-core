# dynoxide-scala-core

Build-tool-agnostic core library that downloads and manages a [Dynoxide](https://github.com/nubo-db/dynoxide)
DynamoDB-emulator process for integration tests. It has **no dependency on sbt or Mill** — each
build-tool plugin (e.g. [`sbt-dynoxide`](https://github.com/Hapag-Lloyd/sbt-dynoxide),
`mill-dynoxide`) is a thin adapter around this library, so the process lifecycle, binary
download/caching, and readiness-probing logic is written and tested exactly once.

## What it provides

- `com.hlag.dynoxide.core.DynoxideServer` — reference-counted start/stop of a single shared
  Dynoxide process (`ensureRunning`, `release`, `forceStop`)
- `com.hlag.dynoxide.core.DynoxideLogger` — a minimal logging trait; adapt your build tool's own
  logger to it (`sbt.util.Logger`, Mill's `mill.api.Logger`, ...)

Internally it also resolves the correct platform binary (`BinaryInstaller`) from GitHub Releases —
macOS/Linux/Windows, arm/x86 — caches it under `<baseDir>/.dynoxide/<version>/`, and polls
`http://localhost:<port>` until the emulator is ready.

## Cross-build

Published for Scala 2.12, 2.13, and 3 — covering sbt 1.x plugins (2.12), sbt 2.x plugins (3), and
Mill plugins (2.13/3), all from the same jar per Scala version.

## Usage (from a build-tool plugin)

```scala
libraryDependencies += "com.hlag" %% "dynoxide-scala-core" % "<version>"
```

```scala
import com.hlag.dynoxide.core.{DynoxideLogger, DynoxideServer}

val logger: DynoxideLogger = new DynoxideLogger {
  def info(message: String): Unit  = myBuildTool.log.info(message)
  def debug(message: String): Unit = myBuildTool.log.debug(message)
  def warn(message: String): Unit  = myBuildTool.log.warn(message)
}

DynoxideServer.ensureRunning(port = 8000, version = "v0.13.0", baseDir = projectRoot, log = logger)
// ... run tests ...
DynoxideServer.release()
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Contribution

Bug fixes and improvements are welcome — please open a pull request.

Run all tests:

```
sbt clean test
```

