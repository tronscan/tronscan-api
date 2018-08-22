package org
package tronscan.importer

import akka.actor.Actor
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import javax.inject.Inject
import monix.execution.Scheduler.Implicits.global
import org.tronscan.grpc.WalletClient
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import org.tronscan.service.SynchronisationService
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider

import scala.concurrent.duration._
import scala.util.{Failure, Success}

class FullNodeImporter @Inject()(
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  witnessModelRepository: WitnessModelRepository,
  walletClient: WalletClient,
  syncService: SynchronisationService,
  databaseImporter: DatabaseImporter,
  blockChainBuilder: BlockChainStreamBuilder,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  configurationProvider: ConfigurationProvider) extends Actor {

  val config = configurationProvider.get
  val syncFull = configurationProvider.get.get[Boolean]("sync.full")
  val syncSolidity = configurationProvider.get.get[Boolean]("sync.solidity")

  val decider: Supervision.Decider = { exc =>
    Logger.error("FULL NODE ERROR", exc)
    Supervision.Restart
  }

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider))(context)

  def startSync() = {

    Logger.info("START FULL NODE SYNC")

    Source.tick(0.seconds, 2.seconds, "")
//      .mapAsync(1)(_ => syncService.importStatus.flatMap(buildSource(_).run()))
      .runWith(Sink.ignore)
      .andThen {
        case Success(_) =>
          Logger.info("BLOCKCHAIN SYNC SUCCESS")
        case Failure(exc) =>
          Logger.error("BLOCKCHAIN SYNC FAILURE", exc)
      }
  }

  def receive = {
    case Sync() =>
      startSync()
  }
}
