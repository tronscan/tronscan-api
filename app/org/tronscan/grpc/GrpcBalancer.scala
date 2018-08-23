package org.tronscan.grpc

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Cancellable, Props, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import io.grpc.ManagedChannelBuilder
import org.tron.api.api.WalletGrpc.WalletStub
import org.tron.api.api.{EmptyMessage, WalletGrpc}
import org.tronscan.Extensions._

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

class GrpcBalancer extends Actor {

  // TODO All the seednodes, make this dynamic later
  val ips = List(
    "52.56.56.149",
    "54.236.37.243",
    "52.53.189.99",
    "18.196.99.16",
    "34.253.187.192",
    "35.180.51.163",
    "54.252.224.209",
    "18.228.15.36",
    "52.15.93.92",
    "34.220.77.106",
    "13.127.47.162",
    "13.124.62.58",
    "13.229.128.108",
    "35.182.37.246",
    "34.200.228.125",
    "18.220.232.201",
    "13.57.30.186",
    "35.165.103.105",
    "18.184.238.21",
    "34.250.140.143",
    "35.176.192.130",
    "52.47.197.188",
    "52.62.210.100",
    "13.231.4.243",
    "18.231.76.29",
    "35.154.90.144",
    "13.125.210.234",
    "13.250.40.82",
    "35.183.101.48",
  )


  var nodeStatuses = Map[String, GrpcStats]()

  def buildRouter(nodeIps: List[String]) = {
    val routees = nodeIps.map { ip =>
      val r = context.actorOf(Props(classOf[GrpcClient], ip))
      context watch r
      ActorRefRoutee(r)
    }

    Router(RoundRobinRoutingLogic(), routees.toVector)
  }

  def buildRouterWithRefs(nodeIps: List[ActorRef]) = {
    val routees = nodeIps.map { ref =>
      context watch ref
      ActorRefRoutee(ref)
    }

    Router(RoundRobinRoutingLogic(), routees.toVector)
  }

  var router = buildRouter(ips)

  def cleanup() = {

    // Cleanup nodes which don't share the common chain
    val chainCounts = nodeStatuses.values
      .flatMap(_.blocks.map(_.hash))
      .groupBy(x => x)
      .map(x => (x._1, x._2.size))

    val mostCommonBlock = chainCounts.toList.maxBy(x => x._2)._1

    val validChainNodes = for {
      (ip, stats) <- nodeStatuses
      if stats.blocks.exists(_.hash == mostCommonBlock)
    } yield stats

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

class GrpcClient(ip: String) extends Actor {

  var pinger: Option[Cancellable] = None
  var latency = 999999L
  var currentBlock = GrpcBlock(0, "")
  var latestBlocks = List[GrpcBlock]()

  lazy val channel = ManagedChannelBuilder
    .forAddress(ip, 50051)
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
        ip = ip,
        responseTime = responseTime,
        blocks = latestBlocks
      )
    }).recover {
      case _ =>
        context.parent ! GrpcStats(
          ref = self,
          ip = ip,
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
