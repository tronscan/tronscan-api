package org
package tronscan.api

import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax._
import io.swagger.annotations.{Api, ApiImplicitParam, ApiImplicitParams, ApiOperation}
import javax.inject.Inject
import org.tron.api.api._
import org.tron.common.utils.ByteArray
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer._
import org.tronscan.api.models.{TransactionSerializer, TronModelsSerializers}
import org.tronscan.grpc.{GrpcService, WalletClient}
import play.api.mvc.Request

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

  @ApiOperation(
    value = "Retrieve the latest block on")
  def getNowBlock = Action.async { implicit req =>

    for {
      block <- walletClient.fullRequest(_.getNowBlock(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> block.asJson
      ))
    }
  }

  @ApiOperation(
    value = "Retrieve a block by number")
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

  @ApiOperation(
    value = "Retrieve a range of blocks")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "from",
      value = "From block",
      required = true,
      dataType = "long",
      paramType = "query"),
    new ApiImplicitParam(
      name = "to",
      value = "To block",
      required = true,
      dataType = "long",
      paramType = "query"),
  ))
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

  @ApiOperation(
    value = "Retrieve a transaction by the transaction hash")
  def getTransactionById(hash: String) = Action.async { implicit req =>

    for {
      transaction <- walletClient.fullRequest(_.getTransactionById(BytesMessage(ByteString.copyFrom(ByteArray.fromHexString(hash)))))
    } yield {
      Ok(Json.obj(
        "data" -> TransactionSerializer.serialize(transaction)
      ))
    }
  }

  @ApiOperation(
    value = "Retrieve total number of transactions")
  def totalTransaction = Action.async { implicit req =>

    for {
      total <- walletClient.fullRequest(_.totalTransaction(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> total.num.asJson
      ))
    }
  }

  @ApiOperation(
    value = "Retrieve account by address")
  def getAccount(address: String) = Action.async { implicit req =>

    for {
      account <- walletClient.fullRequest(_.getAccount(address.toAccount))
    } yield {
      Ok(Json.obj(
        "data" -> account.asJson
      ))
    }
  }

  @ApiOperation(
    value = "Retrieve bandwidth information for the given account")
  def getAccountNet(address: String) = Action.async { implicit req =>

    for {
      accountNet <- walletClient.fullRequest(_.getAccountNet(address.toAccount))
    } yield {
      Ok(Json.obj(
        "data" -> accountNet.asJson
      ))
    }
  }

  @ApiOperation(
    value = "Retrieve nodes")
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

  @ApiOperation(
    value = "Retrieve all witnesses")
  def listWitnesses = Action.async { implicit req =>

    for {
      witnessList <- walletClient.fullRequest(_.listWitnesses(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> witnessList.witnesses.map(_.asJson).asJson
      ))
    }
  }

  @ApiOperation(
    value = "Retrieve all proposals")
  def listProposals = Action.async { implicit req =>

    for {
      proposalList <- walletClient.fullRequest(_.listProposals(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> proposalList.proposals.map(_.asJson).asJson
      ))
    }
  }

  @ApiOperation(
    value = "Retrieve exchanges")
  def listExchanges = Action.async { implicit req =>

    for {
      exchangeList <- walletClient.fullRequest(_.listExchanges(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> exchangeList.exchanges.map(_.asJson).asJson
      ))
    }
  }

  @ApiOperation(
    value = "Retrieve blockchain parameters")
  def getChainParameters = Action.async { implicit req =>

    for {
      chainParameters <- walletClient.fullRequest(_.getChainParameters(EmptyMessage()))
    } yield {
      Ok(Json.obj(
        "data" -> chainParameters.chainParameter.map(_.asJson).asJson
      ))
    }
  }

}
