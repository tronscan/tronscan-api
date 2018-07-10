package org
package tronscan.api

import io.swagger.annotations._
import javax.inject.Inject
import org.joda.time.DateTime
import org.tron.common.utils.ByteArray
import org.tron.protos.Tron.Transaction
import play.api.cache.{Cached, NamedCache}
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.InjectedController
import org.tronscan.App._
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.db.PgProfile.api._
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{TransferModel, TransferModelRepository}
import io.circe.generic.auto._
import io.circe.syntax._
import play.api.cache.redis.CacheAsyncApi

import scala.async.Async._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Try}

@Api(
  value = "Transfers",
  produces = "application/json")
class TransferApi @Inject()(
    repo: TransferModelRepository,
    cached: Cached,
    @NamedCache("redis") redisCache: CacheAsyncApi,
    walletClient: WalletClient) extends BaseApi {

  @ApiResponses(Array(
    new ApiResponse(
      code = 200,
      message = "Transfers found",
      response = classOf[TransferModel])
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
    new ApiImplicitParam(
      name = "to",
      value = "To Address",
      required = false,
      dataType = "string",
      paramType = "query"),
    new ApiImplicitParam(
      name = "address",
      value = "From and To address",
      required = false,
      dataType = "string",
      paramType = "query"),
    new ApiImplicitParam(
      name = "from",
      value = "From Address",
      required = false,
      dataType = "string",
      paramType = "query"),
    new ApiImplicitParam(
      name = "token",
      value = "Token",
      required = false,
      dataType = "string",
      paramType = "query"),
  ))
  @ApiOperation(
    value = "List Transfers",
    response = classOf[TransferModel],
    responseContainer = "List")
  def findAll() = Action.async { implicit request =>

    import repo._

    val queryParams = request.queryString.map(x => x._1.toLowerCase -> x._2.mkString)
    val queryHash = queryParams.map(x => x._1 + "-" + x._2).mkString
    val filterHash = stripNav(queryParams).map(x => x._1 + "-" + x._2).mkString
    val includeCount = request.getQueryString("count").exists(x => true)

    def getTransfers = {

      var q = sortWithRequest() {
        case (t, "timestamp") => t.timestamp
      }

      q = q andThen filterRequest {
        case (query, ("block", value)) =>
          query.filter(_.block === value.toLong)
        case (query, ("to", value)) =>
          query.filter(_.transferToAddress === value)
        case (query, ("from", value)) =>
          query.filter(_.transferFromAddress === value)
        case (query, ("token", value)) =>
          query.filter(_.tokenName === value)
        case (query, ("hash", value)) =>
          query.filter(_.transactionHash === value)
        case (query, ("address", value)) =>
          query.filter(x => x.transferFromAddress === value || x.transferToAddress === value)
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
          query.filter(x => x.timestamp >= dateStart)
        case (query, _) =>
          query
      }

      for {
        total <- if (includeCount) {
          redisCache.getOrFuture(s"transfers/total?$filterHash", 1.minute) {
            readTotals(q).map(_.toLong)
          }
        } else Future.successful(0L)
        data <- readQuery(q andThen limitWithRequest())
      } yield (total, data)
    }

    if (queryParams.contains("address")) {
      redisCache.getOrFuture(s"address/${queryParams("address")}/transfer-query?$queryHash", 10.minutes) {
        getTransfers
      }.map {
        case (total, data) =>
          Ok(Json.obj(
            "total" -> total,
            "data" -> data.asJson,
          ))
      }
    } else {

      for {
        (total, data) <- redisCache.getOrFuture(s"transfers/query?$queryHash", 10.seconds) {
          getTransfers
        }
      } yield {
        Ok(Json.obj(
          "total" -> total,
          "data" -> data.asJson,
        ))
      }
    }
  }

  @ApiOperation(
    value = "Find transfer by transaction hash",
    response = classOf[TransferModel] )
  def findByHash(hash: String) = Action.async {
    repo.findByHash(hash).map {
      case Some(transaction) =>
        Ok(transaction.asJson)
      case _ =>
        NotFound
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def stats = cached.status(x => "transfer_stats", 200, 1.minute) {
    Action.async { implicit request =>

      import repo._

      val weeks = DateTime.now.minusHours(1)

      val q2 = query
        .filter(_.timestamp > weeks)
        .groupBy(_.timestamp.trunc("minute"))
        .map {
          case (day, row) =>
            (day, row.size)
        }
        .sortBy(_._1.asc)

      val q3 = query
        .filter(_.timestamp > weeks)
        .filter(_.tokenName.toUpperCase === "TRX")
        .groupBy(_.timestamp.trunc("minute"))
        .map {
          case (day, row) =>
            (day, row.map(_.amount).sum)
        }
        .sortBy(_._1.asc)
      for {
        data <- readAsync(q2)
        data2 <- readAsync(q3)
      } yield {
        Ok(Json.obj(
          "value" -> data2.map(row => Json.obj(
            "timestamp" -> row._1,
            "value" -> row._2,
          )),
          "total" -> data.map(row => Json.obj(
            "timestamp" -> row._1,
            "value" -> row._2,
          ))
        ))
      }
    }
  }

}
