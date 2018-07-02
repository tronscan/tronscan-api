package org.tronscan.importer

import akka.actor.Actor
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
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
import org.tronscan.utils.{ModelUtils, ProtoUtils}
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.collection.mutable.ListBuffer
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
  var isActive = false

  def buildSource = {
    RunnableGraph.fromGraph(GraphDSL.create(Sink.ignore) { implicit b =>
      sink =>
        import GraphDSL.Implicits._
        val blocks = b.add(Broadcast[Block](3))
        val transactions = b.add(Broadcast[(Block, Transaction)](1))
        val contracts = b.add(Broadcast[(Block, Transaction, Transaction.Contract)](2))
        val addresses = b.add(Merge[Address](2))
        val out = b.add(Merge[Any](2))

        // Periodically start sync
        val importStatus = Source.tick(0.seconds, 3.seconds, "")
          .via(syncStarter)
          .map { x =>
            isActive = true
            x
          }

        /** *** Channels *****/

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
        blocks ~> fullNodeBlockImporter.async ~> out

        // Sync addresses
        addresses ~> redisCacheCleaner ~> accountUpdaterFlow ~> out

        // Broadcast contract events
        contracts.map(_._3) ~> blockChainBuilder.publishContractEvents(List(
          TransferContract,
          TransferAssetContract,
          WitnessCreateContract
        )) ~> Sink.ignore

        /** Close Stream **/

        // Route everything to sink
        out ~> sink.in

        ClosedShape
    })
  }

  /**
    * Reads addresses and imports the account
    * @return
    */
  def accountUpdaterFlow = {
    if (!syncSolidity) {
      syncService.buildAddressSynchronizer.async
    } else {
      Flow[Address]
    }
  }

  /**
    * Invalidate addresses in the redis cache
    */
  def redisCacheCleaner = Flow[Address]
    .map { address =>
      redisCache.removeMatching(s"address/$address/*")
      address
    }
    .async

  /**
    * Build a stream of blocks from the given import status
    */
  def readBlocksFromStatus = Flow[ImportStatus]
    .mapAsync(1) { status =>
      walletClient.full.map { walletFull =>
        val fullNodeBlockChain = new FullNodeBlockChain(walletFull)

        // Switch between batch or single depending how far the sync is behind
        if (status.fullNodeBlocksToSync < 100)  blockChainBuilder.readFullNodeBlocks(status.dbLatestBlock + 1, status.fullNodeBlock)(fullNodeBlockChain.client)
        else                                    blockChainBuilder.readFullNodeBlocksBatched(status.dbLatestBlock + 1, status.fullNodeBlock, 100)(fullNodeBlockChain.client)
      }
    }
    .flatMapConcat { blockStream => blockStream }

  /**
    * Retrieves the latest synchronisation status and checks if the sync should proceed
    */
  def syncStarter = Flow[Any]
    .mapAsync(1)(_ => syncService.importStatus)
    .filter {
      case _ if isActive =>
        false
      // Stop if there are more then 100 blocks to sync for full node
      case status if status.fullNodeBlocksToSync > 0 =>
        Logger.info("START SYNC " + status.toString)
        true
      case status =>
        Logger.info("IGNORE FULL NODE SYNC: " + status.toString)
        false
    }


  def buildContractSqlBuilder = {
    import databaseImporter._

    var q = importWitnessCreate orElse
      importTransfers

    if (!syncSolidity) {
      q = q orElse buildConfirmedEvents
    }

    q orElse elseEmpty
  }

  def fullNodeBlockImporter = {
    val importer = buildContractSqlBuilder

    Flow[Block]
      .map { block =>

        val header = block.getBlockHeader.getRawData
        val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

        Logger.info("FULL NODE BLOCK: " + header.number)

        // Import Block
        queries.append(blockModelRepository.buildInsert(BlockModel.fromProto(block)))

        // Import Transactions
        queries.appendAll(block.transactions.map { trx =>
          transactionModelRepository.buildInsertOrUpdate(ModelUtils.transactionToModel(trx, block))
        })

        // Import Contracts
        queries.appendAll(block.transactionContracts.flatMap {
          case (trx, contract) =>
            ModelUtils.contractToModel(contract, trx, block).map {
              case transfer: TransferModel =>
                importer((contract.`type`, contract, transfer.copy(confirmed = block.getBlockHeader.getRawData.number == 0)))
              case x =>
                importer((contract.`type`, contract, x))
            }.getOrElse(Seq.empty)
        })

        queries.toList
      }
      // Flatmap the queries
      .flatMapConcat(q => Source(q))
      // Batch queries together
      .groupedWithin(500, 2.seconds)
      // Insert batched queries in database
      .mapAsync(1)(blockModelRepository.executeQueries)
  }

  def startSync() = {

    Logger.info("START FULL NODE SYNC")

    val decider: Supervision.Decider = {  exc =>
        Logger.error("FULL NODE ERROR", exc)
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    buildSource.run().andThen {
      case Success(_) =>
        isActive = false
        Logger.info("BLOCKCHAIN SYNC SUCCESS")
      case Failure(exc) =>
        isActive = false
        Logger.error("BLOCKCHAIN SYNC FAILURE", exc)
    }
  }

  def receive = {
    case Sync() =>
      startSync()
  }
}
