package org.tronscan.api

import akka.actor.ActorRef
import javax.inject.{Inject, Named}
import org.tron.api.api.{BlockLimit, EmptyMessage, WalletGrpc}
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import play.api.cache.Cached
import play.api.inject.ConfigurationProvider
import play.api.libs.json.Json
import play.api.mvc.InjectedController
import org.tronscan.grpc.{GrpcBalancerRequest, GrpcBalancerStats, WalletClient}
import org.tronscan.models.BlockModelRepository
import org.tronscan.service.SynchronisationService

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import io.grpc.ManagedChannelBuilder

class SystemApi @Inject()(
  walletClient: WalletClient,
  cached: Cached,
  blockModelRepository: BlockModelRepository,
  syncService: SynchronisationService,
  configurationProvider: ConfigurationProvider,
  @Named("grpc-balancer") grpcBalancer: ActorRef ) extends InjectedController {

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val timeout = Timeout(10.seconds)

  def balancer = Action.async {
    (grpcBalancer ? GrpcBalancerRequest()).mapTo[GrpcBalancerStats].map { stats =>
      Ok(
        Json.obj(
          "active" -> stats.activeNodes.map { node =>
            Json.obj(
              "responseTime" -> node.responseTime,
              "requestsSuccess" -> node.requestHandled,
              "requestsErrors" -> node.requestErrors,
            )
          },
          "backup" -> stats.nodes.map { node =>
            Json.obj(
              "responseTime" -> node.responseTime,
              "requestsSuccess" -> node.requestHandled,
              "requestsErrors" -> node.requestErrors,
            )
          }
        )
      )
    }
  }

  def status = cached.status(x => "system.sync", 200, 15.seconds) {
    Action.async { req =>

      val networkType = configurationProvider.get.get[String]("net.type")

      for {
        syncStatus <- syncService.nodeState
      } yield {
        Ok(
          Json.obj(
            "network" -> Json.obj(
              "type" -> networkType,
            ),
            "sync" -> Json.obj(
              "progress" -> syncStatus.totalProgress,
            ),
            "database" -> Json.obj(
              "block" -> syncStatus.dbLatestBlock,
              "unconfirmedBlock" -> syncStatus.dbUnconfirmedBlock,
            ),
            "full" -> Json.obj(
              "block" -> syncStatus.fullNodeBlock,
            ),
            "solidity" -> Json.obj(
              "block" -> syncStatus.solidityBlock,
            ),
          )
        )
      }
    }
  }
//
//  def test = Action.async {
//    import scala.concurrent.ExecutionContext.Implicits.global
//
//    val channel = ManagedChannelBuilder
//      .forAddress("47.254.146.147", 50051)
//      .usePlainText()
//      .build
//
//    val wallet = WalletGrpc.stub(channel)
//
//    val blocks = for (i <- 1 to 500 by 50) yield i
//
//    val done = Future.sequence(blocks.sliding(2).toList.map { case Seq(now, next) =>
//      wallet.getBlockByLimitNext(BlockLimit(now, next)).map { result =>
//        val resultBlocks = result.block.sortBy(_.getBlockHeader.getRawData.number).map(_.getBlockHeader.getRawData.number)
//        if (resultBlocks.head != now) {
//          println(s"INVALID HEAD ${resultBlocks.head} => $now")
//        }
//        if (resultBlocks.last != next) {
//          println(s"INVALID HEAD ${resultBlocks.last} => $next")
//        }
//        resultBlocks
//      }
//    })
//
//    done.map { _ =>
//      Ok("done")
//    }
//  }
}
