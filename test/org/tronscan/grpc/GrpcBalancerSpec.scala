package org
package tronscan.grpc

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.specs2.mutable._
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletGrpc.WalletStub

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class GrpcBalancerSpec extends Specification {

  /*
  "GRPC Balancer" should {

    "Read Nodes" in {

      val actorSystem = ActorSystem()
      val grpcBalancer = actorSystem.actorOf(Props[GrpcBalancer])
      implicit val timeout = Timeout(25.seconds)


      Thread.sleep(10000)

      println("SENDING CLEANUP")
      grpcBalancer ! "cleanup"

      Thread.sleep(5000)

      def doRequest[A](request: WalletStub => Future[A]) = {
        (grpcBalancer ? GrpcRequest(request)).mapTo[GrpcResponse].map(_.response.asInstanceOf[A])
      }

      for (_ <- 1 to 25) {

        val response = for (i <- 1 to 50) yield {
          doRequest(_.withDeadlineAfter(1, TimeUnit.SECONDS).getNowBlock(EmptyMessage()))
        }

        val start = System.currentTimeMillis()

        awaitSync(Future.sequence(response)).foreach { block =>
          println("BLOCK: " + block.getBlockHeader.getRawData.number)
        }

        println("TIME " + (System.currentTimeMillis() - start))
      }

      awaitSync(actorSystem.terminate())

      ok
    }
  }
  */
}
