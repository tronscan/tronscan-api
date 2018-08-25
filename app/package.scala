import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import monix.eval.Task
import monix.execution.Scheduler
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.http.{ContentTypeOf, ContentTypes, Writeable}
import play.api.libs.json.{JsObject, JsValue, Json => PlayJson}
import play.api.mvc.Codec

import scala.annotation.compileTimeOnly
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Awaitable, ExecutionContext, Future}
import scala.language.experimental.macros
import scala.concurrent.duration._

package object org {

  object ExecContext {
    implicit val work = ExecutionContext.Implicits.global
  }


  // Convert Monix tasks to future where needed, mostly used for scala async
  implicit def task2Future[A](task: Task[A])(implicit scheduler: Scheduler): Future[A] = task.runAsync
  implicit def task2FutureUnit(task: Task[Unit])(implicit scheduler: Scheduler): Future[Unit] = task.runAsync
  implicit def future2TaskUnit(future: Future[Unit])(implicit scheduler: Scheduler): Task[Unit] = Task.fromFuture(future).flatMap(x => Task.unit)
  implicit def future2Task[A](future: Future[A])(implicit scheduler: Scheduler): Task[A] = Task.fromFuture(future)

  // Circe Conversions
  implicit def circeToPlayJson(json: io.circe.Json): JsValue = PlayJson.parse(json.noSpaces)
  implicit def circeToPlayJsonObj(json: io.circe.Json): JsObject = PlayJson.parse(json.noSpaces).as[JsObject]
  implicit def playObjToCirce(json: JsObject): io.circe.JsonObject = io.circe.parser.parse(PlayJson.stringify(json)).right.toOption.flatMap(_.asObject).get
  //  implicit def circeObjToPlay(json: io.circe.JsonObject): JsObject  = PlayJson.parse(json.noSpaces)
  implicit def playToCirce(json: JsValue): Json = io.circe.parser.parse(PlayJson.stringify(json)).right.get

  implicit def circeToPlayJsonWrapper(json: io.circe.Json): PlayJson.JsValueWrapper = PlayJson.parse(json.noSpaces)

  implicit val jsonPlayDecoder: Decoder[JsValue] = Decoder.instance { cursor =>
    cursor.focus match {
      case Some(json) =>
        Right(PlayJson.parse(json.noSpaces))
      case _ =>
        Left(DecodingFailure("Play Json", cursor.history))
    }
  }

  implicit val jsonPlayEncoder: Encoder[JsValue] = Encoder.instance { cursor =>
    io.circe.parser.parse(PlayJson.stringify(cursor)).right.get
  }

  implicit val jsonObjectPlayEncoder: Encoder[JsObject] = Encoder.instance { cursor =>
    io.circe.parser.parse(PlayJson.stringify(cursor)).right.get
  }


  implicit def writeableOfCirceJson(implicit codec: Codec): Writeable[io.circe.Json] = {
    Writeable(data => codec.encode(data.noSpaces))
  }

  implicit def contentTypeCirceJson(implicit codec: Codec): ContentTypeOf[io.circe.Json] = {
    ContentTypeOf(Some(ContentTypes.JSON))
  }

  /**
    * Await.result help function
    */
  def awaitSync[T](awaitable: Awaitable[T]): T = Await.result(awaitable, Duration.Inf)

  def awaitSync[T](awaitable: Task[T]): T = Await.result(awaitable.runAsync(Scheduler.Implicits.global), Duration.Inf)

  def awaitSync[T](awaitable: Awaitable[T], seconds: Int): T = Await.result(awaitable, seconds.seconds)


  /**
    * Run the block of code `body` asynchronously. `body` may contain calls to `await` when the results of
    * a `Future` are needed; this is translated into non-blocking code.
    */
  def async[T](body: => T)(implicit execContext: ExecutionContext): Future[T] = macro scala.async.internal.ScalaConcurrentAsync.asyncImpl[T]

  /**
    * Non-blocking await the on result of `awaitable`. This may only be used directly within an enclosing `async` block.
    *
    * Internally, this will register the remainder of the code in enclosing `async` block as a callback
    * in the `onComplete` handler of `awaitable`, and will *not* block a thread.
    */
  @compileTimeOnly("`await` must be enclosed in an `async` block")
  def await[T](awaitable: Future[T]): T = ??? // No implementation here, as calls to this are translated to `onComplete` by the macro.


  val defaultJodaDatePattern = "yyyy-MM-dd"

  //implicit val DefaultJodaDateEncoder: Encoder[DateTime] = Encoder.instance[DateTime] { dateTime => Json.fromString(dateTime.toString("yyyy-MM-dd")) }
  implicit val defaultJodaDateDecoder: Decoder[DateTime] = jodaDateDecoder(defaultJodaDatePattern)
  implicit val defaultJodaDateEncode: Encoder[DateTime] = Encoder.instance { dt =>
    Json.fromLong(dt.getMillis)
  }

  /**
    * Decoder for the `org.joda.time.DateTime` type.
    *
    * @param pattern a pattern datetime
    * @return a Datetime decoded
    *
    * @see pattern at http://joda-time.sourceforge.net/apidocs/org/joda/time/format/ISODateTimeFormat.html
    */
  def jodaDateDecoder(pattern: String): Decoder[DateTime] = Decoder.instance { cursor =>
    cursor.focus.map {
      // String
      case json if json.isString =>
        tryParserDatetime(json.asString.get, pattern, DecodingFailure("DateTime", cursor.history))
      // Number
      case json if json.isNumber =>
        json.asNumber match {
          // Long
          case Some(num) if num.toLong.isDefined => Right(new DateTime(num.toLong.get))
          // unknown
          case _ => Left(DecodingFailure("DateTime", cursor.history))
        }
    }.getOrElse {
      // focus return None
      Left(DecodingFailure("DateTime", cursor.history))
    }
  }

  def runSync[T](awaitable: Awaitable[T]): T = Await.result(awaitable, Duration.Inf)

  def runSync[T](awaitable: Task[T]): T = Await.result(awaitable.runAsync(Scheduler.Implicits.global), Duration.Inf)

  /**
    * Try to parse a datetime as string through a pattern.
    *
    * @param input a string datetime
    * @param pattern a pattern datetime (e.g 'yyyy-MM-dd')
    * @return a DecodingFailure or a Datetime
    *
    * @see pattern at http://joda-time.sourceforge.net/apidocs/org/joda/time/format/ISODateTimeFormat.html
    */
  def tryParserDatetime(input: String, pattern: String, error: DecodingFailure): Either[DecodingFailure, DateTime] = {
    try {
      val format = DateTimeFormat.forPattern(pattern)
      val datetime = DateTime.parse(input, format)
      Right(datetime)
    } catch {
      case _: Exception => Left(error)
    }
  }
}
