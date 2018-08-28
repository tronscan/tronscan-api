package org.tronscan.api

import akka.actor.ActorRef
import javax.inject.{Inject, Named}
import org.tron.api.api.EmptyMessage
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
}
