package org
package tronscan.api

import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax._
import io.swagger.annotations.Api
import javax.inject.Inject
import org.tron.api.api.{EmptyMessage, NumberMessage, WalletSolidityGrpc}
import org.tron.common.utils.{Base58, ByteUtil}
import org.tron.protos.Tron.Account
import org.tronscan.api.models.TronModelsSerializers
import org.tronscan.grpc.{GrpcService, WalletClient}
import play.api.mvc.Request

@Api(
  value = "Solidity GRPC API",
  produces = "application/json",
)
class GrpcSolidityApi @Inject()(
  walletClient: WalletClient,
  grpcService: GrpcService
) extends BaseApi {

  import scala.concurrent.ExecutionContext.Implicits.global

  val serializer = new TronModelsSerializers
  import serializer._

  def getClient(implicit request: Request[_]) = {
    request.getQueryString("ip") match {
      case Some(ip) =>
        val port = request.getQueryString("port").map(_.toInt).getOrElse(50051)
        grpcService.getChannel(ip, port).map(x => WalletSolidityGrpc.stub(x))
      case _ =>
        walletClient.solidity
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
