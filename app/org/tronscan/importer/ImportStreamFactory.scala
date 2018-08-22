package org.tronscan.importer

import akka.stream.SinkShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink, Source}
import akka.{Done, NotUsed}
import javax.inject.Inject
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.{FullNodeBlockChain, WalletClient}
import org.tronscan.importer.StreamTypes._
import org.tronscan.models._
import org.tronscan.service.SynchronisationService
import org.tronscan.utils.{ModelUtils, StreamUtils}
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.async.Async._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Holds the flows that will handle the specicif objects extracted from blocks
  *
  * @param blocks flows whichs handle the blocks
  * @param addresses flows which handle the addresses
  * @param contracts flows which handle the contracts
  */
case class BlockchainImporters(
  blocks: List[Flow[Block, Block, NotUsed]] = List.empty,
  addresses: List[Flow[Address, Address, NotUsed]] = List.empty,
  contracts: List[Flow[ContractFlow, ContractFlow, NotUsed]] = List.empty,
) {
  def addBlock(block: Flow[Block, Block, NotUsed]) = copy(blocks = blocks :+ block)
  def addAddress(address: Flow[Address, Address, NotUsed]) = copy(addresses = addresses :+ address)
  def addContract(contract: Flow[ContractFlow, ContractFlow, NotUsed]) = copy(contracts = contracts :+ contract)
}

case class ImportAction(
  /**
   * If all blocks should be confirmed
   */
  confirmBlocks: Boolean = false,

  /**
   * If the accounts should be imported from GRPC and updated into the database
   */
  updateAccounts: Boolean = false,

  /**
   * If redis should be resetted
   */
  cleanRedisCache: Boolean = false,

  /**
   * If address importing should be done asynchronously
   */
  asyncAddressImport: Boolean = false,

  /**
   * If events should be published to the websockets
   */
  publishEvents: Boolean = false,

  /**
   * If db should be reset
   */
  resetDB: Boolean = false,
)

