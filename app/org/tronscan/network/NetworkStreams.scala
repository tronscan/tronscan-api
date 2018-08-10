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

object NetworkStreams {

  /**
    * Scans the given IP address
    */
  def networkScanner(nodeFromIp: String => Future[NodeChannel], parallel: Int = 4)(implicit executionContext: ExecutionContext, futures: Futures) = {
    Flow[String]
      .mapAsyncUnordered(parallel) { ip =>

        (for {
          nc <- nodeFromIp(ip)
          nodeList <- nc.full.withDeadlineAfter(15, TimeUnit.SECONDS).listNodes(EmptyMessage())
        } yield {
          nodeList.nodes.map { n =>
            new String(n.address.get.host.toByteArray)
          }
        }).recover {
          case _ =>
            List.empty
        }
      }
      .flatMapConcat(x => Source(x.toList))
  }

  /**
    * Ping the given IPS and returns a node
    *
    * @param nodeFromIp factory which creates a node from a string
    * @param parallel parallel number of processes
    */
  def networkPinger(nodeFromIp: String => Future[NodeChannel], parallel: Int = 4)(implicit executionContext: ExecutionContext, futures: Futures): Flow[String, NetworkNode, NotUsed] = {
    Flow[String]
      .mapAsyncUnordered(parallel) { ip =>

        val ia = InetAddress.getByName(ip)
        val startPing = System.currentTimeMillis()


        (for {
          n <- nodeFromIp(ip)
          r <- n.full.withDeadlineAfter(6, TimeUnit.SECONDS).getNowBlock(EmptyMessage())
          response = System.currentTimeMillis() - startPing
          hostname <- Future(ia.getCanonicalHostName).withTimeout(6.seconds).recover { case _ => ip }
        } yield {

          NetworkNode(
            ip = ip,
            port = 500051,
            lastBlock = r.getBlockHeader.getRawData.number,
            hostname = hostname,
            grpcEnabled = true,
            grpcResponseTime = response)
        }).recover {
          case _ =>
            NetworkNode(
              ip = ip,
              hostname = ia.getCanonicalHostName,
              port = 500051,
              grpcEnabled = false,
              grpcResponseTime = 0)
        }
      }
  }
}
