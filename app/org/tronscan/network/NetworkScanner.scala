package org.tronscan.network

import akka.NotUsed
import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.util.Timeout
import javax.inject.{Inject, Named}
import org.apache.commons.lang3.exception.ExceptionUtils
import org.joda.time.DateTime
import org.tron.api.api.{Node => _}
import org.tronscan.grpc.GrpcClients
import org.tronscan.grpc.GrpcPool.{Channel, RequestChannel}
import org.tronscan.network.NetworkScanner._
import org.tronscan.service.GeoIPService
import org.tronscan.utils.StreamUtils
import play.api.Logger
import play.api.inject.ConfigurationProvider
import play.api.libs.concurrent.Futures

import scala.concurrent.Future
import scala.concurrent.duration._

object NetworkScanner {

  val solidity = 1
  val full = 2

  case class RefreshNodes()
  case class CleanupNodes()
  case class RequestStatus()
  case class NodeStatus(nodes: List[NetworkNode], status: String = "waiting_for_first_sync")
  case class UpdateNode(node: NetworkNode)
  case class GetBestNodes(number: Int, filter: NetworkNode => Boolean = b => true)
}

/**
  * Scans the network for nodes
  */
class NetworkScanner @Inject()(
  configurationProvider: ConfigurationProvider,
  @Named("grpc-pool") actorRef: ActorRef,
  geoIPService: GeoIPService,
  implicit val futures: Futures) extends Actor {

  val workContext = context.system.dispatchers.lookup("contexts.node-watcher")
  val debugEnabled = configurationProvider.get.get[Boolean]("network.scanner.debug")

  var networkNodes = Map[String, NetworkNode]()
  var syncStatus = "waiting_for_first_sync"

  val decider: Supervision.Decider = { exc =>
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

  def channelFromNode(node: NetworkNode) = {
    implicit val executionContext = workContext
    implicit val timeout = Timeout(5.seconds)
    (actorRef ? RequestChannel(node.ip, node.port)).mapTo[Channel].map(_.channel)
  }

  def nodeFromIp(nodeAddress: NodeAddress) = {
    implicit val executionContext = workContext

    for {
      channel <- channelFromIp(nodeAddress.ip, nodeAddress.port)
    } yield NodeChannel(nodeAddress.ip, nodeAddress.port, channel)
  }

  def buildReadStream: Flow[NodeAddress, NodeAddress, NotUsed] = {
    implicit val executionContext = workContext
    Flow[NodeAddress]
        .via(NetworkStreams.networkScanner(nodeFromIp))
        .via(StreamUtils.distinct)
        .map { node =>
          if (debugEnabled) {
            Logger.debug("Found Node: " + node)
          }
          node
        }
  }

  def readNodeChannels(ips: List[NodeAddress]) = {
    Source(ips)
      .via(buildReadStream)
      .via(buildReadStream)
      .via(buildReadStream)
  }

  def readNodeHealth: Flow[NodeAddress, NetworkNode, NotUsed] = {
    implicit val executionContext = workContext
    Flow[NodeAddress]
      .via(NetworkStreams.grpcPinger(nodeFromIp, 12))
      .map { node =>
        if (debugEnabled) {
          if (node.grpcEnabled) {
            Logger.debug("GRPC Online: " + node.hostname + ":" + node.ip)
          } else {
            Logger.debug("GRPC Offline: " + node.hostname + ":" + node.ip)
          }
        }
        node
      }
      .via(NetworkStreams.nodePinger(12))
      .map { node =>
        if (debugEnabled) {
          if (node.pingOnline) {
            Logger.debug("Online: " + node.hostname + ":" + node.ip)
          } else {
            Logger.debug("Offline: " + node.hostname + ":" + node.ip)
          }
        }
        node
      }
  }

  def seedNodes = {

    import scala.collection.JavaConverters._

    val config = configurationProvider.get

    config.underlying.getStringList("fullnode.list").asScala.map { uri =>
      val Array(ip, port) = uri.split(":")
      NodeAddress(ip, port.toInt)
    }.toList
  }

  def soliditySeedNodes = {

    import scala.collection.JavaConverters._

    val config = configurationProvider.get

    config.underlying.getStringList("solidity.list").asScala.map { uri =>
      val Array(ip, port) = uri.split(":")
      NodeAddress(ip, port.toInt)
    }.toList
  }

  def includeGeo(node: NetworkNode) = {
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

    Source.tick(10.seconds, 1.minute, seedNodes)
      .flatMapConcat(readNodeChannels)
      .via(readNodeHealth)
      .mapAsync(4)(includeGeo)
      .map(n => {
        self ! UpdateNode(n)
        n
      })
      .runWith(Sink.ignore)
      .recover { case x =>
        Logger.error("STREAM CRASH", x)
      }
  }

  def getBestNodes(count: Int, filter: NetworkNode => Boolean = n => true) = {
    networkNodes.values
      .filter(filter)
      // Only take nodes which have their GRPC ports open
      .filter(_.grpcEnabled).toList
      // Sort by the best response time first
      .sortBy(_.grpcResponseTime).take(count)
  }

  def cleanup() = {
    val cleanupAfter = DateTime.now.minusMinutes(30)
    networkNodes = networkNodes
      .filter {
        case (_, node) if node.permanent =>
          true
        case (_, node) if node.lastSeen.isAfter(cleanupAfter) =>
          true
        case _ =>
          false
      }
  }

  override def preStart(): Unit = {
    implicit val executionContext = workContext

    seedNodes.map { seedNodeAddress =>
      nodeFromIp(seedNodeAddress).map { _ =>
        self ! UpdateNode(NetworkNode(
          ip = seedNodeAddress.ip,
          port = seedNodeAddress.port,
          permanent = true,
          grpcEnabled = true,
          grpcResponseTime = 1,
        ))
      }
    }

    soliditySeedNodes.map { seedNodeAddress =>
      nodeFromIp(seedNodeAddress).map { _ =>
        self ! UpdateNode(NetworkNode(
          ip = seedNodeAddress.ip,
          port = seedNodeAddress.port,
          nodeType = NetworkScanner.solidity,
          permanent = true,
          grpcEnabled = true,
          grpcResponseTime = 1,
        ))
      }
    }

    val watchdogEnabled = configurationProvider.get.get[Boolean]("network.scanner.enabled")
    println("WATCHDOG ENABLED", watchdogEnabled)
    if (watchdogEnabled) {
      startReader()
      context.system.scheduler.schedule(5.minutes, 1.minute, self, CleanupNodes())
    }
  }

  def updateNode(node: NetworkNode) = {
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
