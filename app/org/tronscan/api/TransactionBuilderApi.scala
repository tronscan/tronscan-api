package org
package tronscan.api

import com.google.protobuf.any.Any
import io.circe.syntax._
import io.circe.{Decoder, Json}
import io.swagger.annotations._
import javax.inject.Inject
import org.tron.common.utils.ByteArray
import org.tron.protos.Contract.{AccountCreateContract, AccountUpdateContract, TransferAssetContract, TransferContract, _}
import org.tron.protos.Tron.Transaction
import org.tron.protos.Tron.Transaction.Contract.ContractType
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.grpc.WalletClient
import org.tronscan.service.TransactionBuilder
import play.api.mvc.{AnyContent, Request, Result}

import scala.concurrent.Future


@Api(
  value = "Transaction Builder",
  produces = "application/json")
class TransactionBuilderApi @Inject()(
  transactionBuilder: TransactionBuilder,
  walletClient: WalletClient) extends BaseApi {


  case class TransactionAction(
      contract: Transaction.Contract,
      broadcast: Boolean,
      key: Option[String] = None,
      data: Option[String] = None)

  import TransactionSerializer._

  import scala.concurrent.ExecutionContext.Implicits.global

  def handleTransaction[T]()(implicit request: Request[AnyContent], contractDecoder: Decoder[T]): Future[Result] = async {
    val json: io.circe.Json = request.body.asJson.get

    val transactionRequest = for {
      contract <- json.hcursor.downField("contract").as[T]
      broadcast <- json.hcursor.downField("broadcast").as[Option[Boolean]]
      key <- json.hcursor.downField("key").as[Option[String]]
    } yield {
      val transactionContract = contract match {
        case c: TransferContract =>
          Transaction.Contract(
            `type` = ContractType.TransferContract,
            parameter = Some(Any.pack(c.asInstanceOf[TransferContract])))

        case c: TransferAssetContract =>
          Transaction.Contract(
            `type` = ContractType.TransferAssetContract,
            parameter = Some(Any.pack(c.asInstanceOf[TransferAssetContract])))

        case c: AccountCreateContract =>
          Transaction.Contract(
            `type` = ContractType.AccountCreateContract,
            parameter = Some(Any.pack(c.asInstanceOf[AccountCreateContract])))

        case c: AccountUpdateContract =>
          Transaction.Contract(
            `type` = ContractType.AccountUpdateContract,
            parameter = Some(Any.pack(c.asInstanceOf[AccountUpdateContract])))

        case c: WithdrawBalanceContract =>
          Transaction.Contract(
            `type` = ContractType.WithdrawBalanceContract,
            parameter = Some(Any.pack(c.asInstanceOf[WithdrawBalanceContract])))

        case c: ProposalApproveContract =>
          Transaction.Contract(
            `type` = ContractType.ProposalApproveContract,
            parameter = Some(Any.pack(c.asInstanceOf[ProposalApproveContract])))

        case c: UpdateAssetContract =>
          Transaction.Contract(
            `type` = ContractType.UpdateAssetContract,
            parameter = Some(Any.pack(c.asInstanceOf[UpdateAssetContract])))
      }

      TransactionAction(transactionContract, broadcast.getOrElse(false), key, json.hcursor.downField("data").as[String].toOption)
    }

    transactionRequest match {
      case Right(TransactionAction(contract: Transaction.Contract, broadcast, key, transactionData)) =>
        var transaction = transactionBuilder.buildTransactionWithContract(contract)
        transaction = await(transactionBuilder.setReference(transaction))

        transactionData.foreach { data =>
          transaction = transaction.withRawData(transaction.getRawData.withData(data.encodeString))
        }

        key.foreach { k =>
          transaction = transactionBuilder.sign(transaction, ByteArray.fromHexString(k))
        }

        val serializedTransaction = TransactionSerializer.serialize(transaction)

        if (broadcast) {
          await(for {
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
          })
        } else {
          Ok(Json.obj(
            "transaction" -> serializedTransaction.asJson,
            "success" -> true.asJson,
          ))
        }
      case Left(failure) =>
        BadRequest(Json.obj(
          "message" -> failure.toString().asJson,
          "success" -> true.asJson,
        ))
    }
  }

  @ApiOperation(
    value = "Build TransferContract")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      required = true,
      dataType = "org.tronscan.api.models.TransferTransaction",
      paramType = "body"),
  ))
  def transfer = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.TransferContract]()
  }

  @ApiOperation(
    value = "Build TransferAssetContract" )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      required = true,
      dataType = "org.tronscan.api.models.TransferAssetTransaction",
      paramType = "body"),
  ))
  def transferAsset = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.TransferAssetContract]()
  }

  @ApiOperation(
    value = "Build AccountCreateContract" )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      required = true,
      dataType = "org.tronscan.api.models.AccountCreateTransaction",
      paramType = "body"),
  ))
  def accountCreate = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.AccountCreateContract]()
  }

  @ApiOperation(
    value = "Build AccountUpdateContract" )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      required = true,
      dataType = "org.tronscan.api.models.AccountUpdateTransaction",
      paramType = "body"),
  ))
  def accountUpdate = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.AccountUpdateContract]()
  }

  @ApiOperation(
    value = "Build WithdrawBalancecontract" )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      required = true,
      dataType = "org.tronscan.api.models.WithdrawBalanceTransaction",
      paramType = "body"),
  ))
  def withdrawBalance = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.WithdrawBalanceContract]()
  }

  @ApiOperation(
    value = "Build ProposalApproveContract")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      required = true,
      dataType = "org.tronscan.api.models.ProposalApproveTransaction",
      paramType = "body"),
  ))
  def proposalApprove = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.ProposalApproveContract]()
  }

  @ApiOperation(
    value = "Build UpdateAssetContract")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      required = true,
      dataType = "org.tronscan.api.models.UpdateAssetTransaction",
      paramType = "body"),
  ))
  def updateAsset = Action.async { implicit req =>
    handleTransaction[org.tron.protos.Contract.UpdateAssetContract]()
  }
}


