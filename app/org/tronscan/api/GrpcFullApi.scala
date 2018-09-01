package org
package tronscan.api

import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax._
import io.swagger.annotations.Api
import javax.inject.Inject
import org.tron.api.api._
import org.tron.common.utils.{Base58, ByteArray}
import org.tron.protos.Tron.Account
import org.tronscan.api.models.{TransactionSerializer, TronModelsSerializers}
import org.tronscan.grpc.{GrpcService, WalletClient}
import play.api.mvc.Request
import org.tronscan.Extensions._

import scala.concurrent.Future

@Api(
  value = "Full Node GRPC API",
  produces = "application/json",
)
class GrpcFullApi @Inject() (
  walletClient: WalletClient,
  grpcService: GrpcService
) extends BaseApi {

  import scala.concurrent.ExecutionContext.Implicits.global

  val serializer = new TronModelsSerializers
  import serializer._

  /**
    * Retrieves a GRPC client
    *
    * Uses the full node by default, but can be overridden by using the ?ip= parameter
    * Uses the 50051 port by default but can be overridden by using the ?port= parameter
    */
  def getClient(implicit request: Request[_]): Future[WalletGrpc.WalletStub] = {
    request.getQueryString("ip") match {
      case Some(ip) =>
        val port = request.getQueryString("port").map(_.toInt).getOrElse(50051)
        grpcService.getChannel(ip, port).map(x => WalletGrpc.stub(x))
      case _ =>
        walletClient.full
    }
  }

  def getNowBlock = Action.async { implicit req =>

    for {
      block <- walletClient.fullRequest(_.getNowBlock(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> block.asJson
      ))
    }
  }


  def getBlockByNum(number: Long) = Action.async { implicit req =>

    for {
      block <- walletClient.fullRequest(_.getBlockByNum(NumberMessage(number)))
    } yield {
      block.blockHeader match {
        case Some(_) =>
          Ok(Json.obj(
            "data" -> block.asJson
          ))
        case _ =>
          NotFound
      }
    }
  }

  def getBlockByLimitNext = Action.async { implicit req =>

    val from = req.getQueryString("from").get.toLong
    val to = req.getQueryString("to").get.toLong

    for {
      blocks <- walletClient.fullRequest(_.getBlockByLimitNext(BlockLimit(from, to)))
    } yield {
      Ok(Json.obj(
        "data" -> blocks.block.sortBy(_.getBlockHeader.getRawData.number).toList.asJson
      ))
    }
  }

  def getTransactionById(hash: String) = Action.async { implicit req =>

    for {
      transaction <- walletClient.fullRequest(_.getTransactionById(BytesMessage(ByteString.copyFrom(ByteArray.fromHexString(hash)))))
    } yield {
      Ok(Json.obj(
        "data" -> TransactionSerializer.serialize(transaction)
      ))
    }
  }

  def totalTransaction = Action.async { implicit req =>

    for {
      total <- walletClient.fullRequest(_.totalTransaction(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> total.num.asJson
      ))
    }
  }

  def getAccount(address: String) = Action.async { implicit req =>

    for {
      account <- walletClient.fullRequest(_.getAccount(address.toAccount))
    } yield {
      Ok(Json.obj(
        "data" -> account.asJson
      ))
    }
  }

  def getAccountNet(address: String) = Action.async { implicit req =>

    for {
      accountNet <- walletClient.fullRequest(_.getAccountNet(address.toAccount))
    } yield {
      Ok(Json.obj(
        "data" -> accountNet.asJson
      ))
    }
  }


  def listNodes = Action.async { implicit req =>

    for {
      nodes <- walletClient.fullRequest(_.listNodes(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> nodes.nodes.map(node => Json.obj(
          "address" -> ByteArray.toStr(node.getAddress.host.toByteArray).asJson,
        )).asJson
      ))
    }
  }

  def listWitnesses = Action.async { implicit req =>

    for {
      witnessList <- walletClient.fullRequest(_.listWitnesses(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> witnessList.witnesses.map(_.asJson).asJson
      ))
    }
  }
}
