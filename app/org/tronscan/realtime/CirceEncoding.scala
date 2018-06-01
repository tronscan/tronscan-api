package org
package tronscan.realtime

import java.nio.charset.StandardCharsets

import akka.util.ByteString
import io.circe.{Decoder, Encoder}
import play.api.libs.json.{JsValue, Json, Writes}
import play.socketio.scaladsl.{SocketIOArgDecoder, SocketIOArgEncoder}

object CirceDecoder {

  /**
    * Create a decoder from the given decode function.
    */
  def apply[T](decode: Either[JsValue, ByteString] => T): SocketIOArgDecoder[T] = new SocketIOArgDecoder[T] {
    override def decodeArg(arg: Either[JsValue, ByteString]) = decode(arg)
  }

  /**
    * Create a JSON decoder.
    */
  def fromJson[T](implicit decoder: Decoder[T]): SocketIOArgDecoder[T] = apply {
    case Left(jsValue) =>
      decoder.decodeJson(jsValue).right.get
    case Right(bytes)  =>
      decoder.decodeJson(io.circe.parser.parse(new String(bytes.toArray, StandardCharsets.UTF_8)).right.get).right.get
  }
}


/**
  * Convenience functions for creating [[SocketIOArgEncoder]] instances.
  */
object CirceEncoder {

  /**
    * Create an arg encoder from the given function.
    */
  def apply[T](encoder: T => Either[JsValue, ByteString]): SocketIOArgEncoder[T] = new SocketIOArgEncoder[T] {
    override def encodeArg(t: T) = encoder(t)
  }

  /**
    * Create a json arg encoder.
    */
  def toJson[T](implicit encode: Encoder[T]): SocketIOArgEncoder[T] = apply { t =>
    Left(Json.parse(encode.apply(t).noSpaces))
  }
}
