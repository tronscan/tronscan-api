package org.tronscan.importer

import akka.actor.{Actor, ActorRef}
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import javax.inject.{Inject, Named}
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.lang3.exception.ExceptionUtils
import org.joda.time.DateTime
import org.tron.protos.Tron.Transaction.Contract.ContractType.{TransferAssetContract, TransferContract, VoteWitnessContract, WitnessCreateContract}
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.domain.Events.{AssetTransferCreated, TransferCreated, VoteCreated, WitnessCreated}
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.{FullNodeBlockChain, WalletClient}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import org.tronscan.protocol.ContractUtils
import org.tronscan.service.{ImportStatus, SynchronisationService}
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class FullNodeReader @Inject()(
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  voteWitnessContractModelRepository: VoteWitnessContractModelRepository,
  witnessModelRepository: WitnessModelRepository,
  walletClient: WalletClient,
  syncService: SynchronisationService,
  databaseImporter: DatabaseImporter,
  @Named("node-watchdog") nodeWatchDog: ActorRef,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  configurationProvider: ConfigurationProvider) extends Actor {

  val config = configurationProvider.get
  val syncFull = configurationProvider.get.get[Boolean]("sync.full")
  val syncSolidity = configurationProvider.get.get[Boolean]("sync.solidity")


  def buildSource = {
    RunnableGraph.fromGraph(GraphDSL.create(Sink.ignore) { implicit b => sink =>
      import GraphDSL.Implicits._
      val blocks = b.add(Broadcast[Block](3))
      val transactions = b.add(Broadcast[(Block, Transaction)](1))
      val contracts = b.add(Broadcast[(Block, Transaction, Transaction.Contract)](1))
      val addresses = b.add(Merge[Address](2))
      val out = b.add(Merge[Any](2))


      // Periodically start sync
      val importStatus = Source.single("")
        .via(syncGate)

      /***** Broadcast channels *****/

      // Blocks
      importStatus.via(readBlocksFromStatus) ~> blocks.in

      // Pass block witness addresses to address stream
      blocks.map(_.witness) ~> addresses

      // Transactions
      blocks.mapConcat(b => b.transactions.map(t => (b, t)).toList) ~> transactions.in

      // Contracts
      transactions.mapConcat { case (block, t) => t.getRawData.contract.map(c => (block, t, c)).toList } ~> contracts.in

      // Addresses
      contracts.mapConcat(_._3.addresses) ~> addresses

      /** Importers **/

      // Synchronize Blocks
      blocks ~> fullNodeBlockImporter.async ~> out

      if (!syncSolidity) {
        addresses ~> syncService.buildAddressSynchronizer.async ~> out
      } else {
        addresses ~> out
      }

      out ~> sink.in

      ClosedShape
    })
  }

  def readBlocksFromStatus = Flow[ImportStatus]
    .mapAsync(1) { status =>
      walletClient.full.map { walletFull =>
        val fullNodeBlockChain = new FullNodeBlockChain(walletFull)
        val streamBuilder = new FullNodeStreamBuilder(fullNodeBlockChain)

        println("SYNCING FROM", status.dbLatestBlock, status.fullNodeBlock)

        if (status.fullNodeBlocksToSync < 100)  streamBuilder.readBlocks(status.dbLatestBlock, status.fullNodeBlock)
        else                                    streamBuilder.readBlocksBatched(status.dbLatestBlock, status.fullNodeBlock, 100)
      }
    }
    .flatMapConcat { blockStream => blockStream }

  def syncGate = Flow[Any]
    .mapAsync(1)(_ => syncService.importStatus)
    .filter {
      // Stop if there are more then 100 blocks to sync for full node
      case status if status.fullNodeBlocksToSync > 0 =>
        Logger.info("START SYNC!")
        true
      case status =>
        Logger.info("IGNORE FULL NODE SYNC: " + status.toString)
        false
    }

  def fullNodeBlockImporter = Flow[Block]
    .map { block =>

      val header = block.getBlockHeader.getRawData

      val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

      println("FULL NODE BLOCK", header.number)

      queries.append(databaseImporter.buildImportBlock(BlockModel.fromProto(block)))

      queries.appendAll(for {
        transaction <- block.transactions
      } yield {

        val transactionHash = transaction.hash
        val transactionTime = new DateTime(header.timestamp)

        val transactionModel = TransactionModel(
          hash = transactionHash,
          block = header.number,
          ownerAddress = ContractUtils.getOwner(transaction.getRawData.contract.head),
          timestamp = transactionTime,
          contractData = TransactionSerializer.serializeContract(transaction.getRawData.contract.head),
          contractType = transaction.getRawData.contract.head.`type`.value,
        )

        transactionModelRepository.buildInsert(transactionModel)
      })

      for {
        transaction <- block.transactions
        contract <- transaction.getRawData.contract
      } {
        val any = contract.getParameter

        val transactionHash = transaction.hash
        val transactionTime = new DateTime(header.timestamp)

        contract.`type` match {
          case TransferContract =>
            val transferContract = org.tron.protos.Contract.TransferContract.parseFrom(any.value.toByteArray)

            val trxModel = TransferModel(
              transactionHash = transactionHash,
              block = header.number,
              timestamp = transactionTime,
              transferFromAddress = transferContract.ownerAddress.encodeAddress,
              transferToAddress = transferContract.toAddress.encodeAddress,
              amount = transferContract.amount,
              confirmed = header.number == 0,
            )

            redisCache.removeMatching(s"address/${transferContract.toAddress.encodeAddress}/*")
            redisCache.removeMatching(s"address/${transferContract.ownerAddress.encodeAddress}/*")

            context.system.eventStream.publish(TransferCreated(trxModel))

            queries.append(transferRepository.buildInsert(trxModel))

          case TransferAssetContract =>
            val transferContract = org.tron.protos.Contract.TransferAssetContract.parseFrom(any.value.toByteArray)

            val trxModel = TransferModel(
              transactionHash = transactionHash,
              block = header.number,
              timestamp = transactionTime,
              transferFromAddress = transferContract.ownerAddress.encodeAddress,
              transferToAddress = transferContract.toAddress.encodeAddress,
              amount = transferContract.amount,
              tokenName = new String(transferContract.assetName.toByteArray),
              confirmed = header.number == 0,
            )

            redisCache.removeMatching(s"address/${transferContract.toAddress.encodeAddress}/*")
            redisCache.removeMatching(s"address/${transferContract.ownerAddress.encodeAddress}/*")

            context.system.eventStream.publish(AssetTransferCreated(trxModel))

            queries.append(transferRepository.buildInsert(trxModel))

          case VoteWitnessContract if !syncSolidity =>
            val voteWitnessContract = org.tron.protos.Contract.VoteWitnessContract.parseFrom(any.value.toByteArray)
            val voterAddress = voteWitnessContract.ownerAddress.encodeAddress

            val inserts = for (vote <- voteWitnessContract.votes) yield {
              VoteWitnessContractModel(
                transaction = transactionHash,
                block = header.number,
                timestamp = transactionTime,
                voterAddress = voterAddress,
                candidateAddress = vote.voteAddress.encodeAddress,
                votes = vote.voteCount,
              )
            }

            inserts.foreach { vote =>
              context.system.eventStream.publish(VoteCreated(vote))
            }

            queries.appendAll(voteWitnessContractModelRepository.buildUpdateVotes(voterAddress, inserts))

          //            case ParticipateAssetIssueContract =>
          //              val participateAssetIssueContract = org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(any.value.toByteArray)
          //
          //              val participateAsset = ParticipateAssetIssueModel(
          //                ownerAddress = Base58.encode58Check(participateAssetIssueContract.ownerAddress.toByteArray),
          //                toAddress = Base58.encode58Check(participateAssetIssueContract.toAddress.toByteArray),
          //                amount = participateAssetIssueContract.amount,
          //                block = header.number,
          //                token = new String(participateAssetIssueContract.assetName.toByteArray),
          //                dateCreated = transactionTime
          //              )
          //
          //              context.system.eventStream.publish(ParticipateAssetIssueModelCreated(participateAsset))
          //
          //              queries.append(participateAssetIssueRepository.buildInsert(participateAsset))
          //
          //              case AssetIssueContract =>
          //                val assetIssueContract = org.tron.protos.Contract.AssetIssueContract.parseFrom(any.value.toByteArray)
          //
          //                val assetIssue = AssetIssueContractModel(
          //                  block = header.number,
          //                  transaction = transactionHash,
          //                  ownerAddress = Base58.encode58Check(assetIssueContract.ownerAddress.toByteArray),
          //                  name = new String(assetIssueContract.name.toByteArray).trim,
          //                  totalSupply = assetIssueContract.totalSupply,
          //                  trxNum = assetIssueContract.trxNum,
          //                  num = assetIssueContract.num,
          //                  startTime = new DateTime(assetIssueContract.startTime),
          //                  endTime = new DateTime(assetIssueContract.endTime),
          //                  voteScore = assetIssueContract.voteScore,
          //                  description = new String(assetIssueContract.description.toByteArray),
          //                  url = new String(assetIssueContract.url.toByteArray),
          //                  dateCreated = transactionTime,
          //                )
          //
          //                context.system.eventStream.publish(AssetIssueCreated(assetIssue))
          //
          //                assetIssueContractModelRepository.insertAsync(assetIssue)

          case WitnessCreateContract =>
            val witnessCreateContract = org.tron.protos.Contract.WitnessCreateContract.parseFrom(any.value.toByteArray)

            val witnessModel = WitnessModel(
              address = witnessCreateContract.ownerAddress.encodeAddress,
              url = new String(witnessCreateContract.url.toByteArray),
            )

            context.system.eventStream.publish(WitnessCreated(witnessModel))

            queries.append(witnessModelRepository.buildInsertOrUpdate(witnessModel))

          //              case WitnessUpdateContract =>
          //                val witnessUpdateContract = org.tron.protos.Contract.WitnessUpdateContract.parseFrom(any.value.toByteArray)
          //
          //                val witnessModel = WitnessModel(
          //                  address = Base58.encode58Check(witnessUpdateContract.ownerAddress.toByteArray),
          //                  url = new String(witnessUpdateContract.updateUrl.toByteArray),
          //                )
          //
          //                witnessModelRepository.update(witnessModel)

          case _ =>
          //                println("other contract")

        }
      }

      queries.toList
    }
    .flatMapConcat(queries => Source(queries))
    .groupedWithin(500, 3.seconds)
    .mapAsync(1) { queries =>
      blockModelRepository.executeQueries(queries)
    }

  def startSync() = {

    Logger.info("START FULL NODE SYNC")

    val decider: Supervision.Decider = {  exc =>
        println("FULL NODE ERROR", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    buildSource.run()
  }

  def receive = {
    case Sync() =>
      startSync()
  }
}
