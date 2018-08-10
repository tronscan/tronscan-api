package org
package tronscan.api

import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax._
import io.swagger.annotations.Api
import javax.inject.Inject
import org.tron.api.api.{BytesMessage, EmptyMessage, NumberMessage, WalletGrpc}
import org.tron.common.utils.{Base58, ByteArray, ByteUtil}
import org.tron.protos.Tron.Account
import org.tronscan.api.models.{TransactionSerializer, TronModelsSerializers}
import org.tronscan.grpc.{GrpcService, WalletClient}
import play.api.mvc.Request
import org.tronscan.api.models.TransactionSerializer._

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
      client <- getClient
      block <- client.getNowBlock(EmptyMessage())
    } yield {
      Ok(Json.obj(
        "data" -> block.asJson
      ))
    }
  }


  def getBlockByNum(number: Long) = Action.async { implicit req =>

    for {
      client <- getClient
      block <- client.getBlockByNum(NumberMessage(number))
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

  def getTransactionById(hash: String) = Action.async { implicit req =>

    for {
      client <- getClient
      transaction <- client.getTransactionById(BytesMessage(ByteString.copyFrom(ByteArray.fromHexString(hash))))
    } yield {
      Ok(Json.obj(
        "data" -> TransactionSerializer.serialize(transaction)
      ))
    }
  }

  def totalTransaction = Action.async { implicit req =>

    for {
      client <- getClient
      total <- client.totalTransaction(EmptyMessage())
    } yield {
      Ok(Json.obj(
        "data" -> total.num.asJson
      ))
    }
  }

  def getAccount(address: String) = Action.async { implicit req =>

    for {
      client <- getClient
      account <- client.getAccount(Account(
        address = ByteString.copyFrom(Base58.decode58Check(address))
      ))
    } yield {
      Ok(Json.obj(
        "data" -> account.asJson
      ))
    }
  }

  def getAccountNet(address: String) = Action.async { implicit req =>

    for {
      client <- getClient
      accountNet <- client.getAccountNet(Account(
        address = ByteString.copyFrom(Base58.decode58Check(address))
      ))
    } yield {
      Ok(Json.obj(
        "data" -> accountNet.asJson
      ))
    }
  }


  def listNodes = Action.async { implicit req =>

    for {
      client <- getClient
      nodes <- client.listNodes(EmptyMessage())
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
      client <- getClient
      witnessList <- client.listWitnesses(EmptyMessage())
    } yield {
      Ok(Json.obj(
        "data" -> witnessList.witnesses.map(_.asJson).asJson
      ))
    }
  }
}
