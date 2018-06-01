package org.tronscan.watchdog

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.util.Timeout
import io.grpc.ManagedChannel
import javax.inject.{Inject, Named}
import org.apache.commons.lang3.exception.ExceptionUtils
import org.joda.time.DateTime
import org.tron.api.api.{Node => _, _}
import org.tronscan.grpc.GrpcClients
import org.tronscan.grpc.GrpcPool.{Channel, RequestChannel}
import org.tronscan.service.GeoIPService
import org.tronscan.watchdog.NodeWatchDog._
import play.api.inject.ConfigurationProvider
import play.api.libs.concurrent.Futures

import scala.concurrent.Future
import scala.concurrent.duration._

object NodeWatchDog {

  val solidity = 1
  val full = 2

  case class Node(
    ip: String,
    port: Int,
    nodeType: Int = full,
    hostname: String = "",
    lastSeen: DateTime = DateTime.now,
    permanent: Boolean = false,
    lastBlock: Long = 0L,
    grpcEnabled: Boolean = false,
    grpcResponseTime: Long = 0,
    country: String = "",
    city: String = "",
    lat: Double = 0,
    lng: Double = 0)

  case class RefreshNodes()
  case class CleanupNodes()
  case class RequestStatus()
  case class NodeStatus(nodes: List[Node], status: String = "waiting_for_first_sync")
  case class UpdateNode(node: Node)
  case class GetBestNodes(number: Int, filter: Node => Boolean = b => true)

  case class NodeChannel(ip: String, port: Int = 50051, channel: ManagedChannel) {
    lazy val full = WalletGrpc.stub(channel)
    lazy val solidity = WalletSolidityGrpc.stub(channel)

    def close() : Unit = {
      channel.shutdown()
      channel.awaitTermination(6, TimeUnit.SECONDS)
    }
  }

}

class NodeWatchDog @Inject()(
  configurationProvider: ConfigurationProvider,
  @Named("grpc-pool") actorRef: ActorRef,
  geoIPService: GeoIPService,
  implicit val futures: Futures) extends Actor {

  val workContext = context.system.dispatchers.lookup("contexts.node-watcher")

  var networkNodes = Map[String, Node]()
  var syncStatus = "waiting_for_first_sync"

  val decider: Supervision.Decider = {
    case exc =>
      println("WATCHDOG ERROR", exc, ExceptionUtils.getStackTrace(exc))
      Supervision.Resume
  }

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider))(context)


  def channelFromIp(ip: String, port: Int = 50051) = {
    implicit val executionContext = workContext
    implicit val timeout = Timeout(5.seconds)
    (actorRef ? RequestChannel(ip, 50051)).mapTo[Channel].map(_.channel)
  }


  def channelFromNode(node: Node) = {
    implicit val executionContext = workContext
    implicit val timeout = Timeout(5.seconds)
    (actorRef ? RequestChannel(node.ip, node.port)).mapTo[Channel].map(_.channel)
  }

  def nodeFromIp(ip: String, port: Int = 50051) = {
    implicit val executionContext = workContext

    for {
      channel <- channelFromIp(ip, port)
    } yield NodeChannel(ip, port, channel)
  }

  def buildReadStream = {
    implicit val executionContext = workContext
    Flow[String]
        .via(Streams.networkScanner(nodeFromIp(_)))
        .via(Streams.distinct)
  }

  def readNodeChannels(ips: List[String]): Source[String, NotUsed] = {
    Source(ips)
      .via(buildReadStream)
      .via(buildReadStream)
      .via(buildReadStream)
  }

  def readNodeHealth: Flow[String, Node, NotUsed] = {
    implicit val executionContext = workContext
    Flow[String]
      .via(Streams.networkPinger(nodeFromIp(_), 4))
  }

  def seedNodes = {

    import scala.collection.JavaConverters._

    val config = configurationProvider.get

    config.underlying.getStringList("fullnode.list").asScala.map { uri =>
      val Array(ip, port) = uri.split(":")
      (ip, port.toInt)
    }.toList
  }

  def soliditySeedNodes = {

    import scala.collection.JavaConverters._

    val config = configurationProvider.get

    config.underlying.getStringList("solidity.list").asScala.map { uri =>
      val Array(ip, port) = uri.split(":")
      (ip, port.toInt)
    }.toList
  }

  def includeGeo(node: Node) = {
    implicit val executionContext = workContext

    geoIPService.findForIp(node.ip).map { geo =>
      node.copy(
        country = geo.country,
        city = geo.city,
        lat = geo.lat,
        lng = geo.lng,
      )
    }
  }

  def startReader() = {
    implicit val executionContext = workContext

    Source.tick(10.seconds, 2.minutes, seedNodes.map(_._1))
      .flatMapConcat(readNodeChannels)
      .via(readNodeHealth)
      .mapAsync(4)(includeGeo)
      .map(n => {
        self ! UpdateNode(n)
        n
      })
      .toMat(Sink.ignore)(Keep.right)
      .run
      .recover {
        case x =>
          println("STREAM CRASH", x)
      }
  }

  def getBestNodes(count: Int, filter: Node => Boolean = n => true) = {
    networkNodes.values.filter(filter).filter(_.grpcEnabled).toList.sortBy(_.grpcResponseTime).take(count)
  }

  def cleanup() = {
    val cleanupAfter = DateTime.now.minusMinutes(30)
    networkNodes = networkNodes
      .filter {
        case (ip, node) if node.permanent =>
          true
        case (ip, node) if node.lastSeen.isAfter(cleanupAfter) =>
          true
        case _ =>
          false
      }
  }

  override def preStart(): Unit = {
    implicit val executionContext = workContext

    seedNodes.map { case (ip, port) =>
      nodeFromIp(ip, port).map { _ =>
        self ! UpdateNode(Node(
          ip = ip,
          permanent = true,
          port = port,
          grpcEnabled = true,
          grpcResponseTime = 1,
        ))
      }
    }

    soliditySeedNodes.map { case (ip, port) =>
      nodeFromIp(ip, port).map { _ =>
        self ! UpdateNode(Node(
          ip = ip,
          nodeType = NodeWatchDog.solidity,
          permanent = true,
          port = port,
          grpcEnabled = true,
          grpcResponseTime = 1,
        ))
      }
    }

    val watchdogEnabled = configurationProvider.get.get[Boolean]("nodeWatchdog")
    println("WATCHDOG ENABLED", watchdogEnabled)
    if (watchdogEnabled) {
      startReader()
      context.system.scheduler.schedule(5.minutes, 1.minute, self, CleanupNodes())
    }
  }

  def updateNode(node: Node) = {
    networkNodes.get(node.ip) match {
      case Some(existingNode) if !existingNode.permanent =>
        networkNodes = networkNodes + (node.ip -> node)
      case None =>
        networkNodes = networkNodes + (node.ip -> node)
      case x =>
        println("ignoring update", x)
    }
  }

  def receive = {
    case UpdateNode(node) =>
      syncStatus = "ready"
      updateNode(node)

    case RequestStatus() =>
      sender() ! NodeStatus(networkNodes.values.toList, syncStatus)

    case CleanupNodes() =>
      cleanup()

    case GetBestNodes(count, filter) =>

      import context.dispatcher

      val s = sender()

      Future.sequence(getBestNodes(count, filter)
        .map { n => channelFromNode(n) })
        .map { channels =>
          s ! GrpcClients(channels)
        }
  }
}
