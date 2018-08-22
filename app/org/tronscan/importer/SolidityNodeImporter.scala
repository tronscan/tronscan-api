package org.tronscan.importer

import akka.actor.Actor
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import javax.inject.Inject
import monix.execution.Scheduler.Implicits.global
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AssetIssueContract, ParticipateAssetIssueContract, VoteWitnessContract}
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.{SolidityBlockChain, WalletClient}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import org.tronscan.service.SynchronisationService
import org.tronscan.utils.{ModelUtils, ProtoUtils}
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Success}


class SolidityNodeImporter @Inject()(
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  voteWitnessContractModelRepository: VoteWitnessContractModelRepository,
  participateAssetIssueRepository: ParticipateAssetIssueModelRepository,
  witnessModelRepository: WitnessModelRepository,
  assetIssueContractModelRepository: AssetIssueContractModelRepository,
  synchronisationService: SynchronisationService,
  databaseImporter: DatabaseImporter,
  blockChainBuilder: BlockChainStreamBuilder,
  walletClient: WalletClient,
  @NamedCache("redis") redisCache: CacheAsyncApi) extends Actor {

  def readBlocksFromStatus = Flow[NodeState]
    .mapAsync(1) { status =>
      walletClient.solidity.map { walletSolidity =>
        val client = new SolidityBlockChain(walletSolidity).client
        blockChainBuilder.readSolidityBlocks(status.dbUnconfirmedBlock, status.soliditySyncToBlock)(client)
      }
    }
    .flatMapConcat { blockStream => blockStream }

  /**
    * Retrieves the latest synchronisation status and checks if the sync should proceed
    */
  def syncStarter = {
    Flow[Any]
      .mapAsync(1)(_ => synchronisationService.nodeState)
      .filter {
        // Stop if there are more then 100 blocks to sync for full node
        case status if status.fullNodeBlocksToSync > 100 =>
          false
        // Accept if there are blocks to sync
        case status if status.solidityBlocksToSync > 0 =>
          true
        case _ =>
          false
      }
  }

  def buildSource = {
    RunnableGraph.fromGraph(GraphDSL.create(Sink.ignore) { implicit b => sink =>
      import GraphDSL.Implicits._
      import b._
      val blocks = add(Broadcast[Block](3))
      val transactions = add(Broadcast[(Block, Transaction)](1))
      val contracts = add(Broadcast[(Block, Transaction, Transaction.Contract)](2))
      val addresses = add(Merge[Address](2))

      // Periodically start sync
      val importStatus = Source.single("")
        .via(syncStarter)

      /***** Broadcast channels *****/

      // Blocks
      importStatus.via(readBlocksFromStatus) ~> blocks.in

      // Send witnesses to address stream
      blocks.map(_.witness) ~> addresses

      // Transactions
      blocks.mapConcat(b => b.transactions.map(t => (b, t)).toList) ~> transactions.in

      // Contracts
      transactions.mapConcat { case (block, t) => t.getRawData.contract.map(c => (block, t, c)).toList } ~> contracts.in

      // Read addresses from contracts
      contracts.mapConcat(_._3.addresses) ~> addresses

      /** Importers **/

      // Synchronize Blocks
      blocks ~> buildSolidityBlockQueryImporter ~> sink.in

      // Synchronize Addresses
//      addresses ~> synchronisationService.buildAddressSynchronizer() ~> Sink.ignore

      // Publish Contract Events
//      contracts.map(_._3) ~>
//        blockChainBuilder.publishContractEvents(List(
//          VoteWitnessContract,
//          AssetIssueContract,
//          ParticipateAssetIssueContract
//        )) ~> Sink.ignore

      ClosedShape
    })
  }

  def buildContractSqlBuilder = {
    import databaseImporter._
    buildConfirmedEvents orElse elseEmpty
  }

  def buildSolidityBlockQueryImporter = {
    val importer = buildContractSqlBuilder

    Flow[Block]
      .mapAsync(12) { solidityBlock =>
        for {
          databaseBlock <- blockModelRepository.findByNumber(solidityBlock.getBlockHeader.getRawData.number)
        } yield (solidityBlock, databaseBlock.get)
      }
      // Filter empty or confirmed blocks
      .filter(x => x._1.blockHeader.isDefined && !x._2.confirmed)
      .map {
        case (solidityBlock, databaseBlock) =>

          val queries = ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]]()

          // Block needs to be replaced if the full node block hash is different from the solidity block hash
          val replaceBlock = solidityBlock.hash != databaseBlock.hash

          if (replaceBlock) {
            val number = solidityBlock.getBlockHeader.getRawData.number
            Logger.info("REPLACE BLOCK: " + number)
            // replace block
            queries.appendAll(blockModelRepository.buildReplaceBlock(BlockModel.fromProto(solidityBlock)))
            queries.append(transactionModelRepository.deleteByNum(number))
            queries.append(transferRepository.deleteByNum(number))
            queries.append(assetIssueContractModelRepository.deleteByNum(number))
            queries.append(participateAssetIssueRepository.deleteByNum(number))
          } else {
            Logger.info("CONFIRM BLOCK: " + solidityBlock.getBlockHeader.getRawData.number)
            // Update Block
            queries.appendAll(blockModelRepository.buildConfirmBlock(databaseBlock.number))
          }

          // Import / Overwrite transactions
          queries.appendAll(for {
            transaction <- solidityBlock.transactions
            transactionModel = ModelUtils.transactionToModel(transaction, solidityBlock)
          } yield {
            transactionModelRepository.buildInsertOrUpdate(transactionModel.copy(confirmed = true))
          })

          queries.appendAll(solidityBlock.transactionContracts.flatMap {
            case (trx, contract) =>
              ModelUtils.contractToModel(contract, trx, solidityBlock).map {
                case _: TransferModel if !replaceBlock =>
                  // Don't import transfers if they don't need to be replaced
                  Seq.empty
                case transfer: TransferModel =>
                  // Import transfer as confirmed
                  importer((contract.`type`, contract, transfer.copy(confirmed = true)))
                case x if replaceBlock => importer((contract.`type`, contract, x))
                case x => Seq.empty
              }.getOrElse(Seq.empty)
          })

          queries.toList
      }
      .flatMapConcat(queries => Source(queries))
      .groupedWithin(500, 10.seconds)
      .mapAsync(1) { queries =>
        blockModelRepository.executeQueries(queries)
      }
  }

  def startSync() = {

    val decider: Supervision.Decider = {  exc =>
      Logger.error("SOLIDITY NODE ERROR", exc)
      Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    Source.tick(0.seconds, 2.seconds, "")
      .mapAsync(1) { _ =>
        Logger.info("START SOLIDITY")
        buildSource.run()
      }
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
