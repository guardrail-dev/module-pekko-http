package dev.guardrail.generators.scala.pekkoHttp

sealed abstract class PekkoHttpVersion(val value: String)
object PekkoHttpVersion {
  case object V1_0_0 extends PekkoHttpVersion("pekko-http-v1.0.0")
  case object V1_1_0 extends PekkoHttpVersion("pekko-http-v1.1.0")

  val mapping: Map[String, PekkoHttpVersion] = Map(
    "pekko-http" -> V1_1_0,
    V1_0_0.value -> V1_0_0,
    V1_1_0.value -> V1_1_0
  )
}
