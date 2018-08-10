package org.tronscan.api

import io.swagger.annotations._
import javax.inject.Inject
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{BlockModel, FundsModel, FundsModelRepository}
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.libs.json.Json

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
     } yield {
       Ok(Json.toJson(
         funds.map(row => Json.obj(
           "address" -> row._1,
           "balance" -> row._2,
           "power"   -> row._3
         ))
       ))
     }
  }
}
