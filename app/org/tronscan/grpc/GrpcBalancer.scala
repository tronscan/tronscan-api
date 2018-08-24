package org.tronscan.grpc

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Cancellable, Props, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import cats.Inject
import io.grpc.ManagedChannelBuilder
import org.tron.api.api.WalletGrpc.WalletStub
import org.tron.api.api.{EmptyMessage, WalletGrpc}
import org.tronscan.Extensions._
import org.tronscan.network.NodeAddress
import play.api.inject.ConfigurationProvider
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

case class GrpcRequest(request: WalletStub => Future[Any])
case class GrpcRetry(request: GrpcRequest)
case class GrpcResponse(response: Any)
case class GrpcKill(actorRef: ActorRef)
case class GrpcBlock(num: Long, hash: String)

case class GrpcStats(
  ref: ActorRef,
  ip: String,
  responseTime: Long,
  blocks: List[GrpcBlock])

object GrpcBalancerOptions {
  val pingInterval = 9.seconds
  val blockListSize = 12
}

class GrpcBalancer @Inject() (configurationProvider: ConfigurationProvider) extends Actor {

  val config = configurationProvider.get

  val seedNodes = config.underlying.getStringList("fullnode.list").asScala.map { uri =>
    val Array(ip, port) = uri.split(":")
    NodeAddress(ip, port.toInt)
  }.toList

  val maxClients = config.get[Int]("grpc.balancer.maxClients")

  // Contains the stats of all the seednodes
  var nodeStatuses = Map[String, GrpcStats]()

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

  var router = buildRouter(seedNodes)

  def cleanup() = {

    // Cleanup nodes which don't share the common chain
    val chainCounts = nodeStatuses.values
      .flatMap(_.blocks.map(_.hash))
      .groupBy(x => x)
      .map(x => (x._1, x._2.size))

    // Find the most common block hash
    val mostCommonBlockHash = chainCounts.toList.maxBy(x => x._2)._1

    // Find all the nodes which have the common hash in their recent blocks
    val validChainNodes = for {
      (ip, stats) <- nodeStatuses
      if stats.blocks.exists(_.hash == mostCommonBlockHash)
    } yield stats

    // Take the 12 fastest nodes
    val fastestNodes = validChainNodes.toList.sortBy(_.responseTime).take(12)

    router = buildRouterWithRefs(fastestNodes.map(_.ref))
  }

  var pinger: Option[Cancellable] = None


  override def preStart(): Unit = {
    import context.dispatcher
    pinger = Some(context.system.scheduler.schedule(6.second, 6.seconds, self, "cleanup"))
  }

  override def postStop(): Unit = {
    pinger.foreach(_.cancel())
  }

  def receive = {
    case w: GrpcRequest ⇒
      router.route(w, sender())
    case GrpcRetry(request) =>
      router.route(request, sender())
    case GrpcKill(ref) ⇒
      router = router.removeRoutee(ref)
    case stats: GrpcStats =>
      nodeStatuses = nodeStatuses ++ Map(stats.ip -> stats)
    case Terminated(a) ⇒
      router = router.removeRoutee(a)
    case "cleanup" =>
      cleanup()
  }
}

class GrpcClient(nodeAddress: NodeAddress) extends Actor {

  var pinger: Option[Cancellable] = None
  var latency = 999999L
  var currentBlock = GrpcBlock(0, "")
  var latestBlocks = List[GrpcBlock]()

  lazy val channel = ManagedChannelBuilder
    .forAddress(nodeAddress.ip, nodeAddress.port)
    .usePlaintext(true)
    .build

  lazy val walletStub = {
    WalletGrpc.stub(channel)
  }

  def ping() = {
    import context.dispatcher

    val w = walletStub
    val startPing = System.currentTimeMillis()

    (for {
      block <- w.withDeadlineAfter(5, TimeUnit.SECONDS).getNowBlock(EmptyMessage())
      responseTime = System.currentTimeMillis() - startPing
    } yield {
      currentBlock = GrpcBlock(block.getBlockHeader.getRawData.number, block.hash)
      latestBlocks = (if (latestBlocks.size > GrpcBalancerOptions.blockListSize) latestBlocks.drop(1) else latestBlocks) :+ currentBlock
      latency = responseTime
      context.parent ! GrpcStats(
        ref = self,
        ip = nodeAddress.ip,
        responseTime = responseTime,
        blocks = latestBlocks
      )
    }).recover {
      case _ =>
        context.parent ! GrpcStats(
          ref = self,
          ip = nodeAddress.ip,
          responseTime = 9999L,
          blocks = List.empty
        )
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
      import context.dispatcher
      val s = sender()
      c.request(walletStub.withDeadlineAfter(5, TimeUnit.SECONDS)).map { x =>
        s ! GrpcResponse(x)
      }.recover {
        case _ =>
          context.parent.tell(GrpcRetry(c), s)
      }

    case "ping" =>
      ping()
  }
}
