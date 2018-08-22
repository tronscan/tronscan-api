package org.tronscan.importer

import akka.{Done, NotUsed}
import akka.actor.Scheduler
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import javax.inject.Inject
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{AccountModel, AccountModelRepository}
import org.tronscan.service.AccountService
import org.tronscan.utils.FutureUtils
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Handles the importing of accounts
  */
class AccountImporter @Inject() (
  accountImporter: AccountModelRepository,
  accountService: AccountService) {

  /**
    * Builds a source which provides accounts that need synchronisation
    */
  def buildAccountSyncSource(implicit executionContext: ExecutionContext): Source[Address, NotUsed] = {
    Source.unfoldAsync(()) { _ =>
      accountImporter.findAddressesWhichNeedSync().map {
        case addresses if addresses.nonEmpty =>
          Some(((), addresses.map(_.address)))
        case _ =>
          None
      }
    }
    .mapConcat(x => x.toList)
  }

  /**
    * Builds a stream that accepts addresses and syncs them to the database
    * @param parallel how many threads should be used
    */
  def buildAddressSynchronizerFlow(walletClient: WalletClient, parallel: Int = 8)(implicit scheduler: Scheduler, executionContext: ExecutionContext): Flow[Address, Address, NotUsed] = {
    Flow[Address]
      .mapAsyncUnordered(parallel) { address =>
        Logger.info("Syncing Address: " + address)

        // Retry if it fails
        FutureUtils.retry(250.milliseconds, 30.seconds, 0.5) { () =>
          accountService.syncAddress(address, walletClient).map { _ =>
            address
          }
        }
      }
  }

  /**
    * Builds a stream that marks the incoming addresses as dirty in the database
    */
  def buildAddressMarkDirtyFlow(implicit executionContext: ExecutionContext): Sink[Address, Future[Done]] = {
    Flow[Address]
      .map { address =>
//        Logger.info(s"$address marked dirty")
//        accountService.markAddressDirty(address).map { _ =>
        //          address
        //        }
        accountImporter.buildDirty(address)
      }
      // Batch queries together
      .groupedWithin(1000, 3.seconds)
      // Insert batched queries in database
      .mapAsync(1)(accountImporter.executeQueries)
      .toMat(Sink.ignore)(Keep.right)
  }
}
