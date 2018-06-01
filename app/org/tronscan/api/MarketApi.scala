package org
package tronscan.api

import io.circe.generic.auto._
import io.circe.syntax._
import io.swagger.annotations.Api
import javax.inject.Inject
import org.jsoup.Jsoup
import play.api.cache.Cached
import play.api.libs.ws.WSClient
import play.api.mvc.InjectedController

import scala.async.Async._
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

case class Exchange(
  rank: Int,
  name: String,
  pair: String,
  link: String,
  volume: Double,
  volumePercentage: Double,
  volumeNative: Double,
  price: Double
)

@Api(
  value = "Market",
  produces = "application/json")
class MarketApi @Inject() (ws: WSClient, cached: Cached) extends InjectedController {

  def markets = cached.status(x => "markets", 200, 1.minute) {
    Action.async {

      async {

        val body = await(ws
          .url("https://coinmarketcap.com/currencies/tron/")
          .get()).body

        val doc = Jsoup.parse(body)

        val marketTable = doc.getElementById("markets-table")

        val rows = marketTable.select("tbody > tr")

        val data = rows.asScala.map { row =>
          Exchange(
            rank = row.child(0).text().toInt,
            name = row.child(1).attr("data-sort"),
            pair = row.child(2).attr("data-sort"),
            link = row.child(2).child(0).attr("href"),
            volume = row.child(3).select("span.volume").first().attr("data-usd").toDouble,
            volumeNative = row.child(3).select("span.volume").first().attr("data-native").toDouble,
            price = row.child(4).select("span.price").first().attr("data-usd").toDouble,
            volumePercentage = row.child(5).attr("data-sort").toDouble,
          )
        }

        Ok(data.asJson)
      }
    }
  }

}
