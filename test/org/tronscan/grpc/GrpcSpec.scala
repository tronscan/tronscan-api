package org
package tronscan.grpc

import io.grpc.ManagedChannelBuilder
import org.specs2.mutable._
import org.tron.api.api.{BlockLimit, WalletGrpc}

import scala.concurrent.Future

class GrpcSpec extends Specification {

  "GRPC" should {

    "Read BlockLimit Next" in {

      import scala.concurrent.ExecutionContext.Implicits.global

      val channel = ManagedChannelBuilder
        .forAddress("47.254.146.147", 50051)
        .usePlaintext()
        .build

      val wallet = WalletGrpc.stub(channel)

      val blocks = for (i <- 1 to 500 by 50) yield i

      val done = Future.sequence(blocks.sliding(2).toList.map { case Seq(now, next) =>
        wallet.getBlockByLimitNext(BlockLimit(now, next)).map { result =>
          val resultBlocks = result.block.sortBy(_.getBlockHeader.getRawData.number).map(_.getBlockHeader.getRawData.number)
          if (resultBlocks.head != now) {
            println(s"INVALID HEAD ${resultBlocks.head} => $now")
          }
          if (resultBlocks.last != next) {
            println(s"INVALID HEAD ${resultBlocks.last} => $next")
          }
          resultBlocks
        }
      })

      awaitSync(done)
      ok
    }
  }
}
