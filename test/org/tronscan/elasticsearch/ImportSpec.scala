package org.tronscan.elasticsearch


import com.sksamuel.elastic4s.http.ElasticClient
import com.sksamuel.elastic4s.http.ElasticDsl._
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.joda.time.DateTime
import org.specs2.mutable._
import play.api.Logger

class ImportSpec extends Specification {

  "Elastic Search" should {

    "Import" in {

      val client = ElasticClient.fromRestClient(RestClient.builder(new HttpHost("localhost", 9200, "http")).build())
//      val localNode = LocalNode("mycluster", "/tmp/esdata")
//      val client = localNode.client(shutdownNodeOnClose = true)

      Logger.info("DELETING INDEX")
      client.execute { deleteIndex("blockchain") }.await

      Logger.info("CREATING INDEX")
      client.execute {
        createIndex("blockchain").mappings(
          mapping("transaction").fields(
            longField("block").index(true),
            textField("hash").index(true),
            dateField("date_created"),
            booleanField("confirmed"),
            objectField("contract_data"),
            intField("contract_type"),
            textField("owner_address"),
            textField("to_address"),
            textField("data")
          )
        )
      }.await

      Logger.info("CREATING TRANSACTIONS")

      val transactions = for (i <- 1 to 2500000) yield {
        indexInto("blockchain" / "transaction").doc(Json.obj(
          "block" -> i.asJson,
          "hash" -> "f54e1d904eb1744f3309f65b2940f56917426b91fc58d51a6096cdb970154044".asJson,
          "date_created" -> DateTime.now.asJson,
          "contract_type" -> 4.asJson,
          "confirmed" -> true.asJson,
          "contract_data" -> Json.obj(
            "to" -> "TXJ1F79y9NaEQ2bVy7Db4VCe4ndM5QSYvS".asJson,
            "from" -> "TQREAvGre73gkS2TUyNhbxcP2dpoe2fXPD".asJson,
            "amount" -> 2000000.asJson
          ),
          "owner_address" -> "TQREAvGre73gkS2TUyNhbxcP2dpoe2fXPD".asJson,
          "to_address" -> "TV3NmH1enpu4X5Hur8Z16eCyNymTqKXQDP".asJson,
          "data" -> "Test Data!".asJson
        ).toString())
      }

      Logger.info("POSTING TO ES")

      transactions.grouped(100000).foreach { bulks =>
        Logger.info("POSTING BATCH")
        val response = client.execute { bulk(bulks) }.await
      }

//      println(response)

      ok
    }
  }
}
