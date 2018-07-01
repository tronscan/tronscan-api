package org.tronscan.importer

import akka.actor.{Actor, ActorRef}
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import io.circe.syntax._
import javax.inject.{Inject, Named}
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.lang3.exception.ExceptionUtils
import org.joda.time.DateTime
import org.tron.common.utils.Base58
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AssetIssueContract, ParticipateAssetIssueContract, TransferAssetContract, TransferContract, UnfreezeBalanceContract, VoteWitnessContract, WitnessCreateContract, WitnessUpdateContract}
import org.tron.protos.Tron.{Account, Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.domain.Events.{AssetIssueCreated, ParticipateAssetIssueModelCreated, VoteCreated}
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.{FullNodeBlockChain, SolidityBlockChain, WalletClient}
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

import scala.async.Async.{await, _}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._


class SolidityNodeReader @Inject()(
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  voteWitnessContractModelRepository: VoteWitnessContractModelRepository,
  participateAssetIssueRepository: ParticipateAssetIssueModelRepository,
  accountModelRepository: AccountModelRepository,
  witnessModelRepository: WitnessModelRepository,
  addressBalanceModelRepository: AddressBalanceModelRepository,
  assetIssueContractModelRepository: AssetIssueContractModelRepository,
  configurationProvider: ConfigurationProvider,
  databaseImporter: DatabaseImporter,
  synchronisationService: SynchronisationService,
  walletClient: WalletClient,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  @Named("node-watchdog") nodeWatchDog: ActorRef) extends Actor {

  def readBlocksFromStatus = Flow[ImportStatus]
    .mapAsync(1) { status =>
      walletClient.solidity.map { walletSolidity =>
        val solidityStreamBuilder = new SolidityNodeStreamBuilder(new SolidityBlockChain(walletSolidity))
        solidityStreamBuilder.readBlocks(status.dbLatestBlock, status.soliditySyncToBlock)
      }
    }
    .flatMapConcat { blockStream => blockStream }

  def syncGate = Flow[Any]
    .mapAsync(1)(_ => synchronisationService.importStatus)
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

  def buildSource = {
    RunnableGraph.fromGraph(GraphDSL.create(Sink.ignore) { implicit b => sink =>
      import GraphDSL.Implicits._
      import b._
      val blocks = add(Broadcast[Block](3))
      val transactions = add(Broadcast[(Block, Transaction)](2))
      val contracts = add(Broadcast[(Block, Transaction, Transaction.Contract)](2))
      val addresses = add(Merge[Address](2))

      // Periodically start sync
      val importStatus = Source.tick(10.seconds, 3.seconds, "")
        .via(syncGate)

      /***** Broadcast channels *****/

      // Blocks
      importStatus.via(readBlocksFromStatus) ~> blocks.in
      blocks.map(_.getBlockHeader.getRawData.witnessAddress.encodeAddress) ~> addresses

      // Transactions
      blocks.mapConcat(b => b.transactions.map(t => (b, t)).toList) ~> transactions.in

      // Contracts
      transactions.mapConcat { case (block, t) =>
        (for {
          contract <- t.getRawData.contract
        } yield (block, t, contract)).toList
      } ~> contracts.in

      // Addresses
      contracts.mapConcat { case (block, t, c) => ContractUtils.getAddresses(c).toList } ~> addresses

      /** Importers **/

      // Synchronize Blocks
      blocks ~> buildSolidityBlockQueryImporter ~> sink.in

      // Synchronize Addresses
      addresses ~> synchronisationService.buildAddressSynchronizer

      ClosedShape
    })
  }


  def buildBlockSource = async {
    // Check if we should allow full to sync first

    val syncStatus = await(synchronisationService.importStatus)
    val walletFull = await(walletClient.full)
    val fullBlockChain = new FullNodeBlockChain(walletFull)
    val fullNodeBlockId = await(fullBlockChain.headBlock).getBlockHeader.getRawData.number

    val latestDbBlockNumber: Long = await(synchronisationService.currentSynchronizedBlock).map(_.number).getOrElse(0)
    if (100 < syncStatus.fullNodeBlocksToSync) {
      throw new Exception("WAIT FOR FULL NODE TO CATCH UP")
    }

    val walletSolidity = await(walletClient.solidity)
    val solidityBlockChain = new SolidityBlockChain(walletSolidity)
    val latestBlock = await(solidityBlockChain.headBlock)
    val blockNum = latestBlock.getBlockHeader.getRawData.number

    val syncToBlock = if (blockNum > latestDbBlockNumber) latestDbBlockNumber else blockNum

    val latestUnconfirmedBlock: Long = await(blockModelRepository.findLatestUnconfirmed).map(_.number).getOrElse(0)
    println(s"SOLIDITY SYNCING FROM $latestUnconfirmedBlock to $syncToBlock")
    //    val solidityBlockDifference = Math.abs(latestDbBlockNumber - latestUnconfirmedBlock)

    val solidityStreamBuilder = new SolidityNodeStreamBuilder(solidityBlockChain)

    if ((syncToBlock - latestUnconfirmedBlock) > 0) {

    }

    solidityStreamBuilder.readBlocks(latestUnconfirmedBlock, syncToBlock)
  }


  def buildSolidityBlockQueryImporter = Flow[Block]
    .mapAsync(12) { solidityBlock =>
      for {
        databaseBlock <- blockModelRepository.findByNumber(solidityBlock.getBlockHeader.getRawData.number)
      } yield (solidityBlock, databaseBlock.get)
    }
    // Filter empty or confirmed blocks
    .filter(x => x._1.blockHeader.isDefined && !x._2.confirmed)
    .map {
      case (solidityBlock, databaseBlock) =>

        val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

        val header = solidityBlock.getBlockHeader.getRawData
        val blockHash = solidityBlock.hash

        // Block needs to be replaced if the full node block hash is different from the solidity block hash
        val replace = blockHash != databaseBlock.hash

        if (!replace) {
          println("CONFIRMING BLOCK", databaseBlock.number)
          // Update Block
          queries.appendAll(blockModelRepository.buildConfirmBlock(databaseBlock.number))
        } else {
          // replace block
          println("REPLACING BLOCK", header.number)
          queries.appendAll(databaseImporter.buildConfirmBlock(BlockModel.fromProto(solidityBlock)))
        }

        queries.appendAll(for {
          transaction <- solidityBlock.transactions
        } yield {

          val transactionHash = transaction.hash
          val transactionTime = new DateTime(header.timestamp)

          val transactionModel = TransactionModel(
            hash = transactionHash,
            block = header.number,
            timestamp = transactionTime,
            ownerAddress = ContractUtils.getOwner(transaction.getRawData.contract.head),
            contractData = TransactionSerializer.serializeContract(transaction.getRawData.contract.head),
            contractType = transaction.getRawData.contract.head.`type`.value,
            confirmed = true,
          )

          transactionModelRepository.buildInsertOrUpdate(transactionModel)
        })

        for {
          transaction <- solidityBlock.transactions
          contract <- transaction.getRawData.contract
        } {
          val any = contract.getParameter

          val transactionHash = transaction.hash
          val transactionTime = new DateTime(header.timestamp)

          contract.`type` match {
            case TransferContract =>
              val transferContract = org.tron.protos.Contract.TransferContract.parseFrom(any.value.toByteArray)
              val from = transferContract.ownerAddress.encodeAddress
              val to = transferContract.toAddress.encodeAddress

              if (replace) {
                val trxModel = TransferModel(
                  transactionHash = transactionHash,
                  block = header.number,
                  timestamp = transactionTime,
                  transferFromAddress = from,
                  transferToAddress = to,
                  amount = transferContract.amount,
                  confirmed = true,
                )

                queries.append(transferRepository.buildInsert(trxModel))
              }

            case TransferAssetContract if replace =>
              val transferContract = org.tron.protos.Contract.TransferAssetContract.parseFrom(any.value.toByteArray)
              val from = transferContract.ownerAddress.encodeAddress
              val to = transferContract.toAddress.encodeAddress

              if (replace) {

                val trxModel = TransferModel(
                  transactionHash = transactionHash,
                  block = header.number,
                  timestamp = transactionTime,
                  transferFromAddress = from,
                  transferToAddress = to,
                  amount = transferContract.amount,
                  tokenName = new String(transferContract.assetName.toByteArray),
                  confirmed = true,
                )

                queries.append(transferRepository.buildInsert(trxModel))
              }

            case VoteWitnessContract =>
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

            case AssetIssueContract =>
              val assetIssueContract = org.tron.protos.Contract.AssetIssueContract.parseFrom(any.value.toByteArray)
              val owner = assetIssueContract.ownerAddress.encodeAddress

              val assetIssue = AssetIssueContractModel(
                block = header.number,
                transaction = transactionHash,
                ownerAddress = owner,
                name = new String(assetIssueContract.name.toByteArray).trim,
                abbr = new String(assetIssueContract.abbr.toByteArray).trim,
                totalSupply = assetIssueContract.totalSupply,
                trxNum = assetIssueContract.trxNum,
                num = assetIssueContract.num,
                startTime = new DateTime(assetIssueContract.startTime),
                endTime = new DateTime(assetIssueContract.endTime),
                voteScore = assetIssueContract.voteScore,
                description = new String(assetIssueContract.description.toByteArray),
                url = new String(assetIssueContract.url.toByteArray),
                dateCreated = transactionTime,
              ).withFrozen(assetIssueContract.frozenSupply)

              context.system.eventStream.publish(AssetIssueCreated(assetIssue))

              queries.append(assetIssueContractModelRepository.buildInsert(assetIssue))

            case ParticipateAssetIssueContract =>
              val participateAssetIssueContract = org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(any.value.toByteArray)

              val ownerAddress = Base58.encode58Check(participateAssetIssueContract.ownerAddress.toByteArray)
              val toAddress = Base58.encode58Check(participateAssetIssueContract.toAddress.toByteArray)

              val participateAsset = ParticipateAssetIssueModel(
                ownerAddress = ownerAddress,
                toAddress = toAddress,
                amount = participateAssetIssueContract.amount,
                block = header.number,
                token = new String(participateAssetIssueContract.assetName.toByteArray),
                dateCreated = transactionTime
              )

              context.system.eventStream.publish(ParticipateAssetIssueModelCreated(participateAsset))

              queries.append(participateAssetIssueRepository.buildInsert(participateAsset))

            case WitnessCreateContract =>
              val witnessCreateContract = org.tron.protos.Contract.WitnessCreateContract.parseFrom(any.value.toByteArray)
              val owner = witnessCreateContract.ownerAddress.encodeAddress

              val witnessModel = WitnessModel(
                address = owner,
                url = new String(witnessCreateContract.url.toByteArray))

              queries.append(witnessModelRepository.buildInsertOrUpdate(witnessModel))

            case WitnessUpdateContract =>
              val witnessUpdateContract = org.tron.protos.Contract.WitnessUpdateContract.parseFrom(any.value.toByteArray)

              val witnessModel = WitnessModel(
                address = witnessUpdateContract.ownerAddress.encodeAddress,
                url = new String(witnessUpdateContract.updateUrl.toByteArray),
              )

              queries.append(witnessModelRepository.buildUpdate(witnessModel))

            case UnfreezeBalanceContract =>
              val unfreezeBalanceContract = org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(any.value.toByteArray)
              queries.append(voteWitnessContractModelRepository.buildDeleteVotesForAddress(unfreezeBalanceContract.ownerAddress.encodeAddress))

            case _ =>
          }
        }

        queries.toList
    }
    .flatMapConcat(queries => Source(queries))
    .groupedWithin(500, 10.seconds)
    .mapAsync(1) { queries =>
      blockModelRepository.executeQueries(queries)
    }

  def startSync() = {

    val decider: Supervision.Decider = {  exc =>
      println("SOLIDITY NODE ERROR", exc, ExceptionUtils.getStackTrace(exc))
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
