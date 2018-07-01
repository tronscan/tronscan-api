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

import scala.concurrent.Future
import scala.concurrent.duration._

class SystemApi @Inject()(
  walletClient: WalletClient,
  cached: Cached,
  blockModelRepository: BlockModelRepository,
  configurationProvider: ConfigurationProvider) extends InjectedController {

  import scala.concurrent.ExecutionContext.Implicits.global

  def status = cached.status(x => "system.sync", 200, 15.seconds) {
    Action.async { req =>

      val networkType = configurationProvider.get.get[String]("net.type")

      for {
        wallet <- walletClient.full
        walletSolidity <- walletClient.solidity

        lastFulNodeNumberF = wallet.getNowBlock(EmptyMessage())
        lastSolidityNumberF = walletSolidity.getNowBlock(EmptyMessage())
        lastDatabaseBlockF = blockModelRepository.findLatest
        lastUnconfirmedDatabaseBlockF = blockModelRepository.findLatestUnconfirmed

        lastFulNodeNumber <- lastFulNodeNumberF
        lastSolidityNumber <- lastSolidityNumberF
        lastDatabaseBlock <- lastDatabaseBlockF
        lastUnconfirmedDatabaseBlock <- lastUnconfirmedDatabaseBlockF
      } yield {

        val dbBlock = lastDatabaseBlock.map(_.number).getOrElse(0L)
        val dbUnconfirmedBlock = lastUnconfirmedDatabaseBlock.map(_.number).getOrElse(0L).toLong
        val fullNodeBlock = lastFulNodeNumber.getBlockHeader.getRawData.number
        val solidityBlock = lastSolidityNumber.getBlockHeader.getRawData.number

        val blockProgress = (dbBlock.toDouble / fullNodeBlock.toDouble) * 100
        val solidityBlockProgress = (dbUnconfirmedBlock.toDouble / solidityBlock.toDouble) * 100
        val totalProgress = (blockProgress + solidityBlockProgress) / 2

        Ok(
          Json.obj(
            "network" -> Json.obj(
              "type" -> networkType,
            ),
            "sync" -> Json.obj(
              "progress" -> totalProgress,
            ),
            "database" -> Json.obj(
              "block" -> dbBlock.toLong,
              "unconfirmedBlock" -> dbUnconfirmedBlock,
            ),
            "full" -> Json.obj(
              "block" -> lastFulNodeNumber.getBlockHeader.getRawData.number,
            ),
            "solidity" -> Json.obj(
              "block" -> solidityBlock,
            ),
          )
        )
      }
    }
  }
}
