package org
package tronscan.api

import io.circe.Json
import io.circe.syntax._
import javax.inject.Inject
import org.tron.common.repositories.StatsRepository

class StatsApi @Inject() (statsRepository: StatsRepository) extends BaseApi {

  import scala.concurrent.ExecutionContext.Implicits.global

  def overview = Action.async {

    for {
      totalTransaction <- statsRepository.totalTransactions.map(_.map(x => (x._1.getMillis, x._2)).toMap)
      avgBlockSize <- statsRepository.averageBlockSize.map(_.map(x => (x._1.getMillis, x._2)).toMap)
      totalBlockCount <- statsRepository.blocksCreated.map(_.map(x => (x._1.getMillis, x._2)).toMap)
      newAddressesSeen <- statsRepository.accountsCreated.map(_.map(x => (x._1.getMillis, x._2)).toMap)
    } yield {
      val days = totalTransaction.keys.toList.sortBy(x => x)
      Ok(Json.obj(
        "success" -> true.asJson,
        "data" -> days.map { day =>
          Json.obj(
            "totalTransaction" -> totalTransaction(day).asJson,
            "avgBlockTime" -> 3.asJson,
            "avgBlockSize" -> avgBlockSize(day).asJson,
            "totalBlockCount" -> totalBlockCount(day).asJson,
            "newAddressSeen" -> newAddressesSeen(day).asJson,
          )
        }.asJson
      ))
    }
  }

}
