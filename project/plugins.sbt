addSbtPlugin("org.scalameta"  % "sbt-scalafmt"   % "2.6.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
addSbtPlugin(
  "com.eed3si9n"              % "sbt-salad-days" % "0.2.0"
) // strips the ~2 MB of fonts and web assets Scaladoc bundles into every javadoc jar
