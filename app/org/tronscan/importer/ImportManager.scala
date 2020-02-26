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
import akka.pattern.after

import scala.concurrent.Future

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
  voteRoundImporter: VoteRoundImporter,
  accountImporter: AccountImporter) extends Actor {

  val config = configurationProvider.get


  def startImporters(): Unit = {

    import scala.concurrent.ExecutionContext.Implicits.global

    val decider: Supervision.Decider = { exc =>
      Logger.error("SYNC NODE ERROR", exc)
      Supervision.Restart
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)
    implicit val system = context.system


    def startImporter(name: String)(source: => Source[_, _]) = {
      source
        .runWith(Sink.ignore)
        .andThen {
          case Success(_) =>
            Logger.info("$name SYNC SUCCESS")
          case Failure(exc) =>
            Logger.error(s"$name SYNC FAILURE", exc)
        }
    }

    // Wait a few seconds for the GRPC Balancer to get ready
    after(12.seconds, context.system.scheduler) {

      Future {
        val syncSolidity = config.get[Boolean]("sync.solidity")
        val syncFull = config.get[Boolean]("sync.full")
        val syncAddresses = config.get[Boolean]("sync.addresses")
        val syncVoteRounds = config.get[Boolean]("sync.votes")

        if (syncVoteRounds) {
          startImporter("ROUNDS") {
            Source.tick(3.seconds, 30.minutes, "")
              .mapAsync(1)(_ => voteRoundImporter.importRounds().run())
          }
        }

        if (syncFull) {
          startImporter("FULL") {
            Source.tick(0.seconds, 3.seconds, "")
              .mapAsync(1)(_ => fullNodeImporter.buildStream.flatMap(_.run()))
          }
        }

        if (syncSolidity) {
          startImporter("SOLIDITY") {
            Source.tick(0.seconds, 3.seconds, "")
              .mapAsync(1)(_ => solidityNodeImporter.buildStream.flatMap(_.run()))
          }
        }

        if (syncAddresses) {
          implicit val scheduler = context.system.scheduler
          startImporter("ADDRESSES") {
            Source.tick(0.seconds, 15.seconds, "")
              .mapAsync(1) { _ =>
                accountImporter
                  .buildAccountSyncSource
                  .runWith(accountImporter.buildAddressSynchronizerFlow(walletClient))
              }
          }
        }
      }
    }
  }

  override def preStart(): Unit = {
    startImporters()
  }

  def receive = {
    case _ =>
  }
}
