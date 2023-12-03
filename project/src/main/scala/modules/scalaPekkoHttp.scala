package dev.guardrail.sbt.modules

import dev.guardrail.sbt.Build._

import sbt._
import sbt.Keys._
import wartremover.WartRemover.autoImport._

object scalaPekkoHttp {
  val pekkoVersion           = "1.0.0"
  val pekkoHttpVersion       = "1.0.0"
  val catsVersion            = "2.10.0"
  val circeVersion           = "0.14.6"
  val javaxAnnotationVersion = "1.3.2"
  val jaxbApiVersion         = "2.3.1"
  val refinedVersion         = "0.11.0"
  val scalatestVersion       = "3.2.17"

  val dependencies = Seq(
    "javax.annotation"  %  "javax.annotation-api" % javaxAnnotationVersion, // for jdk11
    "javax.xml.bind"    %  "jaxb-api"             % jaxbApiVersion, // for jdk11
  ) ++ Seq(
    "org.apache.pekko"  %% "pekko-http"           % pekkoHttpVersion,
    "org.apache.pekko"  %% "pekko-http-testkit"   % pekkoHttpVersion,
    "org.apache.pekko"  %% "pekko-stream"         % pekkoVersion,
    "org.apache.pekko"  %% "pekko-testkit"        % pekkoVersion,
    "eu.timepit"        %% "refined"              % refinedVersion,
    "eu.timepit"        %% "refined-cats"         % refinedVersion,
    "io.circe"          %% "circe-core"           % circeVersion,
    "io.circe"          %% "circe-jawn"           % circeVersion,
    "io.circe"          %% "circe-parser"         % circeVersion,
    "io.circe"          %% "circe-refined"        % circeVersion,
    "org.scalatest"     %% "scalatest"            % scalatestVersion % Test,
    "org.typelevel"     %% "cats-core"            % catsVersion
  ).map(_.cross(CrossVersion.for3Use2_13))

  val project = commonModule("scala-pekko-http")

  val sample =
    buildSampleProject("akkaHttp", dependencies)
      .settings(Compile / compile / wartremoverWarnings --= Seq(Wart.NonUnitStatements, Wart.Throw))
}
