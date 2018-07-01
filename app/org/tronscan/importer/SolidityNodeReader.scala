package org.tronscan.importer

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.stream._
import akka.stream.scaladsl.{BroadcastHub, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source, Zip}
import akka.util
import com.google.protobuf.ByteString
import io.circe.syntax._
import io.grpc.{Status, StatusRuntimeException}
import javax.inject.{Inject, Named}
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.lang3.exception.ExceptionUtils
import org.joda.time.DateTime
import org.tron.api.api.{EmptyMessage, NumberMessage}
import org.tron.common.utils.{Base58, ByteArray}
import org.tron.protos.Tron.Account
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AccountUpdateContract, AssetIssueContract, ParticipateAssetIssueContract, TransferAssetContract, TransferContract, UnfreezeAssetContract, UnfreezeBalanceContract, VoteWitnessContract, WithdrawBalanceContract, WitnessCreateContract, WitnessUpdateContract}
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.domain.Events.{AssetIssueCreated, ParticipateAssetIssueModelCreated, VoteCreated}
import org.tronscan.grpc.{FullNodeBlockChain, GrpcClients, SolidityBlockChain, WalletClient}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import org.tronscan.protocol.TransactionUtils
import org.tronscan.network.NetworkScanner
import org.tronscan.network.NetworkScanner.GetBestNodes
import org.tronscan.service.SynchronisationService
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.async.Async.{await, _}
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
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

  var resumeFromBlock = -1L
  var addressSyncer = ActorRef.noSender

  def syncChain(): Future[Unit] = async {

    println("START SOLIDITY SYNC")

    val decider: Supervision.Decider = {
      case exc: StatusRuntimeException if exc.getStatus == Status.DEADLINE_EXCEEDED =>
        println("DEADLINE REACHED, RESTARTING", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Restart
      case exc =>
        println("SOLIDITY STREAM ERROR", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    // Check if we should allow full to sync first
    val walletFull = await(walletClient.full)
    val fullBlockChain = new FullNodeBlockChain(walletFull)
    val fullNodeBlockId = await(fullBlockChain.headBlock).getBlockHeader.getRawData.number

    val latestDbBlockNumber: Long = await(synchronisationService.currentSynchronizedBlock).map(_.number).getOrElse(0)
    val fullNodeBlockDifference = Math.abs(fullNodeBlockId - latestDbBlockNumber)
    if (100 < fullNodeBlockDifference) {
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

    val blocks = solidityStreamBuilder.readBlocks(latestUnconfirmedBlock, syncToBlock)

    if ((syncToBlock - latestUnconfirmedBlock) > 0) {

      val blockSource = blocks
        .mapAsync(12) { solidityBlock =>
          for {
            databaseBlock <- blockModelRepository.findByNumber(solidityBlock.getBlockHeader.getRawData.number)
          } yield (solidityBlock, databaseBlock.get)
        }
        .filter(x => x._1.blockHeader.isDefined && !x._2.confirmed)
        .toMat(BroadcastHub.sink)(Keep.right)
        .run()

      val syncTask = blockSource
        .map {
          case (solidityBlock, databaseBlock) =>

            val addresses = ListBuffer[String]()

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

            addresses.append(header.witnessAddress.encodeAddress)

            queries.appendAll(for {
              transaction <- solidityBlock.transactions
            } yield {

              val transactionHash = transaction.hash
              val transactionTime = new DateTime(header.timestamp)

              val transactionModel = TransactionModel(
                hash = transactionHash,
                block = header.number,
                timestamp = transactionTime,
                ownerAddress = TransactionUtils.getOwner(transaction.getRawData.contract.head),
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

              // println(s"block: ${header.number}", s"transaction hash: $transactionHash", "timestamp: " + transaction.getRawData.timestamp)

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

                  addresses.append(from)
                  addresses.append(to)

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

                  addresses.append(from)
                  addresses.append(to)

                //                case ParticipateAssetIssueContract =>
                //                  val participateAssetIssueContract = org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(any.value.toByteArray)
                //
                //                  val participateAsset = ParticipateAssetIssueModel(
                //                    ownerAddress = Base58.encode58Check(participateAssetIssueContract.ownerAddress.toByteArray),
                //                    toAddress = Base58.encode58Check(participateAssetIssueContract.toAddress.toByteArray),
                //                    amount = participateAssetIssueContract.amount,
                //                    block = header.number,
                //                    token = new String(participateAssetIssueContract.assetName.toByteArray),
                //                    dateCreated = transactionTime
                //                  )
                //
                //                  context.system.eventStream.publish(ParticipateAssetIssueModelCreated(participateAsset))
                //
                //                  participateAssetIssueRepository.insertAsync(participateAsset)

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

                  addresses.append(voterAddress)

                  queries.appendAll(voteWitnessContractModelRepository.buildUpdateVotes(voterAddress, inserts))

                case AssetIssueContract =>
                  val assetIssueContract = org.tron.protos.Contract.AssetIssueContract.parseFrom(any.value.toByteArray)
                  val owner = Base58.encode58Check(assetIssueContract.ownerAddress.toByteArray)

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

                  addresses.append(owner)

                  queries.append(assetIssueContractModelRepository.buildInsert(assetIssue))

                case ParticipateAssetIssueContract =>
                  val participateAssetIssueContract = org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(any.value.toByteArray)

                  val ownerAddress = Base58.encode58Check(participateAssetIssueContract.ownerAddress.toByteArray)
                  val toAddress = Base58.encode58Check(participateAssetIssueContract.toAddress.toByteArray)

                  addresses.append(ownerAddress)
                  addresses.append(toAddress)

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

                  addresses.append(owner)
                  queries.append(witnessModelRepository.buildInsertOrUpdate(witnessModel))

                case WitnessUpdateContract =>
                  val witnessUpdateContract = org.tron.protos.Contract.WitnessUpdateContract.parseFrom(any.value.toByteArray)

                  val witnessModel = WitnessModel(
                    address = witnessUpdateContract.ownerAddress.encodeAddress,
                    url = new String(witnessUpdateContract.updateUrl.toByteArray),
                  )

                  addresses.append(witnessUpdateContract.ownerAddress.encodeAddress)
                  queries.append(witnessModelRepository.buildUpdate(witnessModel))

                case UnfreezeBalanceContract =>
                  val unfreezeBalanceContract = org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(any.value.toByteArray)
                  queries.append(voteWitnessContractModelRepository.buildDeleteVotesForAddress(unfreezeBalanceContract.ownerAddress.encodeAddress))
                  addresses.append(unfreezeBalanceContract.ownerAddress.encodeAddress)

                case WithdrawBalanceContract =>
                  val withdrawBalanceContract = org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(any.value.toByteArray)
                  addresses.append(withdrawBalanceContract.ownerAddress.encodeAddress)

                case AccountUpdateContract =>
                  val accountUpdateContract = org.tron.protos.Contract.AccountUpdateContract.parseFrom(any.value.toByteArray)
                  addresses.append(accountUpdateContract.ownerAddress.encodeAddress)

                case UnfreezeAssetContract =>
                  val unfreezeAssetContract = org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(any.value.toByteArray)
                  addresses.append(unfreezeAssetContract.ownerAddress.encodeAddress)

                case _ =>
              }
            }

            addresses.foreach(address => addressSyncer ! address)

            queries.toList
        }
        .flatMapConcat(queries => Source(queries))
        .groupedWithin(500, 10.seconds)
        .mapAsync(1) { queries =>
          blockModelRepository.executeQueries(queries)
        }
        .toMat(Sink.ignore)(Keep.right)
        .run

      val addresses = blockSource
          .flatMapConcat { case (block, _) =>
            Source((for {
              transaction <- block.transactions
              contract <- transaction.getRawData.contract
            } yield ProtoUtils.fromContract(contract)).toList)
          }

      await(syncTask)
    }

    println("SOLIDITY SYNC DONE")
  }.recover {
    case exc =>
      println("SOLIDITY ERROR", ExceptionUtils.getMessage(exc), ExceptionUtils.getMessage(exc))
      exc
  }

  def startAddressSync() = {

    val decider: Supervision.Decider = {
      case exc: StatusRuntimeException if exc.getStatus == Status.DEADLINE_EXCEEDED =>
        println("DEADLINE REACHED, RESTARTING", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Restart
      case exc =>
        println("ADDRESS SYNC ERROR", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    Source.actorRef[String](500, OverflowStrategy.dropHead)
      .mapMaterializedValue { actorRef =>
        addressSyncer = actorRef
      }
      .groupedWithin(500, 15.seconds)
      .map { addresses => addresses.distinct }
      .flatMapConcat(x => Source(x))
      .mapAsyncUnordered(8) { address =>
        async {

          redisCache.removeMatching(s"address/$address/*")

          val walletSolidity = await(walletClient.solidity)

          val account = await(walletSolidity.getAccount(Account(
            address = ByteString.copyFrom(Base58.decode58Check(address))
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
      .toMat(Sink.ignore)(Keep.none)
      .run
  }

  def getClients = {
    implicit val timeout = util.Timeout(10.seconds)
    (nodeWatchDog ? GetBestNodes(10, n => n.nodeType == NetworkScanner.solidity && n.permanent)).mapTo[GrpcClients]
  }

  override def preStart(): Unit = {
    startAddressSync()
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
