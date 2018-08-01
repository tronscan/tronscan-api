package org.tronscan.api

import javax.inject.{Inject, Singleton}
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import org.tron.api.api.{EmptyMessage, Node}
import org.tron.common.utils.ByteArray
import org.tronscan.Extensions._
import org.tronscan.actions.RepresentativeListReader
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{AccountModelRepository, IPGeoModel, VoteWitnessContractModelRepository, WitnessModelRepository}
import org.tronscan.service.GeoIPService
import play.api.cache.redis.CacheAsyncApi
import play.api.cache.{Cached, NamedCache}
import play.api.libs.json.Json
import play.api.mvc.InjectedController

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

case class GeoNode(node: Node, iPGeoModel: IPGeoModel)

@Singleton
class WitnessApi @Inject()(
  cached: Cached,
  repo: VoteWitnessContractModelRepository,
  witnessModelRepository: WitnessModelRepository,
  geoIPService: GeoIPService,
  walletClient: WalletClient,
  accountModelRepository: AccountModelRepository,
  walletSolidity: WalletSolidity,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  representativeListReader: RepresentativeListReader) extends InjectedController {

  def findAll = Action.async { implicit request =>

    for {
      (witnesses, accounts, witnessTrx) <- redisCache.getOrFuture("witness.list", 1.second) {
        representativeListReader.execute
      }
    } yield {
      Ok(Json.toJson(witnesses.map { witness =>
        Json.obj(
          "address"             -> witness.address.encodeAddress,
          "name"                -> accounts.getOrElse(witness.address.encodeAddress, "").toString,
          "url"                 -> witness.url,
          "producer"            -> witness.isJobs,
          "latestBlockNumber"   -> witness.latestBlockNum,
          "latestSlotNumber"    -> witness.latestSlotNum,
          "missedTotal"         -> witness.totalMissed,
          "producedTotal"       -> witness.totalProduced,
          "producedTrx"         -> witnessTrx.getOrElse(witness.address.encodeAddress, 0L).toLong,
          "votes"               -> witness.voteCount,
        )
      }))
    }
  }

  def maintenanceStatistic = Action.async { implicit request =>
    for {
      (witnesses, blocks, total) <- redisCache.getOrFuture("witness.statistic", 1.second) {
        representativeListReader.maintenanceStatistic
      }
    } yield {
      Ok(Json.toJson(blocks.filter{block =>
        val list = witnesses.filter(_.address.encodeAddress == block._1)
        list.size > 0 && list.head.isJobs
      }.map { block =>
        Json.obj(
          "address"          -> block._1,
          "name"             -> block._2,
          "url"              -> block._3,
          "blockProduced"    -> block._4,
          "total"            -> total.head,
          "percentage"       -> block._4 * 1.0 / total.head,
        )
      }))
    }
  }

  def searchForGeo(node: Node) = {
    val ip = ByteArray.toStr(node.getAddress.host.toByteArray)
    geoIPService.findForIp(ip).map { geo =>
      GeoNode(node, geo)
    }
  }

  /**
    * Retrieve the node map
    *
    * @return
    */
  def getNodeMap = cached.status(x => "nodemap", 200, 20.seconds) {
    Action.async {

      for {
        wallet <- walletClient.full
        nodes <- wallet.listNodes(EmptyMessage()).map(_.nodes)
        geoNodes <- Future.sequence(nodes.map(searchForGeo))
      } yield {
        Ok(Json.toJson(geoNodes.map { case GeoNode(node, geo) =>
          Json.obj(
            "ip"      -> ByteArray.toStr(node.getAddress.host.toByteArray),
            "country" -> geo.country,
            "city"    -> geo.city,
            "lat"     -> geo.lat,
            "lng"     -> geo.lng,
          )
        }))
      }
    }
  }

}
