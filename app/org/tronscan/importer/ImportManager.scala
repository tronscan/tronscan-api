package org.tronscan.importer

import akka.actor.Actor
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import javax.inject.Inject
import org.tronscan.grpc.WalletClient
import play.api.Logger
import play.api.inject.ConfigurationProvider

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ImportManager {
  case class Sync()
  case class SyncAccounts()
}

/**
  * Handles the Full and Solidity Import
  */
class ImportManager @Inject() (
  configurationProvider: ConfigurationProvider,
  fullNodeImporter: FullNodeImporter,
  solidityNodeImporter: SolidityNodeImporter,
  walletClient: WalletClient,
  accountImporter: AccountImporter) extends Actor {

  val config = configurationProvider.get

  def startImporters() = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val decider: Supervision.Decider = { exc =>
      Logger.error("SYNC NODE ERROR", exc)
      Supervision.Restart
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)
    implicit val system = context.system

    val syncSolidity = config.get[Boolean]("sync.solidity")
    val syncFull = config.get[Boolean]("sync.full")
    val syncAddresses = config.get[Boolean]("sync.addresses")

    if (syncFull) {
      Source.tick(0.seconds, 3.seconds, "")
        .mapAsync(1)(_ => fullNodeImporter.buildStream.flatMap(_.run()))
        .runWith(Sink.ignore)
        .andThen {
          case Success(_) =>
            Logger.info("BLOCKCHAIN SYNC SUCCESS")
          case Failure(exc) =>
            Logger.error("BLOCKCHAIN SYNC FAILURE", exc)
        }
    }

    if (syncSolidity) {
      Source.tick(0.seconds, 3.seconds, "")
        .mapAsync(1)(_ => solidityNodeImporter.buildStream.flatMap(_.run()))
        .runWith(Sink.ignore)
        .andThen {
          case Success(_) =>
            Logger.info("SOLIDITY SYNC SUCCESS")
          case Failure(exc) =>
            Logger.error("SOLIDITY SYNC FAILURE", exc)
        }
    }

    if (syncAddresses) {
      implicit val scheduler = context.system.scheduler
      Source.tick(0.seconds, 15.seconds, "")
        .mapAsync(1) { _ =>
          accountImporter
            .buildAccountSyncSource
            .runWith(accountImporter.buildAddressSynchronizerFlow(walletClient))
        }
        .runWith(Sink.ignore)
        .andThen {
          case Success(_) =>
            Logger.info("ADDRESS SYNC SUCCESS")
          case Failure(exc) =>
            Logger.error("ADDRESS SYNC FAILURE", exc)
        }
    }
  }


  def receive = {
    case _ =>
  }
}
