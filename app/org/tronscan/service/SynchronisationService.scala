package org.tronscan.service

import akka.NotUsed
import akka.stream.scaladsl.Flow
import io.circe.syntax._
import javax.inject.Inject
import org.joda.time.DateTime
import org.tron.api.api.{EmptyMessage, NumberMessage}
import org.tron.protos.Tron.Account
import org.tronscan.Extensions._
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.WalletClient
import org.tronscan.importer.ImportStatus
import org.tronscan.models.{AccountModel, AccountModelRepository, AddressBalanceModelRepository, BlockModelRepository}
import org.tronscan.utils.StreamUtils
import play.api.Logger
import play.api.inject.ConfigurationProvider

import scala.async.Async._


class SynchronisationService @Inject() (
  walletClient: WalletClient,
  blockModelRepository: BlockModelRepository,
  accountModelRepository: AccountModelRepository,
  addressBalanceModelRepository: AddressBalanceModelRepository,
  configurationProvider: ConfigurationProvider) {

  import scala.concurrent.ExecutionContext.Implicits.global

  val syncSolidity = configurationProvider.get.get[Boolean]("sync.solidity")

  /**
    * Reset all the blockchain data in the database
    */
  def resetDatabase() = {
    blockModelRepository.clearAll
  }

  /**
    * Checks if the chain is the same for the given block
    */
  def isSameChain(blockNumber: Long = 0) = {
    for {
      wallet <- walletClient.full
      dbBlock <- blockModelRepository.findByNumber(blockNumber)
      genesisBlock <- wallet.getBlockByNum(NumberMessage(blockNumber))
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

  def getFullNodeHashByNum(number: Long) = {
    for {
      wallet <- walletClient.full
      hash <- wallet.getBlockByNum(NumberMessage(number)).map(_.hash)
    } yield hash
  }

  def getSolidityHashByNum(number: Long) = {
    for {
      wallet <- walletClient.solidity
      hash <- wallet.getBlockByNum(NumberMessage(number)).map(_.hash)
    } yield hash
  }

  def getDBHashByNum(number: Long) = {
    blockModelRepository.findByNumber(number).map {
      case Some(block) =>
        block.hash
      case _ =>
        ""
    }
  }

  /**
    * Retrieves the import status for full and solidity nodes
    */
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

      lastFullNodeBlockHash <- lastFulNodeNumberF.map(_.hash).recover { case _ => "" }
      lastSolidityNodeBlockHash <- lastSolidityNumberF.map(_.hash).recover { case _ => "" }
      lastDbBlockHash <- lastDatabaseBlockF.map(_.get.hash).recover { case _ => "" }
    } yield ImportStatus(
      solidityEnabled = syncSolidity,
      fullNodeBlock = lastFulNodeNumber,
      solidityBlock = lastSolidityNumber,
      dbUnconfirmedBlock = lastUnconfirmedDatabaseBlock.map(_.number).getOrElse(-1),
      dbLatestBlock = lastDatabaseBlock.map(_.number).getOrElse(-1),
      fullNodeBlockHash = lastFullNodeBlockHash,
      solidityBlockHash = lastSolidityNodeBlockHash,
      dbBlockHash = lastDbBlockHash
    )
  }

  /**
    * Builds a stream that accepts addresses and syncs them to the database
    */
  def buildAddressSynchronizer(parallel: Int = 8): Flow[Address, Address, NotUsed] = Flow[Address]
    .via(StreamUtils.distinct)
    .mapAsyncUnordered(parallel) { address =>
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
            dateCreated = new DateTime(account.createTime),
            dateUpdated = DateTime.now
          )

          await(accountModelRepository.insertOrUpdate(accountModel))
          await(addressBalanceModelRepository.updateBalance(accountModel))
        }
        address
      }
    }
}
