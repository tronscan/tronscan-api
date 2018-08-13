package org.tronscan.api

import io.swagger.annotations._
import javax.inject.Inject
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{BlockModel, FundsModel, FundsModelRepository}
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.ExecutionContext.Implicits.global

@Api(
  value = "funds",
  produces = "application/json")
class FundsApi @Inject() (
  repo: FundsModelRepository,
  walletClient: WalletClient,
  @NamedCache("redis") redisCache: CacheAsyncApi) extends BaseApi {

  @ApiOperation(
    value = "List blocks",
    response = classOf[BlockModel],
    responseContainer = "List")
  def findAll = Action.async { implicit request =>
     for {
       funds <- repo.findAll()
       balanceSum <- repo.getFundsBalanceSum()
     } yield {
       val data: Vector[JsObject] = funds.map(row => Json.obj(
         "id"      -> row._1,
         "address" -> row._2,
         "balance" -> row._3,
         "power"   -> row._4
       ))
       var response = Json.obj("totalBalance" -> balanceSum)
       response ++= Json.obj("data" -> data)
       Ok(Json.toJson(response))
     }
  }
}
