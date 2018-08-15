package org.tronscan.importer

import akka.actor.Actor
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import akka.{Done, NotUsed}
import javax.inject.Inject
import monix.execution.Scheduler.Implicits.global
import org.tron.protos.Tron.Transaction.Contract.ContractType.{TransferAssetContract, TransferContract, WitnessCreateContract}
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.{FullNodeBlockChain, WalletClient}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import org.tronscan.service.{ImportStatus, SynchronisationService}
import org.tronscan.utils.ModelUtils
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.async.Async.{async, await}
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}


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
    Supervision.Resume
  }

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider))(context)

  /**
    * Build import action from import status
    */
  def buildImportActionFromImportStatus(importStatus: ImportStatus) = {
    var autoConfirmBlocks = false
    var updateAccounts = false
    var redisCleaner = true
    var asyncAddressImport = false
    var publishEvents = true

    val fullNodeBlockHash = Await.result(syncService.getFullNodeHashByNum(importStatus.solidityBlock), 2.second)
    val resetDB = !Await.result(syncService.isSameChain(), 2.second)

    if (!syncSolidity) {
      autoConfirmBlocks = true
      updateAccounts = true
    }

    if ((importStatus.dbLatestBlock <= importStatus.solidityBlock - 1000) && (fullNodeBlockHash == importStatus.solidityBlockHash)) {
      autoConfirmBlocks = true
      updateAccounts = true
    }

    if (importStatus.dbLatestBlock < (importStatus.fullNodeBlock - 1000)) {
      publishEvents = false
    }

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

  def buildSource(importState: ImportStatus) = {

    Logger.info("buildSource: " + importState.toString)

    val importAction = buildImportActionFromImportStatus(importState)

    println("importAction-confirmBlocks = " + importAction.confirmBlocks)

    if (importAction.resetDB) {
      Await.result(syncService.resetDatabase(), 2.second)
    }

    def redisCleaner = if (importAction.cleanRedisCache) Flow[Address].alsoTo(redisCacheCleaner) else Flow[Address]

    def accountUpdaterFlow: Flow[Address, Any, NotUsed] = {
      if (importAction.updateAccounts) {
        if (importAction.asyncAddressImport) {
//          Flow[Address].alsoTo(Sink.actorRef(addressSync, PoisonPill))
//          Flow[Address].alsoTo(Sink.)
          syncService.buildAddressSynchronizer()
        } else {
          syncService.buildAddressSynchronizer()
        }
      } else {
        Flow[Address]
      }
    }

    def eventsPublisher = {
      if (importAction.publishEvents) {
        blockChainBuilder.publishContractEvents(List(
          TransferContract,
          TransferAssetContract,
          WitnessCreateContract
        ))
      } else {
        Flow[Transaction.Contract]
      }
    }

    RunnableGraph.fromGraph(GraphDSL.create(Sink.ignore) { implicit b => sink =>
        import GraphDSL.Implicits._
        val blocks = b.add(Broadcast[Block](3))
        val transactions = b.add(Broadcast[(Block, Transaction)](1))
        val contracts = b.add(Broadcast[(Block, Transaction, Transaction.Contract)](2))
        val addresses = b.add(Merge[Address](2))
        val out = b.add(Merge[Any](3))

        // Periodically start sync
        val importStatus = Source
          .single(importState)
          .via(syncStarter)

        /***** Channels *****/

        // Blocks
        importStatus.via(readBlocksFromStatus) ~> blocks.in

        // Pass block witness addresses to address stream
        blocks.map(_.witness) ~> addresses

        // Transactions
        blocks.mapConcat(b => b.transactions.map(t => (b, t)).toList) ~> transactions.in

        // Contracts
        transactions.mapConcat { case (block, t) => t.getRawData.contract.map(c => (block, t, c)).toList } ~> contracts.in

        // Read addresses from contracts
        contracts.mapConcat(_._3.addresses) ~> addresses

        /** Importers **/

        // Synchronize Blocks
        blocks ~> fullNodeBlockImporter(importAction.confirmBlocks) ~> out

        // Sync addresses
        addresses ~> redisCleaner ~> accountUpdaterFlow ~> out

        // Broadcast contract events
        contracts.map(_._3) ~> eventsPublisher ~> out

        /** Close Stream **/

        // Route everything to sink
        out ~> sink.in

        ClosedShape
    })
  }

  /**
    * Invalidate addresses in the redis cache
    */
  def redisCacheCleaner: Sink[Any, Future[Done]] = Sink
    .foreach { address =>
      redisCache.removeMatching(s"address/$address/*")
    }

  /**
    * Build a stream of blocks from the given import status
    */
  def readBlocksFromStatus = Flow[ImportStatus]
    .mapAsync(1) { status =>
      walletClient.full.map { walletFull =>
        val fullNodeBlockChain = new FullNodeBlockChain(walletFull)
        // Switch between batch or single depending how far the sync is behind
        val action = buildImportActionFromImportStatus(status)
        val blockEnd = if (action.confirmBlocks) status.solidityBlock else status.fullNodeBlock
        if (status.fullNodeBlocksToSync < 100)  blockChainBuilder.readFullNodeBlocks(status.dbLatestBlock + 1, blockEnd)(fullNodeBlockChain.client)
        else                                    blockChainBuilder.readFullNodeBlocksBatched(status.dbLatestBlock + 1, blockEnd, 100)(fullNodeBlockChain.client)
      }
    }
    .flatMapConcat { blockStream => blockStream }

  /**
    * Retrieves the latest synchronisation status and checks if the sync should proceed
    */
  def syncStarter = Flow[ImportStatus]
    .filter {
      // Stop if there are more then 100 blocks to sync for full node
      case status if status.fullNodeBlocksToSync > 0 =>
        Logger.info(s"START SYNC FROM ${status.dbLatestBlock} TO ${status.fullNodeBlock}. " + status.toString)
        true
      case status =>
        Logger.info("IGNORE FULL NODE SYNC: " + status.toString)
        false
    }


  def buildContractSqlBuilder = {
    import databaseImporter._
    importWitnessCreate orElse importTransfers orElse buildConfirmedEvents orElse elseEmpty
  }

  /**
    * Build block importer
    *
    * @param confirmBlocks if all blocks that are being imported should be automatically confirmed
    */
  def fullNodeBlockImporter(confirmBlocks: Boolean = false) = {
    val importer = buildContractSqlBuilder

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

  def startSync() = {

    Logger.info("START FULL NODE SYNC")

    Source.tick(0.seconds, 2.seconds, "")
      .mapAsync(1)(_ => syncService.importStatus.flatMap(buildSource(_).run()))
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
