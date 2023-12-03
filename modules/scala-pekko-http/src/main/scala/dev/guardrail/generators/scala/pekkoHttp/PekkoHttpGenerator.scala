package dev.guardrail.generators.scala.pekkoHttp

import scala.meta._
import scala.reflect.runtime.universe.typeTag

import dev.guardrail.{ RuntimeFailure, Target }
import dev.guardrail.generators.scala.{ CirceModelGenerator, CirceRefinedModelGenerator, JacksonModelGenerator, ModelGeneratorType, ScalaLanguage }
import dev.guardrail.generators.spi.{ FrameworkGeneratorLoader, ModuleLoadResult, ProtocolGeneratorLoader }
import dev.guardrail.terms.framework._

class PekkoHttpGeneratorLoader extends FrameworkGeneratorLoader {
  type L = ScalaLanguage
  def reified = typeTag[Target[ScalaLanguage]]
  val apply =
    ModuleLoadResult.forProduct2(
      FrameworkGeneratorLoader.label -> Seq(PekkoHttpVersion.mapping),
      ProtocolGeneratorLoader.label -> Seq(
        CirceModelGenerator.mapping,
        CirceRefinedModelGenerator.mapping.view.mapValues(_.toCirce).toMap,
        JacksonModelGenerator.mapping
      )
    ) { (pekkoHttpVersion, collectionVersion) =>
      PekkoHttpGenerator(pekkoHttpVersion, collectionVersion)
    }
}

object PekkoHttpGenerator {
  def apply(pekkoHttpVersion: PekkoHttpVersion, modelGeneratorType: ModelGeneratorType): FrameworkTerms[ScalaLanguage, Target] =
    new PekkoHttpGenerator(pekkoHttpVersion, modelGeneratorType)
}

class PekkoHttpGenerator private (pekkoHttpVersion: PekkoHttpVersion, modelGeneratorType: ModelGeneratorType) extends FrameworkTerms[ScalaLanguage, Target] {
  override def fileType(format: Option[String]) = Target.pure(format.fold[Type](t"BodyPartEntity")(Type.Name(_)))
  override def objectType(format: Option[String]) =
    modelGeneratorType match {
      case _: CirceModelGenerator   => Target.pure(t"io.circe.Json")
      case _: JacksonModelGenerator => Target.pure(t"com.fasterxml.jackson.databind.JsonNode")
      case _                        => Target.raiseError(RuntimeFailure(s"Unknown modelGeneratorType: ${modelGeneratorType}"))
    }

  override def getFrameworkImports(tracing: Boolean) =
    for {
      protocolImports <- modelGeneratorType match {
        case _: CirceModelGenerator   => Target.pure(List(q"import io.circe.Decoder"))
        case _: JacksonModelGenerator => Target.pure(List())
        case _                        => Target.raiseError(RuntimeFailure(s"Unknown modelGeneratorType: ${modelGeneratorType}"))
      }
    } yield List(
      q"import org.apache.pekko.http.scaladsl.model._",
      q"import org.apache.pekko.http.scaladsl.model.headers.RawHeader",
      q"import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller, FromEntityUnmarshaller, FromRequestUnmarshaller, FromStringUnmarshaller}",
      q"import org.apache.pekko.http.scaladsl.marshalling.{Marshal, Marshaller, Marshalling, ToEntityMarshaller, ToResponseMarshaller}",
      q"import org.apache.pekko.http.scaladsl.server.Directives._",
      q"import org.apache.pekko.http.scaladsl.server.{Directive, Directive0, Directive1, ExceptionHandler, MalformedFormFieldRejection, MalformedHeaderRejection, MissingFormFieldRejection, MalformedRequestContentRejection, Rejection, RejectionError, Route}",
      q"import org.apache.pekko.http.scaladsl.util.FastFuture",
      q"import org.apache.pekko.stream.{IOResult, Materializer}",
      q"import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink, Source}",
      q"import org.apache.pekko.util.ByteString",
      q"import cats.{Functor, Id}",
      q"import cats.data.EitherT",
      q"import cats.implicits._",
      q"import scala.concurrent.{ExecutionContext, Future}",
      q"import scala.language.higherKinds",
      q"import scala.language.implicitConversions",
      q"import java.io.File",
      q"import java.security.MessageDigest",
      q"import java.util.concurrent.atomic.AtomicReference",
      q"import scala.util.{Failure, Success}"
    ) ++ protocolImports