class ImportStreamFactory @Inject()(
  syncService: SynchronisationService,
  blockChainBuilder: BlockChainStreamBuilder,
  @NamedCache("redis") redisCache: CacheAsyncApi) {

  /**
    * Build import action from import status
    */
  def buildImportActionFromImportStatus(importStatus: NodeState)(implicit executionContext: ExecutionContext) = async {
    var autoConfirmBlocks = false
    var updateAccounts = false
    var redisCleaner = true
    var asyncAddressImport = false
    var publishEvents = true

    val fullNodeBlockHash = await(syncService.getFullNodeHashByNum(importStatus.solidityBlock))
    val resetDB = !await(syncService.isSameChain())

    // If solidity isn't being synced then take over Solidity node tasks
    if (!importStatus.solidityEnabled) {
      autoConfirmBlocks = true
      updateAccounts = true
    }

    // If the solidity and full node hash are the same then confirm everything
    if ((importStatus.dbLatestBlock <= importStatus.solidityBlock - 1000) && (fullNodeBlockHash == importStatus.solidityBlockHash)) {
      autoConfirmBlocks = true
      updateAccounts = true
    }

    // Don't publish events when there is lots to sync
    if (importStatus.dbLatestBlock < (importStatus.fullNodeBlock - 1000)) {
      publishEvents = false
    }

    // No need to clean cache when starting a clean sync
    if (importStatus.dbLatestBlock == 0) {
      redisCleaner = false
    }

    ImportAction(
      confirmBlocks       = autoConfirmBlocks,
      updateAccounts      = updateAccounts,
      cleanRedisCache     = redisCleaner,
      asyncAddressImport  = asyncAddressImport,
      publishEvents       = publishEvents,
      resetDB             = resetDB
    )
  }

  /**
    * A sync that extracts all the blocks, transactions, contracts, addresses from the blocks and passes them to streams
    */
  def buildBlockSink(importers: BlockchainImporters): Sink[Block, Future[Done]] = {
    Sink.fromGraph(GraphDSL.create(Sink.ignore) { implicit b => sink =>
      import GraphDSL.Implicits._
      val blocks = b.add(Broadcast[Block](3))
      val transactions = b.add(Broadcast[(Block, Transaction)](1))
      val contracts = b.add(Broadcast[ContractFlow](2))
      val addresses = b.add(Merge[Address](2))
      val out = b.add(Merge[Any](3))

      /***** Channels *****/

      // Pass block witness addresses to address stream
      blocks.map(_.witness).filter(_.length == 34) ~> addresses

      // Transactions
      blocks.mapConcat(b => b.transactions.map(t => (b, t)).toList) ~> transactions.in

      // Contracts
      transactions.mapConcat { case (block, t) => t.getRawData.contract.map(c => (block, t, c)).toList } ~> contracts.in

      // Read addresses from contracts
      contracts.mapConcat(_._3.addresses) ~> addresses

      /** Importers **/

      // Extract blocks
      blocks ~> importers.blocks.pipe.async ~> out

      // Extract addresses
      addresses ~> StreamUtils.distinct[Address] ~> importers.addresses.pipe.async ~> out

      // Extract contracts
      contracts ~> importers.contracts.pipe.async ~> out

      /** Close Stream **/

      // Route everything to sink
      out ~> sink.in

      SinkShape(blocks.in)
    })
  }

  /**
    * Build a stream of blocks from the given import status
    */
  def buildBlockSource(walletClient: WalletClient)(implicit context: ExecutionContext) = Flow[NodeState]
    .mapAsync(1) { status =>
      walletClient.full.map { walletFull =>
        val fullNodeBlockChain = new FullNodeBlockChain(walletFull)

        // Switch between batch or single depending how far the sync is behind
        if (status.fullNodeBlocksToSync < 100)  blockChainBuilder.readFullNodeBlocks(status.dbLatestBlock + 1, status.fullNodeBlock)(fullNodeBlockChain.client)
        else                                    blockChainBuilder.readFullNodeBlocksBatched(status.dbLatestBlock + 1, status.fullNodeBlock, 100)(fullNodeBlockChain.client)
      }
    }
    .flatMapConcat(blockStream => blockStream)

  /**
    * Retrieves the latest synchronisation status and checks if the sync should proceed
    */
  def preSynchronisationChecker = Flow[NodeState]
    .filter {
      // Stop if there are more then 100 blocks to sync for full node
      case status if status.fullNodeBlocksToSync > 0 =>
        Logger.info(s"START SYNC FROM ${status.dbLatestBlock} TO ${status.fullNodeBlock}. " + status.toString)
        true
      case status =>
        Logger.info("IGNORE FULL NODE SYNC: " + status.toString)
        false
    }

  def buildContractSqlBuilder(databaseImporter: DatabaseImporter) = {
    import databaseImporter._
    importWitnessCreate orElse importTransfers orElse buildConfirmedEvents orElse elseEmpty
  }

  /**
    * Build block importer
    *
    * @param confirmBlocks if all blocks that are being imported should be automatically confirmed
    */
  def fullNodeBlockImporter(
    blockModelRepository: BlockModelRepository,
    transactionModelRepository: TransactionModelRepository,
    databaseImporter: DatabaseImporter,
    confirmBlocks: Boolean = false) = {
    val importer = buildContractSqlBuilder(databaseImporter)

    Flow[Block]
      .map { block =>

        val header = block.getBlockHeader.getRawData
        val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

        Logger.info(s"FULL NODE BLOCK: ${header.number}, TX: ${block.transactions.size}, CONFIRM: $confirmBlocks")

        // Import Block
        queries.append(blockModelRepository.buildInsert(BlockModel.fromProto(block).copy(confirmed = confirmBlocks)))

        // Import Transactions
        queries.appendAll(block.transactions.map { trx =>
          transactionModelRepository.buildInsertOrUpdate(ModelUtils.transactionToModel(trx, block).copy(confirmed = confirmBlocks))
        })

        // Import Contracts
        queries.appendAll(block.transactionContracts.flatMap {
          case (trx, contract) =>
            ModelUtils.contractToModel(contract, trx, block).map {
              case transfer: TransferModel =>
                importer((contract.`type`, contract, transfer.copy(confirmed = confirmBlocks || block.getBlockHeader.getRawData.number == 0)))
              case x =>
                importer((contract.`type`, contract, x))
            }.getOrElse(Seq.empty)
        })

        queries.toList
      }
      // Flatmap the queries
      .flatMapConcat(q => Source(q))
      // Batch queries together
      .groupedWithin(1000, 2.seconds)
      // Insert batched queries in database
      .mapAsync(1)(blockModelRepository.executeQueries)
  }

}
