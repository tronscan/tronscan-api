package org.tronscan.grpc

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Cancellable, Props, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import io.grpc.ManagedChannelBuilder
import javax.inject.Inject
import org.tron.api.api.WalletGrpc.WalletStub
import org.tron.api.api.{EmptyMessage, WalletGrpc}
import org.tronscan.Extensions._
import org.tronscan.network.NodeAddress
import play.api.inject.ConfigurationProvider

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

case class GrpcRequest(request: WalletStub => Future[Any])
case class GrpcRetry(request: GrpcRequest, sender: ActorRef)
case class GrpcResponse(response: Any)
case class GrpcBlock(num: Long, hash: String)
case class OptimizeNodes()
case class GrpcBalancerStats(
  activeNodes: List[GrpcStats] = List.empty,
  nodes: List[GrpcStats] = List.empty)
case class GrpcBalancerRequest()

case class GrpcStats(
  ref: ActorRef,
  ip: String,
  responseTime: Long,
  blocks: List[GrpcBlock],
  requestHandled: Int = 0,
  requestErrors: Int = 0
)

object GrpcBalancerOptions {
  val pingInterval = 9.seconds
  val blockListSize = 12
}

/**
  * Manages the seedNodes and periodically checks which nodes are the fastest
  * The fastest nodes will be used to handle GRPC calls
  */
class GrpcBalancer @Inject() (configurationProvider: ConfigurationProvider) extends Actor {

  val config = configurationProvider.get

  val seedNodes = config.underlying.getStringList("fullnode.list").asScala.map { uri =>
    val Array(ip, port) = uri.split(":")
    NodeAddress(ip, port.toInt)
  }.toList

  val maxClients = config.get[Int]("grpc.balancer.maxClients")

  // Contains the stats of all the seednodes
  var nodeStatuses = Map[String, GrpcStats]()
  var router = buildRouter(seedNodes)
  var totalStats = GrpcBalancerStats()

  def buildRouter(nodeIps: List[NodeAddress]) = {
    val routeRefs = nodeIps.map { ip =>
      context.actorOf(Props(classOf[GrpcClient], ip))
    }

    buildRouterWithRefs(routeRefs)
  }

  def buildRouterWithRefs(nodeIps: List[ActorRef]) = {
    val routees = nodeIps.map { ref =>
      context watch ref
      ActorRefRoutee(ref)
    }

    Router(RoundRobinRoutingLogic(), routees.toVector)
  }

  def optimizeNodes() = {

    // Cleanup nodes which don't share the common chain
    val chainCounts = nodeStatuses.values
      .flatMap(_.blocks.map(_.hash))
      .groupBy(x => x)
      .map(x => (x._1, x._2.size))

    // Find the most common block hash
    val mostCommonBlockHash = chainCounts.toList.maxBy(x => x._2)._1

    // Find all the nodes which have the common hash in their recent blocks
    val validChainNodes = for {
      (_, stats) <- nodeStatuses
      if stats.blocks.exists(_.hash == mostCommonBlockHash)
    } yield stats

    // Take the 12 fastest nodes
    val sortedNodes = validChainNodes.toList.sortBy(_.responseTime)
    val fastestNodes = sortedNodes.take(maxClients)

    totalStats = GrpcBalancerStats(
      activeNodes = fastestNodes,
      nodes = sortedNodes.drop(maxClients)
    )

    router = buildRouterWithRefs(fastestNodes.map(_.ref))
  }

  var pinger: Option[Cancellable] = None

  override def preStart(): Unit = {
    import context.dispatcher
    pinger = Some(context.system.scheduler.schedule(4.second, 6.seconds, self, OptimizeNodes()))
  }

  override def postStop(): Unit = {
    pinger.foreach(_.cancel())
  }

  def receive = {
    case w: GrpcRequest ⇒
      router.route(w, sender())
    case GrpcRetry(request, s) =>
      router.route(request, s)
    case stats: GrpcStats =>
      nodeStatuses = nodeStatuses ++ Map(stats.ip -> stats)
    case Terminated(a) ⇒
      router = router.removeRoutee(a)
    case OptimizeNodes() =>
      optimizeNodes()
    case GrpcBalancerRequest() =>
      sender() ! totalStats
  }
}

/**
  * Connects to GRPC and periodically pings the port to determine latency
  */
class GrpcClient(nodeAddress: NodeAddress) extends Actor {

  var pinger: Option[Cancellable] = None
  var latestBlocks = List[GrpcBlock]()
  var requestsHandled = 0
  var requestErrors = 0

  lazy val channel = ManagedChannelBuilder
    .forAddress(nodeAddress.ip, nodeAddress.port)
    .usePlaintext(true)
    .build

  lazy val walletStub = {
    WalletGrpc.stub(channel)
  }

  /**
    * Ping the node and gather stats, send it back to the balancer
    */
  def ping() = {
    import context.dispatcher

    val w = walletStub
    val startPing = System.currentTimeMillis()

    (for {
      block <- w.withDeadlineAfter(5, TimeUnit.SECONDS).getNowBlock(EmptyMessage())
      responseTime = System.currentTimeMillis() - startPing
    } yield {

      val currentBlock = GrpcBlock(block.getBlockHeader.getRawData.number, block.hash)

      // Keep a maximum of 5 recent blocks in the list
      latestBlocks = (if (latestBlocks.size > GrpcBalancerOptions.blockListSize) latestBlocks.drop(1) else latestBlocks) :+ currentBlock

      context.parent ! GrpcStats(
        ref = self,
        ip = nodeAddress.ip,
        responseTime = responseTime,
        blocks = latestBlocks,
        requestHandled = requestsHandled,
        requestErrors = requestErrors,
      )

      requestsHandled += 1
    }).recover {
      case _ =>
        context.parent ! GrpcStats(
          ref = self,
          ip = nodeAddress.ip,
          responseTime = 9999L,
          blocks = List.empty
        )
        requestErrors += 1
    }
  }

  def handleRequest(request: GrpcRequest, s: ActorRef) = {
    import context.dispatcher
    request.request(walletStub.withDeadlineAfter(5, TimeUnit.SECONDS)).map { x =>
      s ! GrpcResponse(x)
      requestsHandled += 1
    }.recover {
      case _ =>
        requestErrors += 1
        context.parent.tell(GrpcRetry(request, s), s)
    }
  }

  override def preStart(): Unit = {
    import context.dispatcher
    pinger = Some(context.system.scheduler.schedule(0.second, GrpcBalancerOptions.pingInterval, self, "ping"))
  }

  override def postStop(): Unit = {
    pinger.foreach(_.cancel())
  }

  def receive = {
    case c: GrpcRequest =>
      handleRequest(c, sender())

    case "ping" =>
      ping()
  }
}