  override def getFrameworkImplicits() =
    for {
      protocolImplicits <- modelGeneratorType match {
        case circe: CirceModelGenerator => Target.pure(circeImplicits(circe))
        case _: JacksonModelGenerator   => Target.pure(jacksonImplicits)
        case _                          => Target.raiseError(RuntimeFailure(s"Unknown modelGeneratorType: ${modelGeneratorType}"))
      }
      defn = q"""
        object PekkoHttpImplicits {
          private[this] def pathEscape(s: String): String = Uri.Path.Segment.apply(s, Uri.Path.Empty).toString
          implicit def addShowablePath[T](implicit ev: Show[T]): AddPath[T] = AddPath.build[T](v => pathEscape(ev.show(v)))

          private[this] def argEscape(k: String, v: String): String = Uri.Query.apply((k, v)).toString
          implicit def addShowableArg[T](implicit ev: Show[T]): AddArg[T] = AddArg.build[T](key => v => argEscape(key, ev.show(v)))

          type HttpClient = HttpRequest => Future[HttpResponse]
          type TraceBuilder = String => HttpClient => HttpClient

          class TextPlain(val value: String)
          object TextPlain {
            def apply(value: String): TextPlain = new TextPlain(value)
            implicit final def textTEM: ToEntityMarshaller[TextPlain] =
              Marshaller.withFixedContentType(ContentTypes.`text/plain(UTF-8)`) { text =>
                HttpEntity(ContentTypes.`text/plain(UTF-8)`, text.value)
              }
          }

          sealed trait IgnoredEntity
          object IgnoredEntity {
            val empty: IgnoredEntity = new IgnoredEntity {}
          }

          // Translate String => Json (by either successfully parsing or string literalizing (Dangerous!))
          final val contentRequiredUnmarshaller: FromStringUnmarshaller[String] = Unmarshaller.strict {
            case "" =>
              throw Unmarshaller.NoContentException
            case data =>
              data
          }

          implicit val ignoredUnmarshaller: FromEntityUnmarshaller[IgnoredEntity] =
            Unmarshaller.strict(_ => IgnoredEntity.empty)

          implicit def MFDBPviaFSU[T](implicit ev: Unmarshaller[BodyPartEntity, T]): Unmarshaller[Multipart.FormData.BodyPart, T] = Unmarshaller.withMaterializer { implicit executionContext => implicit mat => entity =>
            ev.apply(entity.entity)
          }

          implicit def BPEviaFSU[T](implicit ev: Unmarshaller[String, T]): Unmarshaller[BodyPartEntity, T] = Unmarshaller.withMaterializer { implicit executionContext => implicit mat => entity =>
            entity.dataBytes
              .runWith(Sink.fold(ByteString.empty)((accum, bs) => accum.concat(bs)))
              .map(_.decodeString(java.nio.charset.StandardCharsets.UTF_8))
              .flatMap(ev.apply(_))
          }

          def AccumulatingUnmarshaller[T, U, V](accumulator: AtomicReference[List[V]], ev: Unmarshaller[T, U])(acc: U => V)(implicit mat: Materializer): Unmarshaller[T, U] = {
            ev.map { value =>
              accumulator.updateAndGet(x => (acc(value) :: x))
              value
            }
          }

          def SafeUnmarshaller[T, U](ev: Unmarshaller[T, U])(implicit mat: Materializer): Unmarshaller[T, Either[Throwable, U]] = Unmarshaller { implicit executionContext => entity =>
            ev.apply(entity).map[Either[Throwable, U]](Right(_)).recover({ case t => Left(t) })
          }

          def StaticUnmarshaller[T](value: T)(implicit mat: Materializer): Unmarshaller[Multipart.FormData.BodyPart, T] = Unmarshaller { implicit ec => part =>
            part.entity.discardBytes().future.map(_ => value)
          }

          implicit def UnitUnmarshaller(implicit mat: Materializer): Unmarshaller[Multipart.FormData.BodyPart, Unit] = StaticUnmarshaller(())

          def discardEntity: Directive0 = extractMaterializer.flatMap { implicit mat =>
            extractRequest.flatMap { req =>
              req.discardEntityBytes().future
              Directive.Empty
            }
          }

          ..$protocolImplicits
        }
      """
    } yield Some((q"PekkoHttpImplicits", defn))

