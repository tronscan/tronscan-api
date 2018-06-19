package org
package tronscan.api

import io.circe.{Decoder, Json}
import io.swagger.annotations._
import javax.inject.Inject
import org.tron.common.utils.ByteArray
import org.tron.protos.Tron.Transaction
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.grpc.WalletClient
import org.tronscan.models.TransactionModel
import org.tronscan.service.TransactionBuilder
import play.api.mvc.{AnyContent, Request, Result}

import scala.concurrent.Future
import io.circe.syntax._

case class TransactionAction(
  contract: Transaction.Contract,
  broadcast: Boolean,
  key: Option[String] = None
)

case class Signature(pk: String)

@Api(
  value = "Transaction Builder",
  produces = "application/json")
class TransactionBuilderApi @Inject()(
  transactionBuilder: TransactionBuilder,
  walletClient: WalletClient) extends BaseApi {

  import scala.concurrent.ExecutionContext.Implicits.global
  import TransactionSerializer._

  def handleTransaction[T]()(implicit request: Request[AnyContent], contractDecoder: Decoder[T]): Future[Result] = {
    val json: io.circe.Json = request.body.asJson.get

    val transactionRequest = for {
      contract <- json.hcursor.downField("contract").as[T]
      broadcast <- json.hcursor.downField("broadcast").as[Option[Boolean]]
      key <- json.hcursor.downField("key").as[Option[String]]
    } yield TransactionAction(contract.asInstanceOf[Transaction.Contract], broadcast.getOrElse(false), key)

    transactionRequest match {
      case Right(TransactionAction(contract: Transaction.Contract, broadcast, key)) =>
        var transaction = transactionBuilder.buildTransactionWithContract(contract)

        key.foreach { k =>
          transaction = transactionBuilder.sign(transaction, ByteArray.fromHexString(k))
        }

        val serializedTransaction = TransactionSerializer.serialize(transaction)

        if (broadcast) {
          for {
            full <- walletClient.full
            result <- full.broadcastTransaction(transaction)
          } yield {
            Ok(Json.obj(
              "transaction" -> serializedTransaction.asJson,
              "success" -> result.result.asJson,
              "result" -> Json.obj(
                "code" -> result.code.toString.asJson,
                "message" -> new String(result.message.toByteArray).toString.asJson,
              )
            ))
          }
        } else {
          Future.successful {
            Ok(Json.obj(
              "transaction" -> serializedTransaction.asJson,
              "success" -> true.asJson,
            ))
          }
        }
      case Left(failure) =>
        Future.successful {
          BadRequest(Json.obj(
            "message" -> failure.toString().asJson,
            "success" -> true.asJson,
          ))
        }
    }
  }

  @ApiOperation(
    value = "Build TransferContract")
  def transfer = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.TransferContract]()
  }

  @ApiOperation(
    value = "Build TransferAssetContract" )
  def transferAsset = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.TransferAssetContract]()
  }

  @ApiOperation(
    value = "Build AccountCreateContract" )
  def accountCreate = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.AccountCreateContract]()
  }

  @ApiOperation(
    value = "Build AccountUpdateContract" )
  def accountUpdate = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.AccountUpdateContract]()
  }
}
