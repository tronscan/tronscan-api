package org
package tronscan.api

import com.google.protobuf.ByteString
import io.circe.Json
import io.swagger.annotations.Api
import javax.inject.Inject
import org.tron.api.api.{EmptyMessage, WalletGrpc}
import org.tronscan.grpc.{GrpcService, WalletClient}
import play.api.mvc.Request
import io.circe.syntax._
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.common.utils.Base58
import org.tron.protos.Tron.Account
import org.tronscan.api.models.TronModelsSerializers._

import scala.concurrent.Future

@Api(
  value = "GRPC",
  produces = "application/json")
class GrpcFullApi @Inject() (
  walletClient: WalletClient,
  grpcService: GrpcService
) extends BaseApi {

  import scala.concurrent.ExecutionContext.Implicits.global

  def getClient(implicit request: Request[_]): Future[WalletGrpc.WalletStub] = {
    request.getQueryString("ip") match {
      case Some(ip) =>
        val port = request.getQueryString("port").map(_.toInt).getOrElse(50051)
        grpcService.getChannel(ip, port).map(x => WalletGrpc.stub(x))
      case _ =>
        walletClient.full
    }
  }

  /**
    * Retrieve the current block
    */
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

  /**
    * Retrieve the current block
    */
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
}
