package org.tronscan.network

import java.net.InetAddress
import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import org.tron.api.api.EmptyMessage
import play.api.libs.concurrent.Futures
import play.api.libs.concurrent.Futures._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import org.tronscan.Extensions._
import org.tronscan.utils.NetworkUtils
import play.api.libs.ws.WSClient

object NetworkStreams {

  /**
    * Scans the given IP address
    */
  def networkScanner(nodeFromIp: NodeAddress => Future[NodeChannel], parallel: Int = 4)(implicit executionContext: ExecutionContext) = {
    Flow[NodeAddress]
      .mapAsyncUnordered(parallel) { ip =>

        (for {
          nc <- nodeFromIp(ip)
          nodeList <- nc.full.withDeadlineAfter(15, TimeUnit.SECONDS).listNodes(EmptyMessage())
        } yield {
          nodeList.nodes.map(_.address.get).map(a => NodeAddress(a.host.decodeString, a.port))
        }).recover {
          case _ =>
            List.empty
        }
      }
      .mapConcat(_.toList)
  }

  /**
    * Ping the given IPS and returns a node
    *
    * @param nodeFromIp factory which creates a node from a string
    * @param parallel parallel number of processes
    */
  def grpcPinger(nodeFromIp: NodeAddress => Future[NodeChannel], parallel: Int = 4)(implicit executionContext: ExecutionContext, futures: Futures): Flow[NodeAddress, NetworkNode, NotUsed] = {
    Flow[NodeAddress]
      .mapAsyncUnordered(parallel) { nodeAddress =>

        val ia = InetAddress.getByName(nodeAddress.ip)
        val startPing = System.currentTimeMillis()

        (for {
          n <- nodeFromIp(nodeAddress)
          r <- n.full.withDeadlineAfter(5, TimeUnit.SECONDS).getNowBlock(EmptyMessage())
          response = System.currentTimeMillis() - startPing
          hostname <- Future(ia.getCanonicalHostName).withTimeout(6.seconds).recover { case _ => nodeAddress.ip }
        } yield {
          NetworkNode(
            ip = nodeAddress.ip,
            port = nodeAddress.port,
            lastBlock = r.getBlockHeader.getRawData.number,
            hostname = hostname,
            grpcEnabled = true,
            grpcResponseTime = response)
        }).recover {
          case _ =>
            NetworkNode(
              ip = nodeAddress.ip,
              hostname = ia.getCanonicalHostName,
              port = nodeAddress.port,
              grpcEnabled = false,
              grpcResponseTime = 0)
        }
      }
  }

  /**
    * Ping the given IPS and returns a node
    *
    * @param parallel parallel number of processes
    */
  def nodePinger(parallel: Int = 4)(implicit executionContext: ExecutionContext, futures: Futures): Flow[NetworkNode, NetworkNode, NotUsed] = {
    Flow[NetworkNode]
      .mapAsyncUnordered(parallel) { networkNode =>

        val ia = InetAddress.getByName(networkNode.ip)
        val startPing = System.currentTimeMillis()

        (for {
          online <- NetworkUtils.ping(networkNode.ip, networkNode.port)
          response = System.currentTimeMillis() - startPing
        } yield {
          if (online) {
            networkNode.copy(
              pingOnline = online,
              pingResponseTime = response,
            )
          } else {
            networkNode.copy(
              pingOnline = false,
            )
          }
        }).recover {
          case _ =>
            networkNode.copy(
              pingOnline = false,
            )
        }
      }
  }

  /**
    * Ping the given IPS and returns a node
    *
    * @param parallel parallel number of processes
    */
  def httpPinger(parallel: Int = 4)(implicit executionContext: ExecutionContext, wsClient: WSClient): Flow[NetworkNode, NetworkNode, NotUsed] = {
    Flow[NetworkNode]
      .mapAsyncUnordered(parallel) { networkNode =>

        val ia = InetAddress.getByName(networkNode.ip)
        val startPing = System.currentTimeMillis()

        (for {
          (url, online) <- NetworkUtils.pingHttp(s"http://${networkNode.ip}:8090/wallet/getnowblock").recoverWith {
            case _ =>
              NetworkUtils.pingHttp(s"http://${networkNode.ip}:8091/walletsolidity/getnowblock")
          }
          response = System.currentTimeMillis() - startPing
        } yield {
          if (online) {
            networkNode.copy(
              httpEnabled = online,
              httpResponseTime = response,
              httpUrl = url,
            )
          } else {
            networkNode.copy(httpEnabled = false)
          }
        }).recover {
          case _ =>
            networkNode.copy(httpEnabled = false)
        }
      }
  }
}
