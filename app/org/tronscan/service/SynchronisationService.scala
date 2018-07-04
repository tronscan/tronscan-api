package org.tronscan.service

import akka.stream.scaladsl.{Flow, Source}
import io.circe.syntax._
import javax.inject.Inject
import org.joda.time.DateTime
import org.tron.api.api.EmptyMessage
import org.tron.protos.Tron.Account
import org.tronscan.Extensions._
import org.tronscan.domain.BlockChain
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{AccountModel, AccountModelRepository, AddressBalanceModelRepository, BlockModelRepository}
import org.tronscan.utils.StreamUtils
import play.api.Logger

import scala.async.Async.{async, await}
import scala.concurrent.duration._

case class ImportStatus(
  fullNodeBlock: Long,
  solidityBlock: Long,
  dbUnconfirmedBlock: Long,
  dbLatestBlock: Long) {

  /**
    * Full Node Synchronisation Progress
    */
  val fullNodeProgress: Double = (dbLatestBlock.toDouble / fullNodeBlock.toDouble) * 100

  /**
    * Solidity Synchronisation Progress
    */
  val solidityBlockProgress: Double = (dbUnconfirmedBlock.toDouble / solidityBlock.toDouble) * 100

  /**
    * How many blocks to sync from the full node
    */
  val fullNodeBlocksToSync = fullNodeBlock - dbLatestBlock

  /**
    * To which block the solidity block will be synced
    */
  val soliditySyncToBlock = if (solidityBlock > dbLatestBlock) dbLatestBlock else solidityBlock

  /**
    * To which block the solidity block will be synced
    */
  val solidityBlocksToSync = dbLatestBlock - dbUnconfirmedBlock

  /**
    * Total Progress
    */
  val totalProgress = (fullNodeProgress + solidityBlockProgress) / 2
}

class SynchronisationService @Inject() (
  walletClient: WalletClient,
  blockModelRepository: BlockModelRepository,
  accountModelRepository: AccountModelRepository,
  addressBalanceModelRepository: AddressBalanceModelRepository) {

  import scala.concurrent.ExecutionContext.Implicits.global

  /**
    * Reset all the blockchain data in the database
    */
  def resetDatabase() = {
    blockModelRepository.clearAll
  }

  /**
    * Checks if the given chain is the same as the database chain
    */
  def isSameChain(blockChain: BlockChain) = {
    for {
      dbBlock <- blockModelRepository.findByNumber(0)
      genesisBlock <- blockChain.genesisBlock
    } yield dbBlock.exists(_.hash == genesisBlock.hash)
  }

  /**
    * If the database has any blocks
    */
  def hasData = {
    blockModelRepository.findByNumber(0).map(_.isDefined)
  }

  /**
    * Last synchronized block in the database
    */
  def currentSynchronizedBlock = {
    blockModelRepository.findLatest
  }

  /**
    * Last confirmed block in the database
    */
  def currentConfirmedBlock = {
    blockModelRepository.findLatestUnconfirmed
  }

  def importStatus = {
    for {
      wallet <- walletClient.full
      walletSolidity <- walletClient.solidity

      lastFulNodeNumberF = wallet.getNowBlock(EmptyMessage())
      lastSolidityNumberF = walletSolidity.getNowBlock(EmptyMessage())
      lastDatabaseBlockF = blockModelRepository.findLatest
      lastUnconfirmedDatabaseBlockF = blockModelRepository.findLatestUnconfirmed

      lastFulNodeNumber <- lastFulNodeNumberF.map(_.getBlockHeader.getRawData.number).recover { case _ => -1L }
      lastSolidityNumber <- lastSolidityNumberF.map(_.getBlockHeader.getRawData.number).recover { case _ => -1L }
      lastDatabaseBlock <- lastDatabaseBlockF
      lastUnconfirmedDatabaseBlock <- lastUnconfirmedDatabaseBlockF
    } yield ImportStatus(
      fullNodeBlock = lastFulNodeNumber,
      solidityBlock = lastSolidityNumber,
      dbUnconfirmedBlock = lastUnconfirmedDatabaseBlock.map(_.number).getOrElse(0),
      dbLatestBlock = lastDatabaseBlock.map(_.number).getOrElse(0),
    )
  }


  def buildAddressSynchronizer = Flow[Address]
    .via(StreamUtils.distinct)
//    .groupedWithin(100, 15.seconds)
//    .map { addresses => addresses.distinct }
//    .flatMapConcat(x => Source(x.toList))
    .mapAsyncUnordered(8) { address =>
      Logger.info("Syncing Address: " + address)
      async {

        val walletSolidity = await(walletClient.solidity)

        val account = await(walletSolidity.getAccount(Account(
          address = address.decodeAddress
        )))

        if (account != null) {

          val accountModel = AccountModel(
            address = address,
            name = new String(account.accountName.toByteArray),
            balance = account.balance,
            power = account.frozen.map(_.frozenBalance).sum,
            tokenBalances = account.asset.asJson,
            dateUpdated = DateTime.now,
          )

          List(accountModelRepository.buildInsertOrUpdate(accountModel)) ++
            addressBalanceModelRepository.buildUpdateBalance(accountModel)
        } else {
          List.empty
        }
      }
    }
    .flatMapConcat(queries => Source(queries))
    .groupedWithin(150, 10.seconds)
    .mapAsync(1) { queries =>
      blockModelRepository.executeQueries(queries)
    }
}
