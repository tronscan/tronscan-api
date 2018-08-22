package org.tronscan.api

import javax.inject.Inject
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import play.api.cache.Cached
import play.api.inject.ConfigurationProvider
import play.api.libs.json.Json
import play.api.mvc.InjectedController
import org.tronscan.grpc.WalletClient
import org.tronscan.models.BlockModelRepository
import org.tronscan.service.SynchronisationService

import scala.concurrent.Future
import scala.concurrent.duration._

class SystemApi @Inject()(
  walletClient: WalletClient,
  cached: Cached,
  blockModelRepository: BlockModelRepository,
  syncService: SynchronisationService,
  configurationProvider: ConfigurationProvider) extends InjectedController {

  import scala.concurrent.ExecutionContext.Implicits.global

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