  private def circeImplicits(circeVersion: CirceModelGenerator): List[Defn] = {
    val jsonEncoderTypeclass: Type = t"io.circe.Encoder"
    val jsonDecoderTypeclass: Type = t"io.circe.Decoder"
    val jsonType: Type             = t"io.circe.Json"
    List(
      q"""
          // Translate Json => HttpEntity
          implicit final def jsonMarshaller(
              implicit printer: _root_.io.circe.Printer = _root_.io.circe.Printer.noSpaces
          ): ToEntityMarshaller[${jsonType}] =
            Marshaller.withFixedContentType(MediaTypes.`application/json`) { json =>
              HttpEntity(MediaTypes.`application/json`, ${Term.Select(q"printer", circeVersion.print)}(json))
            }
       """,
      q"""
          // Translate [A: Encoder] => HttpEntity
          implicit final def jsonEntityMarshaller[A](
              implicit J: ${jsonEncoderTypeclass}[A],
                       printer: _root_.io.circe.Printer = _root_.io.circe.Printer.noSpaces
          ): ToEntityMarshaller[A] =
            jsonMarshaller(printer).compose(J.apply)
       """,
      q"""
          // Translate HttpEntity => Json (for `text/plain`)
          final val stringyJsonEntityUnmarshaller: FromEntityUnmarshaller[${jsonType}] =
            Unmarshaller.byteStringUnmarshaller
              .forContentTypes(MediaTypes.`text/plain`)
              .map({
                case ByteString.empty =>
                  throw Unmarshaller.NoContentException
                case data =>
                  _root_.io.circe.Json.fromString(data.decodeString("utf-8"))
              })
       """,
      q"""
          // Translate HttpEntity => Json (for `text/plain`, relying on the Decoder to reject incorrect types.
          //   This permits not having to manually construct ToStringMarshaller/FromStringUnmarshallers.
          //   This is definitely lazy, but lets us save a lot of scalar parsers as circe decoders are fairly common.)
          final val sneakyJsonEntityUnmarshaller: FromEntityUnmarshaller[${jsonType}] =
            Unmarshaller.byteStringUnmarshaller
              .forContentTypes(MediaTypes.`text/plain`)
              .flatMapWithInput { (httpEntity, byteString) =>
                if (byteString.isEmpty) {
                  FastFuture.failed(Unmarshaller.NoContentException)
                } else {
                  val parseResult = Unmarshaller.bestUnmarshallingCharsetFor(httpEntity) match {
                    case HttpCharsets.`UTF-8` => _root_.io.circe.jawn.parse(byteString.utf8String)
                    case otherCharset => _root_.io.circe.jawn.parse(byteString.decodeString(otherCharset.nioCharset.name))
                  }
                  parseResult.fold(FastFuture.failed, FastFuture.successful)
                }
              }
       """,
      q"""
          final val stringyJsonUnmarshaller: FromStringUnmarshaller[${jsonType}] =
            Unmarshaller.strict(value => _root_.io.circe.Json.fromString(value))
       """,
      q"""
          // Translate HttpEntity => Json (for `application/json`)
          implicit final val structuredJsonEntityUnmarshaller: FromEntityUnmarshaller[${jsonType}] =
            Unmarshaller.byteStringUnmarshaller
              .forContentTypes(MediaTypes.`application/json`)
              .flatMapWithInput { (httpEntity, byteString) =>
                if (byteString.isEmpty) {
                  FastFuture.failed(Unmarshaller.NoContentException)
                } else {
                  val parseResult = Unmarshaller.bestUnmarshallingCharsetFor(httpEntity) match {
                    case HttpCharsets.`UTF-8` => _root_.io.circe.jawn.parse(byteString.utf8String)
                    case otherCharset => _root_.io.circe.jawn.parse(byteString.decodeString(otherCharset.nioCharset.name))
                  }
                  parseResult.fold(FastFuture.failed, FastFuture.successful)
                }
              }
       """,
      q"""
          // Translate HttpEntity => [A: Decoder] (for `application/json` or `text/plain`)
          implicit def jsonEntityUnmarshaller[A](implicit J: ${jsonDecoderTypeclass}[A]): FromEntityUnmarshaller[A] = {
            Unmarshaller.firstOf(structuredJsonEntityUnmarshaller, stringyJsonEntityUnmarshaller)
              .flatMap(_ => _ => json => J.decodeJson(json).fold(FastFuture.failed, FastFuture.successful))
          }
       """,
      q"""
          def unmarshallJson[A](implicit J: ${jsonDecoderTypeclass}[A]): Unmarshaller[${jsonType}, A] =
            Unmarshaller { _ => value =>
              J.decodeJson(value)
                .fold(FastFuture.failed, FastFuture.successful)
            }
       """,
      q"""
          // Translate String => Json by parsing
          final val jsonParsingUnmarshaller: FromStringUnmarshaller[${jsonType}] = Unmarshaller {
            _ => data => _root_.io.circe.jawn.parse(data).fold(FastFuture.failed, FastFuture.successful)
          }
       """,
      q"""
          // Translate String => Json by treaing as a JSON literal
          final val jsonStringyUnmarshaller: FromStringUnmarshaller[${jsonType}] = Unmarshaller.strict {
            case data =>
              _root_.io.circe.Json.fromString(data)
          }
       """,
      q"""
          // Translate String => [A: Decoder]
          def jsonDecoderUnmarshaller[A](implicit J: ${jsonDecoderTypeclass}[A]): Unmarshaller[${jsonType}, A] =
            Unmarshaller { _ => json =>
              J.decodeJson(json).fold(FastFuture.failed, FastFuture.successful)
            }
       """
    )
  }

