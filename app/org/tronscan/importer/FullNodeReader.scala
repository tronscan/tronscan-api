package org.tronscan.importer

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, KillSwitches, Supervision}
import akka.util
import javax.inject.{Inject, Named}
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.lang3.exception.ExceptionUtils
import org.joda.time.DateTime
import org.tron.common.utils.{Base58, ByteArray}
import org.tron.protos.Tron.Transaction.Contract.ContractType.{TransferAssetContract, TransferContract, VoteWitnessContract, WitnessCreateContract}
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.domain.Events.{AssetTransferCreated, TransferCreated, VoteCreated, WitnessCreated}
import org.tronscan.grpc.{FullNodeBlockChain, GrpcClients, WalletClient}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import org.tronscan.network.NetworkScanner
import org.tronscan.network.NetworkScanner.GetBestNodes
import org.tronscan.protocol.TransactionUtils
import org.tronscan.service.SynchronisationService
import play.api.Logger._
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.async.Async._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class FullNodeReader @Inject()(
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  voteWitnessContractModelRepository: VoteWitnessContractModelRepository,
  witnessModelRepository: WitnessModelRepository,
  walletClient: WalletClient,
  syncService: SynchronisationService,
  @Named("node-watchdog") nodeWatchDog: ActorRef,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  configurationProvider: ConfigurationProvider) extends Actor {

  val config = configurationProvider.get
  val syncFull = configurationProvider.get.get[Boolean]("sync.full")
  val syncSolidity = configurationProvider.get.get[Boolean]("sync.solidity")

  def syncChain() = async {

    println("START BLOCKCHAIN SYNC")

    val decider: Supervision.Decider = {
      case exc =>
        println("FULL NODE ERROR", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    val wallet = await(walletClient.full)
    val fullNodeBlockChain = new FullNodeBlockChain(wallet)
    val streamBuilder = new FullNodeStreamBuilder(fullNodeBlockChain)

    // Check if we are still on the same chain, if not then reset the database
    if (await(syncService.hasData)) {
      if (!await(syncService.isSameChain(fullNodeBlockChain))) {
        warn("CHAIN CHANGED, RESETTING")
        await(syncService.resetDatabase())
      }
    }

    val lastSynchronizedBlockNumber: Long = await(syncService.currentSynchronizedBlock).map(_.number + 1).getOrElse(0)
    val latestBlock = await(fullNodeBlockChain.headBlock)
    val latestBlockNumber = latestBlock.getBlockHeader.getRawData.number
    val blockDifference = Math.abs(latestBlockNumber - lastSynchronizedBlockNumber)

    info(s"BLOCKCHAIN SYNC FROM $lastSynchronizedBlockNumber to $latestBlockNumber")

    val blocks =
      if (blockDifference < 100)  streamBuilder.readBlocks(lastSynchronizedBlockNumber, latestBlockNumber)
      else                        streamBuilder.readBlocksBatched(lastSynchronizedBlockNumber, latestBlockNumber)

    val (killSwitch, syncTask) = blocks
      .viaMat(KillSwitches.single)(Keep.right)
      .map { block =>

        val header = block.getBlockHeader.getRawData

        val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

        println("FULL NODE BLOCK", header.number)

        queries.append(blockModelRepository.buildInsert(BlockModel(
          number = header.number,
          size = block.toByteArray.length,
          hash = block.hash,
          timestamp = new DateTime(header.timestamp),
          txTrieRoot = Base58.encode58Check(header.txTrieRoot.toByteArray),
          parentHash = ByteArray.toHexString(block.parentHash),
          witnessId = header.witnessId,
          witnessAddress = Base58.encode58Check(header.witnessAddress.toByteArray),
          nrOfTrx = block.transactions.size,
          confirmed = header.number == 0,
        )))

        queries.appendAll(for {
          transaction <- block.transactions
        } yield {

          val transactionHash = transaction.hash
          val transactionTime = new DateTime(header.timestamp)

          val transactionModel = TransactionModel(
            hash = transactionHash,
            block = header.number,
            ownerAddress = TransactionUtils.getOwner(transaction.getRawData.contract.head),
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
              val owner = Base58.encode58Check(witnessCreateContract.ownerAddress.toByteArray)

              val witnessModel = WitnessModel(
                address = owner,
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
      .toMat(Sink.ignore)(Keep.both)
      .run

    await(syncTask)
  }.andThen {
    case Success(x) =>

    case Failure(exc) =>
      println("BLOCKCHAIN SYNC FAILURE", ExceptionUtils.getMessage(exc), ExceptionUtils.getStackTrace(exc))
  }

  def getClients = {
    implicit val timeout = util.Timeout(10.seconds)
    (nodeWatchDog ? GetBestNodes(10, n => n.nodeType == NetworkScanner.full && n.permanent)).mapTo[GrpcClients]
  }

  def waiting: Receive = {
    case x =>
  }

  def receive = {
    case Sync() =>
      context.become(waiting)
      syncChain().onComplete { _ =>
        context.become(receive)
      }(context.dispatcher)
  }
}
