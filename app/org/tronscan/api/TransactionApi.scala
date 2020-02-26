package org
package tronscan.api

import io.circe.generic.auto._
import io.circe.syntax._
import io.swagger.annotations._
import javax.inject.Inject
import org.joda.time.DateTime
import org.tron.common.utils.ByteArray
import org.tron.protos.Tron.Transaction
import org.tron.protos.Tron.Transaction.Contract.ContractType
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.db.PgProfile.api._
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{TransactionModel, TransactionModelRepository}
import play.api.cache.redis.CacheAsyncApi
import play.api.cache.{Cached, NamedCache}
import play.api.libs.json.{JsObject, Json}

import scala.async.Async._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Try}


@Api(
  value = "Transactions",
  produces = "application/json")
class TransactionApi @Inject()(
  transactionRepository: TransactionModelRepository,
  cached: Cached,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  walletClient: WalletClient) extends BaseApi {

  @ApiResponses(Array(
    new ApiResponse(
      code = 200,
      message = "Transactions found",
      response = classOf[TransactionModel])
  ))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "hash",
      value = "Transaction Hash",
      required = false,
      dataType = "string",
      paramType = "query"),
    new ApiImplicitParam(
      name = "block",
      value = "Block Number",
      required = false,
      dataType = "long",
      paramType = "query"),
  ))
  @ApiOperation(
    value = "Retrieve transactions",
    response = classOf[TransactionModel],
    responseContainer = "List")
  def findAll() = Action.async { implicit request =>

    import transactionRepository._

    val queryParams = request.queryString.map(x => x._1.toLowerCase -> x._2.mkString)
    val queryHash = queryParams.map(x => x._1 + "-" + x._2).mkString
    val filterHash = stripNav(queryParams).map(x => x._1 + "-" + x._2).mkString
    val includeCount = request.getQueryString("count").exists(x => true)


    def getTransactions = {

      var q = sortWithRequest() {
        case (t, "timestamp") => t.timestamp
        case (t, "block") => t.block
      }

      q = q andThen filterRequest {
        case (query, ("block", value)) =>
          query.filter(_.block === value.toLong)
        case (query, ("address", value)) =>
          query.filter(x => x.ownerAddress === value || x.toAddress === value)
        case (query, ("address_from", value)) =>
          query.filter(_.ownerAddress === value)
        case (query, ("address_to", value)) =>
          query.filter(_.toAddress === value)
        case (query, ("hash", value)) =>
          query.filter(_.hash === value.toLowerCase)
        case (query, ("date_start", value)) =>
          val dateStart = Try(value.toLong) match {
            case Success(timestamp) =>
              new DateTime(timestamp)
            case _ =>
              DateTime.parse(value)
          }
          query.filter(x => x.timestamp >= dateStart)
        case (query, ("date_to", value)) =>
          val dateStart = Try(value.toLong) match {
            case Success(timestamp) =>
              new DateTime(timestamp)
            case _ =>
              DateTime.parse(value)
          }
          query.filter(x => x.timestamp <= dateStart)
        case (query, ("contract_type", value)) =>
          query.filter(x => x.contractType === value.toInt)
        case (query, ("token", value)) if value.toUpperCase == "TRX" =>
          query.filter(x => x.contractType === ContractType.TransferContract.value)
        case (query, ("token", value)) =>
          query.filter(x => x.contractType === ContractType.TransferAssetContract.value && x.contractData.+>>("token") === value)
        case (query, _) =>
          query
      }

      for {
        total <- if (includeCount) {
          redisCache.getOrFuture(s"transactions/total?$filterHash", 1.minute) {
            readTotals(q).map(_.toLong)
          }
        } else Future.successful(0L)
        data <- readQuery(q andThen limitWithRequest())
      } yield (total, data)
    }


    if (queryParams.contains("address")) {
      redisCache.getOrFuture(s"address/${queryParams("address")}/transaction-query?$queryHash", 10.minutes) {
        getTransactions
      }.map {
        case (total, data) =>
          Ok(Json.obj(
            "total" -> total,
            "data" -> data.asJson,
          ))
      }
    } else {
      for {
        (total, data) <- redisCache.getOrFuture(s"transactions/query?" + queryParams.map(x => x._1 + "-" + x._2).mkString, 10.seconds) {
          getTransactions
        }
      } yield {
        Ok(Json.obj(
          "total" -> total,
          "data" -> data.asJson,
        ))
      }
    }
  }

  @ApiResponses(Array(
    new ApiResponse(
      code = 200,
      message = "Transaction Successfully created")
  ))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      required = true,
      dataType = "org.tronscan.api.models.CreateTransaction",
      paramType = "body"),
  ))
  @ApiOperation(
    value = "Send transaction to the network",
    response = classOf[TransactionModel],
  )
  def create = Action.async { req =>
    async {

      val transactionBytes = (req.body.asJson.get.as[JsObject] \ "transaction").as[String]
      val transaction = Transaction.parseFrom(ByteArray.fromHexString(transactionBytes))
      val decoded = TransactionSerializer.serialize(transaction)

      if (req.getQueryString("dry-run").isDefined) {
        Ok(Json.obj(
          "transaction" -> decoded,
        ))
      } else {
        val wallet = await(walletClient.full)
        val result = await(wallet.broadcastTransaction(transaction))

        Ok(Json.obj(
          "success" -> result.result,
          "code" -> result.code.toString,
          "message" -> new String(result.message.toByteArray).toString,
          "transaction" -> decoded,
        ))
      }
    }
  }

  @ApiOperation(
    value = "Find transaction by hash",
    response = classOf[TransactionModel] )
  def findByHash(hash: String) = Action.async {
    transactionRepository.findByHash(hash).map {
      case Some(transaction) =>
        Ok(transaction.asJson)
      case _ =>
        NotFound
    }
  }
}