  private def jacksonImplicits: List[Defn] = {
    val jsonType: Type = t"com.fasterxml.jackson.databind.JsonNode"
    List(
      q"""
          // Translate JsonNode => HttpEntity
          implicit final def jsonMarshaller: ToEntityMarshaller[${jsonType}] =
            Marshaller.withFixedContentType(MediaTypes.`application/json`) { json =>
              HttpEntity(MediaTypes.`application/json`, json.toString)
            }
       """,
      q"""
          // Translate [A: GuardrailEncoder] => HttpEntity
          implicit final def jsonEntityMarshaller[A: GuardrailEncoder](
              implicit mapper: com.fasterxml.jackson.databind.ObjectMapper
          ): ToEntityMarshaller[A] =
            jsonMarshaller.compose(implicitly[GuardrailEncoder[A]].encode)
       """,
      q"""
          // Translate HttpEntity => JsonNode (for `text/plain`)
          final val stringyJsonEntityUnmarshaller: FromEntityUnmarshaller[${jsonType}] =
            Unmarshaller.byteStringUnmarshaller
              .forContentTypes(MediaTypes.`text/plain`)
              .map({
                case ByteString.empty =>
                  throw Unmarshaller.NoContentException
                case data =>
                  new com.fasterxml.jackson.databind.node.TextNode(data.decodeString("utf-8"))
              })
       """,
      q"""
          // Translate HttpEntity => JsonNode (for `text/plain`, relying on the Decoder to reject incorrect types.
          //   This permits not having to manually construct ToStringMarshaller/FromStringUnmarshallers.
          //   This is definitely lazy, but lets us save a lot of scalar parsers as circe decoders are fairly common.)
          def sneakyJsonEntityUnmarshaller(
              implicit mapper: com.fasterxml.jackson.databind.ObjectMapper
          ): FromEntityUnmarshaller[${jsonType}] =
            Unmarshaller.byteStringUnmarshaller
              .forContentTypes(MediaTypes.`text/plain`)
              .flatMapWithInput { (httpEntity, byteString) =>
                if (byteString.isEmpty) {
                  FastFuture.failed(Unmarshaller.NoContentException)
                } else {
                  val jsonStr = Unmarshaller.bestUnmarshallingCharsetFor(httpEntity) match {
                    case HttpCharsets.`UTF-8` => byteString.utf8String
                    case otherCharset => byteString.decodeString(otherCharset.nioCharset.name)
                  }
                  FastFuture(scala.util.Try(mapper.readTree(jsonStr)))
                }
              }
       """,
      q"""
          final val stringyJsonUnmarshaller: FromStringUnmarshaller[${jsonType}] =
            Unmarshaller.strict(value => new com.fasterxml.jackson.databind.node.TextNode(value))
       """,
      q"""
          // Translate HttpEntity => JsonNode (for `application/json`)
          implicit def structuredJsonEntityUnmarshaller(
              implicit mapper: com.fasterxml.jackson.databind.ObjectMapper
          ): FromEntityUnmarshaller[${jsonType}] =
            Unmarshaller.byteStringUnmarshaller
              .forContentTypes(MediaTypes.`application/json`)
              .flatMapWithInput { (httpEntity, byteString) =>
                if (byteString.isEmpty) {
                  FastFuture.failed(Unmarshaller.NoContentException)
                } else {
                  val jsonStr = Unmarshaller.bestUnmarshallingCharsetFor(httpEntity) match {
                    case HttpCharsets.`UTF-8` => byteString.utf8String
                    case otherCharset => byteString.decodeString(otherCharset.nioCharset.name)
                  }
                  FastFuture(scala.util.Try(mapper.readTree(jsonStr)))
                }
              }
       """,
      q"""
          // Translate HttpEntity => [A: GuardrailDecoder] (for `application/json` or `text/plain`)
          implicit def jsonEntityUnmarshaller[A: GuardrailDecoder: GuardrailValidator: scala.reflect.ClassTag](
              implicit mapper: com.fasterxml.jackson.databind.ObjectMapper,
              validator: javax.validation.Validator
          ): FromEntityUnmarshaller[A] = {
            Unmarshaller.firstOf(structuredJsonEntityUnmarshaller, stringyJsonEntityUnmarshaller)
              .flatMap(_ => _ => json => FastFuture(implicitly[GuardrailDecoder[A]].decode(json)))
          }
       """,
      q"""
          def unmarshallJson[A: GuardrailDecoder: GuardrailValidator: scala.reflect.ClassTag](
              implicit mapper: com.fasterxml.jackson.databind.ObjectMapper,
              validator: javax.validation.Validator
          ): Unmarshaller[${jsonType}, A] =
            Unmarshaller { _ => value => FastFuture(implicitly[GuardrailDecoder[A]].decode(value)) }
       """,
      q"""
          // Translate String => JsonNode by parsing
          def jsonParsingUnmarshaller(
              implicit mapper: com.fasterxml.jackson.databind.ObjectMapper
          ): FromStringUnmarshaller[${jsonType}] = Unmarshaller {
            _ => data => FastFuture(scala.util.Try(mapper.readTree(data)))
          }
       """,
      q"""
          // Translate String => JsonNode by treaing as a JSON literal
          final val jsonStringyUnmarshaller: FromStringUnmarshaller[${jsonType}] = Unmarshaller.strict {
            case data =>
              new com.fasterxml.jackson.databind.node.TextNode(data)
          }
       """,
      q"""
          // Translate String => [A: GuardrailDecoder]
          def jsonDecoderUnmarshaller[A: GuardrailDecoder: GuardrailValidator: scala.reflect.ClassTag](
              implicit mapper: com.fasterxml.jackson.databind.ObjectMapper,
              validator: javax.validation.Validator
          ): Unmarshaller[${jsonType}, A] =
            Unmarshaller { _ => json =>
              FastFuture(implicitly[GuardrailDecoder[A]].decode(json))
            }
       """
    )
  }

