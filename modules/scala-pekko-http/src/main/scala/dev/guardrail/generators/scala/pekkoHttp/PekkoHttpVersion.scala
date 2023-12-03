package dev.guardrail.generators.scala.pekkoHttp

sealed abstract class PekkoHttpVersion(val value: String)
object PekkoHttpVersion {
  case object V10_1 extends PekkoHttpVersion("pekko-http-v10.1")
  case object V10_2 extends PekkoHttpVersion("pekko-http-v10.2")

  val mapping: Map[String, PekkoHttpVersion] = Map(
    "pekko-http" -> V10_2,
    V10_1.value -> V10_1,
    V10_2.value -> V10_2
  )
}
