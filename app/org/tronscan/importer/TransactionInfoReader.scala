package org.tronscan.importer

import akka.NotUsed
import akka.actor.Actor
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import com.google.protobuf.ByteString
import javax.inject.Inject
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.lang3.exception.ExceptionUtils
import org.tron.api.api.BytesMessage
import org.tron.common.utils.ByteArray
import org.tronscan.grpc.WalletClient
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import play.api.inject.ConfigurationProvider

import scala.async.Async._
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class TransactionInfoReader @Inject()(
  transactionModelRepository: TransactionModelRepository,
  walletClient: WalletClient,
  configurationProvider: ConfigurationProvider) extends Actor {

  val config = configurationProvider.get


  def buildTransactionSource: Source[TransactionModel, NotUsed] = {

    Source.unfoldAsync(0) { _ =>

      transactionModelRepository.findWithoutFee(1000).map { txs =>
        if (txs.nonEmpty) {
          Some(0, txs)
        } else {
          None
        }
      }
    }
    .mapConcat(x => x.toList)
  }

  def buildFeeSync() = async {

    println("START TRANSACTION SYNC")

    val decider: Supervision.Decider = {
      case exc =>
        println("TRANSACTION FEE ERROR", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    val solidityClient = await(walletClient.solidity)

    val syncTask = buildTransactionSource
      .mapAsync(12) { tx =>
        for {
          info <- solidityClient.getTransactionInfoById(BytesMessage(ByteString.copyFrom(ByteArray.fromHexString(tx.hash))))
        } yield (tx, info.fee)
      }
      .map { case (tx, fee) =>
        transactionModelRepository.buildInsertOrUpdate(tx.copy(fee = Some(fee)))
      }
      .groupedWithin(500, 3.seconds)
      .mapAsync(1) { queries =>
        transactionModelRepository.executeQueries(queries)
      }
      .toMat(Sink.ignore)(Keep.right)
      .run

    await(syncTask)

  }.andThen {
    case Success(x) =>
      println("TRANSACTION FEE SYNC DONE")
    case Failure(exc) =>
      println("TRANSACTION FEE SYNC FAILURE", ExceptionUtils.getMessage(exc), ExceptionUtils.getStackTrace(exc))
  }


  def syncFees() = {

    val decider: Supervision.Decider = {
      case exc =>
        println("TRANSACTION FEE ERROR", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    Source.tick(6.seconds, 3.seconds, "")
      .mapAsync(1)(_ => buildFeeSync())
      .runWith(Sink.ignore)
  }

  def receive = {
    case Sync() =>
      syncFees()
  }
}