  override def getFrameworkDefinitions(tracing: Boolean) =
    Target.pure(List.empty)

  override def lookupStatusCode(key: String) =
    key match {
      case "100" => Target.pure((100, q"Continue"))
      case "101" => Target.pure((101, q"SwitchingProtocols"))
      case "102" => Target.pure((102, q"Processing"))

      case "200" => Target.pure((200, q"OK"))
      case "201" => Target.pure((201, q"Created"))
      case "202" => Target.pure((202, q"Accepted"))
      case "203" => Target.pure((203, q"NonAuthoritativeInformation"))
      case "204" => Target.pure((204, q"NoContent"))
      case "205" => Target.pure((205, q"ResetContent"))
      case "206" => Target.pure((206, q"PartialContent"))
      case "207" => Target.pure((207, q"MultiStatus"))
      case "208" => Target.pure((208, q"AlreadyReported"))
      case "226" => Target.pure((226, q"IMUsed"))

      case "300" => Target.pure((300, q"MultipleChoices"))
      case "301" => Target.pure((301, q"MovedPermanently"))
      case "302" => Target.pure((302, q"Found"))
      case "303" => Target.pure((303, q"SeeOther"))
      case "304" => Target.pure((304, q"NotModified"))
      case "305" => Target.pure((305, q"UseProxy"))
      case "307" => Target.pure((307, q"TemporaryRedirect"))
      case "308" => Target.pure((308, q"PermanentRedirect"))

      case "400" => Target.pure((400, q"BadRequest"))
      case "401" => Target.pure((401, q"Unauthorized"))
      case "402" => Target.pure((402, q"PaymentRequired"))
      case "403" => Target.pure((403, q"Forbidden"))
      case "404" => Target.pure((404, q"NotFound"))
      case "405" => Target.pure((405, q"MethodNotAllowed"))
      case "406" => Target.pure((406, q"NotAcceptable"))
      case "407" => Target.pure((407, q"ProxyAuthenticationRequired"))
      case "408" => Target.pure((408, q"RequestTimeout"))
      case "409" => Target.pure((409, q"Conflict"))
      case "410" => Target.pure((410, q"Gone"))
      case "411" => Target.pure((411, q"LengthRequired"))
      case "412" => Target.pure((412, q"PreconditionFailed"))
      case "413" =>
        Target.pure(
          (
            413,
            pekkoHttpVersion match {
              case PekkoHttpVersion.V1_0_0 => q"PayloadTooLarge"
              case PekkoHttpVersion.V1_1_0 => q"ContentTooLarge"
            }
          )
        )
      case "414" => Target.pure((414, q"RequestUriTooLong"))
      case "415" => Target.pure((415, q"UnsupportedMediaType"))
      case "416" => Target.pure((416, q"RequestedRangeNotSatisfiable"))
      case "417" => Target.pure((417, q"ExpectationFailed"))
      case "418" => Target.pure((418, q"ImATeapot"))
      case "420" => Target.pure((420, q"EnhanceYourCalm"))
      case "422" => Target.pure((422, q"UnprocessableEntity"))
      case "423" => Target.pure((423, q"Locked"))
      case "424" => Target.pure((424, q"FailedDependency"))
      case "425" => Target.pure((425, q"UnorderedCollection"))
      case "426" => Target.pure((426, q"UpgradeRequired"))
      case "428" => Target.pure((428, q"PreconditionRequired"))
      case "429" => Target.pure((429, q"TooManyRequests"))
      case "431" => Target.pure((431, q"RequestHeaderFieldsTooLarge"))
      case "449" => Target.pure((449, q"RetryWith"))
      case "450" => Target.pure((450, q"BlockedByParentalControls"))
      case "451" => Target.pure((451, q"UnavailableForLegalReasons"))

      case "500" => Target.pure((500, q"InternalServerError"))
      case "501" => Target.pure((501, q"NotImplemented"))
      case "502" => Target.pure((502, q"BadGateway"))
      case "503" => Target.pure((503, q"ServiceUnavailable"))
      case "504" => Target.pure((504, q"GatewayTimeout"))
      case "505" => Target.pure((505, q"HTTPVersionNotSupported"))
      case "506" => Target.pure((506, q"VariantAlsoNegotiates"))
      case "507" => Target.pure((507, q"InsufficientStorage"))
      case "508" => Target.pure((508, q"LoopDetected"))
      case "509" => Target.pure((509, q"BandwidthLimitExceeded"))
      case "510" => Target.pure((510, q"NotExtended"))
      case "511" => Target.pure((511, q"NetworkAuthenticationRequired"))
      case "598" => Target.pure((598, q"NetworkReadTimeout"))
      case "599" => Target.pure((599, q"NetworkConnectTimeout"))
      case _     => Target.raiseUserError(s"Unknown HTTP status code: ${key}")
    }
}
