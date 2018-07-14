package org
package tronscan.api

import io.circe.Json
import io.circe.syntax._
import javax.inject.Inject
import org.tron.common.repositories.StatsRepository
import org.tronscan.actions.StatsOverview
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import scala.concurrent.duration._

class StatsApi @Inject() (
  statsOverview: StatsOverview,
  statsRepository: StatsRepository,
  @NamedCache("redis") redisCache: CacheAsyncApi
) extends BaseApi {

  import scala.concurrent.ExecutionContext.Implicits.global

  def overview = Action.async {
    for {
      (totalTransaction, avgBlockSize, totalBlockCount, newAddressesSeen, blockchainSize) <- redisCache.getOrFuture(s"stats.overview", 1.hour)(statsOverview.execute)
    } yield {
      val days = totalTransaction.keys.toList.sortBy(x => x)
      Ok(Json.obj(
        "success" -> true.asJson,
        "data" -> days.map { day =>
          Json.obj(
            "date" -> day.asJson,
            "totalTransaction" -> totalTransaction(day).asJson,
            "avgBlockTime" -> 3.asJson, // TODO properly calculate
            "avgBlockSize" -> avgBlockSize(day).asJson,
            "totalBlockCount" -> totalBlockCount(day).asJson,
            "newAddressSeen" -> newAddressesSeen(day).asJson,
            "blockchainSize" -> blockchainSize(day).asJson,
          )
        }.asJson
      ))
    }
  }

}
