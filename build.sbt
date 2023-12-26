name := "guardrail-module-pekko-http"

import dev.guardrail.sbt.Build
import dev.guardrail.sbt.Build.ProjectSyntax
import dev.guardrail.sbt.modules.scalaPekkoHttp

lazy val pekkoHttp = (
  scalaPekkoHttp
    .project
    .customDependsOn_("guardrail-core", "1.0.0-M1")
    .customDependsOn_("guardrail-scala-support", "1.0.0-M1")
    .settings(
      resolvers ++= Seq(
        sbt.librarymanagement.Resolver.mavenLocal,
        sbt.librarymanagement.Resolver.defaultLocal,
        "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots",
      )
    )
)

onLoadMessage := WelcomeMessage.welcomeMessage((pekkoHttp / version).value)

lazy val githubMatrixSettings = taskKey[String]("Prints JSON value expected by the Scala CI matrix build: [{ version: ..., bincompat: ... }]")

githubMatrixSettings := {
  (pekkoHttp/crossScalaVersions).value
    .map(v => (v, v.split('.').take(2).mkString(".")))
    .map({ case (version, bincompat) => s"""{"version":"${version}","bincompat":"${bincompat}"}""" })
    .mkString("[", ",", "]")
}
