package org.tronscan.tools.nodetester

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.{Flow, Source}
import org.apache.commons.lang3.exception.ExceptionUtils
import org.tron.api.api.{EmptyMessage, WalletGrpc, WalletSolidityGrpc}
import org.tronscan.grpc.GrpcPool
import org.tronscan.grpc.GrpcPool.Channel
import akka.pattern.ask

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object NodeTesterStreams {

  type IpPort = (String, Int)

  /**
    * Try and detect what type of the the given ip and port is
    *
    * First try full node, if it throws error then try solidity
    */
  def detectNodeType(grpcPool: ActorRef) = {
    Flow[IpPort]
      .mapAsync(1) { case (ip, port) =>

        (for {
          channel <- (grpcPool ? GrpcPool.RequestChannel(ip, port)).mapTo[Channel]
          client = WalletGrpc.stub(channel.channel)
          _ <- client.getNowBlock(EmptyMessage())
        } yield {
          Some(FullNodeTest(client))
        }).recoverWith {
          case exc =>
            (for {
              channel <- (grpcPool ? GrpcPool.RequestChannel(ip, port)).mapTo[Channel]
              client = WalletSolidityGrpc.stub(channel.channel)
              _ <- client.getNowBlock(EmptyMessage())
            } yield {
              Some(SolidityTest(client))
            }).recover {
              case x =>
                None
            }
        }
      }
  }

  /**
    * Handle a nodetest
    *
    * - Call GetNowBlock and calculate milliseconds
    */
  def pingNode(grpcPool: ActorRef): Flow[NodeTest, NodeStatus, NotUsed] = {
    Flow[NodeTest]
      .mapAsync(1) { node: NodeTest =>
        val startTime = System.currentTimeMillis()
        (for {
          request <- node.latestBlock
          endTime = System.currentTimeMillis()
          responseTime: Long = endTime - startTime
        } yield {
          NodeStatus(
            msg = s"${node.name}->getNowBlock: " + request.getBlockHeader.getRawData.number, // + ", Num->" + latestBlock,
            responseTime = responseTime
          )
        }).recover {
          case exc =>
            val endTime = System.currentTimeMillis()
            val responseTime: Long = endTime - startTime
            NodeStatus(
              msg = s"Error: ${ExceptionUtils.getMessage(exc)}",
              responseTime = responseTime
            )
        }
      }
  }

  def buildForAddress(address: String, grpcPool: ActorRef)(implicit executionContext: ExecutionContext) = {
    Source.single(address)
      // Convert Address to IP and Port
      .map {
        case hostname if hostname.contains(":") =>
          val Array(ip, port) = hostname.split(":")
          (ip, port.toInt)
        case hostname =>
          (hostname, 50051)
      }
      // Try to test for full node
      .via(detectNodeType(grpcPool))
      // Change stream to a poller
      .flatMapConcat { node => Source.tick(1.second, 5.seconds, node.get) }
      // Start pinging the node
      .via(pingNode(grpcPool))
  }
}
