package org.tronscan.api

import akka.actor.ActorRef
import io.swagger.annotations._
import javax.inject.{Inject, Named}
import org.joda.time.DateTime
import org.tron.api.api.EmptyMessage
import org.tron.common.utils.{Base58, ByteUtil}
import org.tronscan.App._
import org.tronscan.Extensions._
import org.tronscan.db.PgProfile.api._
import org.tronscan.grpc.WalletClient
import org.tronscan.importer.ImportRange
import org.tronscan.models.{BlockModel, BlockModelRepository}
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

@Api(
  value = "Blocks",
  produces = "application/json")
class BlockApi @Inject() (
  repo: BlockModelRepository,
  walletClient: WalletClient,
  @Named("partial-reader") partialReader: ActorRef,
  @NamedCache("redis") redisCache: CacheAsyncApi) extends BaseApi {

  @ApiResponses(Array(
    new ApiResponse(
      code = 200,
      message = "Blocks found",
      response = classOf[BlockModel])
  ))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "hash",
      value = "Hash",
      required = false,
      dataType = "string",
      paramType = "query"),
    new ApiImplicitParam(
      name = "number",
      value = "Number",
      required = false,
      dataType = "long",
      paramType = "query"),
    new ApiImplicitParam(
      name = "parenthash",
      value = "Parenthash",
      required = false,
      dataType = "string",
      paramType = "query"),
    new ApiImplicitParam(
      name = "producer",
      value = "Producer",
      required = false,
      dataType = "string",
      paramType = "query"),
  ))
  @ApiOperation(
    value = "List blocks",
    response = classOf[BlockModel],
    responseContainer = "List")
  def findAll = Action.async { implicit request =>

    import repo._

    val queryParams = request.queryString.map(x => x._1.toLowerCase -> x._2.mkString)
    val queryHash = queryParams.map(x => x._1 + "-" + x._2).mkString
    val filterHash = stripNav(queryParams).map(x => x._1 + "-" + x._2).mkString
    val includeCount = request.getQueryString("count").exists(x => true)

    def getBlocks = {

      var q = sortWithRequest() {
        case (t, "number") => t.number
        case (t, "timestamp") => t.timestamp
      }

      q = q andThen filterRequest {
        case (query, ("hash", value)) =>
          query.filter(_.hash === value)
        case (query, ("number", value)) =>
          query.filter(_.number === value.toLong)
        case (query, ("parenthash", value)) =>
          query.filter(_.hash === value)
        case (query, ("producer", value)) =>
          query.filter(_.witnessAddress === value)
        case (query, _) =>
          query
      }

      for {
        total <- if (includeCount) {
          redisCache.getOrFuture(s"blocks/total?$filterHash", 1.minute) {
            readTotals(q).map(_.toLong)
          }
        } else Future.successful(0L)
        accounts <- readQuery(q andThen limitWithRequest())
      } yield (total, accounts)
    }

    for {
      (total, accounts) <- redisCache.getOrFuture(s"blocks/query?$queryHash", 12.seconds) {
        getBlocks
      }
    } yield {
      Ok(Json.obj(
        "total" -> total,
        "data" -> accounts,
      ))
    }
  }

  @ApiOperation(
    value = "Find a block by number",
    response = classOf[BlockModel])
  def findById(id: Long) = Action.async {

    repo
      .findByNumber(id)
      .map {
        case Some(block) =>
          Ok(Json.toJson(block))
        case _ =>
          NotFound
      }
  }

  @ApiOperation(
    value = "Find latest block",
    response = classOf[BlockModel])
  def latest = Action.async {
    for {
      wallet <- walletClient.full
      block <- wallet.getNowBlock(EmptyMessage())
    } yield {
      val header = block.getBlockHeader.getRawData
      Ok(Json.toJson(BlockModel(
        number = header.number,
        size = block.toByteArray.length,
        hash = block.hash,
        timestamp = new DateTime(header.timestamp),
        txTrieRoot = Base58.encode58Check(header.txTrieRoot.toByteArray),
        parentHash = ByteUtil.toHexString(header.parentHash.toByteArray),
        witnessId = header.witnessId,
        witnessAddress = Base58.encode58Check(header.witnessAddress.toByteArray),
        nrOfTrx = block.transactions.size,
      )))
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def stats = Action.async { implicit request =>

    import repo._

    val weeks = DateTime.now.minusDays(3)

    if (request.getQueryString("info").contains("avg-block-size")) {

      val q2 = query
        .filter(_.timestamp > weeks)
        .groupBy(_.timestamp.trunc("hour"))
        .map {
          case (day, row) =>
            (day, row.map(_.size).avg)
        }
        .sortBy(_._1.asc)
      for {
        data <- readAsync(q2)
      } yield {
        Ok(Json.toJson(
          data.map(row => Json.obj(
            "timestamp" -> row._1,
            "value" -> row._2,
          ))
        ))
      }
    } else {
      Future.successful(NotFound)
    }
  }

  def syncBlocks = Action { req =>

    for {
      from <- req.getQueryString("from")
      to <- req.getQueryString("to")
    } {
      partialReader ! ImportRange(from.toLong, to.toLong)
    }

    Ok
  }
}
