package org.tronscan.websockets

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import javax.inject.{Inject, Named, Provider}
import org.tronscan.events._
import org.tronscan.tools.nodetester._
import org.tronscan.websockets.SocketCodecs._
import play.api.Logger
import play.api.libs.json.JsString
import play.engineio.EngineIOController
import play.socketio.SocketIOSession
import play.socketio.scaladsl.SocketIO

import scala.concurrent.duration._

case class SocketClient(params: Map[String, String] = Map.empty)

class SocketIOEngine @Inject() (
  socket: SocketIO,
  actorMaterializer: Materializer,
  @Named("grpc-pool") grpcPool: ActorRef,
  actorSystem: ActorSystem) extends Provider[EngineIOController] {

  implicit val materializer = actorMaterializer
  implicit val system = actorSystem
  implicit val timeout = Timeout(15.seconds)

  def buildAddressListener(address: String) = {
    val source = Source.actorRef[Any](500, OverflowStrategy.dropHead)
      .mapMaterializedValue { actorRef =>
        actorSystem.eventStream.subscribe(actorRef, classOf[AddressEvent])
      }
      .filter {
        case ev: AddressEvent if ev.isAddress(address)  =>
          true
        case _ =>
          false
      }

    Flow.fromSinkAndSourceCoupled(Sink.ignore, source)
  }

  def buildNodeTester(address: String) = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val source = NodeTesterStreams.buildForAddress(address, grpcPool)

    Flow.fromSinkAndSourceCoupled(Sink.ignore, source)
  }

  def buildSignListener(channel: String) = {

    var ref = ActorRef.noSender
    val receiver = Sink.foreach[Any] {
      case SignRequestCallback(transaction, callback) =>
        actorSystem.eventStream.publish(RequestTransactionSign(channel, transaction, callback))

      case appLogin: AppLogin =>
        actorSystem.eventStream.publish(appLogin)

      case x =>
        Logger.debug("IGNORED " + x.toString)
    }

    val source = Source.actorRef[Any](500, OverflowStrategy.dropHead)
      .mapMaterializedValue { actorRef =>
        ref = actorRef
        actorSystem.eventStream.subscribe(actorRef, classOf[SignEvent])
        actorSystem.eventStream.subscribe(actorRef, classOf[AppLogin])
      }
      .filter {
        case ev: SignEvent if ev.channel == channel =>
          true
        case _: AppLogin =>
          true
        case _ =>
          false
      }

    Flow.fromSinkAndSourceCoupled(receiver, source)
  }

  def buildBlockChainListener() = {

    val source = Source.actorRef[Any](500, OverflowStrategy.dropHead)
      .mapMaterializedValue { actorRef =>
        actorSystem.eventStream.subscribe(actorRef, classOf[BlockChainEvent])
      }

    Flow.fromSinkAndSourceCoupled(Sink.ignore, source)
  }

  def get() = {
    socket.builder
      .withErrorHandler {
        case x: Exception =>
          JsString("Exception: " + x.getMessage)
      }
      .onConnect { (request, _) =>
          SocketClient(request.queryString.map(x => (x._1, x._2.mkString)))
      }
      .addNamespace(decoder, encoder) {
        case (session@SocketIOSession(sessionId, request), namespace) =>
          namespace.split("\\?")(0).split("-") match {
            case Array("/address", address) =>
              buildAddressListener(address)
            case Array("/blockchain") =>
              buildBlockChainListener()
//            case Array("/sign", code) =>
//              buildSignListener(code)
            case Array("/nodetest", address) =>
              buildNodeTester(address)
            case _ =>
              throw new Exception("Invalid channel")
          }

      }
//      .defaultNamespace(decoder, encoder, chatFlow)
      .createController()
  }

}
