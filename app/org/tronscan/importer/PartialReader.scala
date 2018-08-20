package org.tronscan.importer

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream._
import akka.util
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import javax.inject.{Inject, Named}
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.lang3.exception.ExceptionUtils
import org.joda.time.DateTime
import org.tron.api.api.{EmptyMessage, NumberMessage}
import org.tron.common.utils.{Base58, ByteArray}
import org.tron.protos.Tron.Account
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AssetIssueContract, TransferAssetContract, TransferContract, VoteWitnessContract, WitnessCreateContract}
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.events._
import org.tronscan.grpc.{GrpcClients, WalletClient}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import org.tronscan.utils.ContractUtils
import org.tronscan.watchdog.NodeWatchDog
import org.tronscan.watchdog.NodeWatchDog.GetBestNodes
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction
import io.circe.syntax._
import io.circe.generic.auto._
import scala.async.Async._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class PartialReader @Inject()(
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  assetIssueContractModelRepository: AssetIssueContractModelRepository,
  transferRepository: TransferModelRepository,
  voteWitnessContractModelRepository: VoteWitnessContractModelRepository,
  witnessModelRepository: WitnessModelRepository,
  accountModelRepository: AccountModelRepository,
  addressBalanceModelRepository: AddressBalanceModelRepository,
  walletClient: WalletClient,
  @Named("node-watchdog") nodeWatchDog: ActorRef,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  configurationProvider: ConfigurationProvider) extends Actor {

  val config = configurationProvider.get
  var addressSyncer = ActorRef.noSender


  def syncChain(from: Long, to: Long) = async {

    println("START BLOCKCHAIN SYNC")

    val decider: Supervision.Decider = {
      case exc =>
        println("FULL NODE ERROR", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    val clients = await(getClients)

    println("got clients", clients.clients.size)

    val wallet = await(walletClient.full)


    println(s"BLOCKCHAIN SYNC FROM $from to $to")

    val (killSwitch, syncTask) = Source(from to to)
      .take(1000)
      .mapAsync(12) { i => wallet.getBlockByNum(NumberMessage(i)) }
      .filter(block => {
        if (to > 0) {
          block.getBlockHeader.getRawData.number > 0
        } else true
      })
      .viaMat(KillSwitches.single)(Keep.right)
      .mapAsync(1) { block =>

        val header = block.getBlockHeader.getRawData

        val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

        println("PARTIAL NODE BLOCK", header.number)

        queries.append(blockModelRepository.buildInsertOrUpdate(BlockModel(
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
            ownerAddress = ContractUtils.getOwner(transaction.getRawData.contract.head),
            toAddress = ContractUtils.getTo(transaction.getRawData.contract.head).getOrElse(""),
            timestamp = transactionTime,
            contractData = TransactionSerializer.serializeContract(transaction.getRawData.contract.head),
            contractType = transaction.getRawData.contract.head.`type`.value,
            data = ByteArray.toHexString(transaction.getRawData.data.toByteArray)
          )

          transactionModelRepository.buildInsertOrUpdate(transactionModel)
        })

        for {
          transaction <- block.transactions
          contract <- transaction.getRawData.contract
        } {
          val any = contract.getParameter

          val transactionHash = transaction.hash
          val transactionTime = new DateTime(header.timestamp)

          //            println(s"block: ${header.number}", s"transaction hash: $transactionHash", "timestamp: " + transaction.getRawData.timestamp)

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

              addressSyncer ! transferContract.toAddress.encodeAddress
              addressSyncer ! transferContract.ownerAddress.encodeAddress

              queries.append(transferRepository.buildInsertOrUpdate(trxModel))

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

              addressSyncer ! transferContract.toAddress.encodeAddress
              addressSyncer ! transferContract.ownerAddress.encodeAddress


              //              context.system.eventStream.publish(AssetTransferCreated(trxModel))

              queries.append(transferRepository.buildInsertOrUpdate(trxModel))
//
//            case AssetIssueContract =>
//              val assetIssueContract = org.tron.protos.Contract.AssetIssueContract.parseFrom(any.value.toByteArray)
//              val owner = Base58.encode58Check(assetIssueContract.ownerAddress.toByteArray)
//
//              val assetIssue = AssetIssueContractModel(
//                block = header.number,
//                transaction = transactionHash,
//                ownerAddress = owner,
//                name = new String(assetIssueContract.name.toByteArray).trim,
//                abbr = new String(assetIssueContract.abbr.toByteArray).trim,
//                totalSupply = assetIssueContract.totalSupply,
//                trxNum = assetIssueContract.trxNum,
//                num = assetIssueContract.num,
//                startTime = new DateTime(assetIssueContract.startTime),
//                endTime = new DateTime(assetIssueContract.endTime),
//                voteScore = assetIssueContract.voteScore,
//                description = new String(assetIssueContract.description.toByteArray),
//                url = new String(assetIssueContract.url.toByteArray),
//                dateCreated = transactionTime,
//              ).withFrozen(assetIssueContract.frozenSupply)

//              context.system.eventStream.publish(AssetIssueCreated(assetIssue))

//              queries.append(assetIssueContractModelRepository.buildInsertOrUpdate(assetIssue))
//            case VoteWitnessContract if !syncSolidity =>
//              val voteWitnessContract = org.tron.protos.Contract.VoteWitnessContract.parseFrom(any.value.toByteArray)
//              val voterAddress = voteWitnessContract.ownerAddress.encodeAddress
//
//              val inserts = for (vote <- voteWitnessContract.votes) yield {
//                VoteWitnessContractModel(
//                  transaction = transactionHash,
//                  block = header.number,
//                  timestamp = transactionTime,
//                  voterAddress = voterAddress,
//                  candidateAddress = vote.voteAddress.encodeAddress,
//                  votes = vote.voteCount,
//                )
//              }
//
//              inserts.foreach { vote =>
//                context.system.eventStream.publish(VoteCreated(vote))
//              }
//
//              queries.appendAll(voteWitnessContractModelRepository.buildUpdateVotes(voterAddress, inserts))

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
              val owner = witnessCreateContract.ownerAddress.encodeAddress

              addressSyncer ! owner

              val witnessModel = WitnessModel(
                address = owner,
                url = new String(witnessCreateContract.url.toByteArray),
              )

//              context.system.eventStream.publish(WitnessCreated(witnessModel))

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

        blockModelRepository.executeQueries(queries)
      }
      .toMat(Sink.ignore)(Keep.both)
      .run

    await(syncTask)
  }.andThen {
    case Success(x) =>

    case Failure(exc) =>
      println("BLOCKCHAIN PARTIAL SYNC FAILURE", ExceptionUtils.getMessage(exc), ExceptionUtils.getStackTrace(exc))
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

    Source.actorRef[String](5000, OverflowStrategy.dropHead)
      .mapMaterializedValue { actorRef =>
        addressSyncer = actorRef
      }
      .groupedWithin(500, 15.seconds)
      .map { addresses => addresses.distinct }
      .flatMapConcat(x => Source(x))
      .mapAsyncUnordered(8) { address =>
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

            await(accountModelRepository.insertOrUpdate(accountModel))
            await(addressBalanceModelRepository.updateBalance(accountModel))
          }

          redisCache.removeMatching(s"address/$address/*")
        }
      }
  }


  override def preStart(): Unit = {
    startAddressSync()
  }

  def getClients = {
    implicit val timeout = util.Timeout(10.seconds)
    (nodeWatchDog ? GetBestNodes(10, n => n.nodeType == NodeWatchDog.full && n.permanent)).mapTo[GrpcClients]
  }

  def receive = {
    case ImportRange(from, to) =>
      syncChain(from, to)
  }
}


case class ImportRange(from: Long, to: Long)