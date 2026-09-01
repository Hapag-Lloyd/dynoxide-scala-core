import java.io.File

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / organization     := "com.hlag"
ThisBuild / organizationName := "Hapag-Lloyd"
ThisBuild / homepage         := Some(uri("https://github.com/Hapag-Lloyd/dynoxide-scala-core"))
ThisBuild / licenses         := List("Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers       := List(
  Developer("baldram", "Marcin Szałomski", "", uri("https://github.com/baldram"))
)
ThisBuild / scmInfo          := Some(
  ScmInfo(
    uri("https://github.com/Hapag-Lloyd/dynoxide-scala-core"),
    "scm:git:git@github.com:Hapag-Lloyd/dynoxide-scala-core.git",
  )
)
ThisBuild / description      :=
  "Build-tool-agnostic core: downloads/manages a Dynoxide DynamoDB emulator process. " +
    "Shared by sbt-dynoxide and mill-dynoxide."
ThisBuild / versionScheme    := Some("early-semver")

// the artifact `version` is managed by sbt-ci-release/sbt-dynver

val scala212 = "2.12.20"
val scala213 = "2.13.18"
val scala3   = "3.8.4"

lazy val root = (project in file("."))
  .settings(
    name                                   := "dynoxide-scala-core",
    scalaVersion                           := scala3,
    crossScalaVersions                     := Seq(scala3, scala213, scala212),
    libraryDependencies += "org.scalameta" %% "munit"    % "1.3.5"  % Test,
    libraryDependencies += "org.wiremock"   % "wiremock" % "3.13.2" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    // DynoxideServerSuite spawns a subprocess (re-entering this same JVM) that needs the full
    // test classpath; `java.class.path` isn't reliable here since sbt loads the project via its
    // own classloaders rather than that system property, so it's exposed explicitly instead.
    Test / testOptions += {
      val converter = fileConverter.value
      val cp        = (Test / fullClasspath).value
        .map(a => converter.toPath(a.data).toFile.getAbsolutePath)
        .mkString(File.pathSeparator)
      Tests.Setup { () =>
        val _ = System.setProperty("dynoxide.test.classpath", cp)
      }
    },
  )
