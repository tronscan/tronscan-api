package org.tronscan.api

import javax.inject.{Inject, Singleton}
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import org.tron.api.api.{EmptyMessage, Node}
import org.tron.common.utils.{Base58, ByteArray}
import org.tron.protos.Tron.Account
import play.api.cache.{Cached, NamedCache}
import play.api.cache.redis.CacheAsyncApi
import play.api.libs.json.Json
import play.api.mvc.InjectedController
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{AccountModelRepository, IPGeoModel, VoteWitnessContractModelRepository, WitnessModelRepository}
import org.tronscan.service.GeoIPService
import org.tronscan.Extensions._
import org.tronscan.actions.RepresentativeListReader
import org.tronscan.protocol.AddressFormatter

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
          "address"             -> witness.address.toAddress,
          "name"                -> accounts.getOrElse(witness.address.toAddress, "").toString,
          "url"                 -> witness.url,
          "latestBlockNumber"   -> witness.latestBlockNum,
          "latestSlotNumber"    -> witness.latestSlotNum,
          "missedTotal"         -> witness.totalMissed,
          "producedTotal"       -> witness.totalProduced,
          "producedTrx"         -> witnessTrx.getOrElse(witness.address.toAddress, 0L).toLong,
          "votes"               -> witness.voteCount,
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
            "ip" -> ByteArray.toStr(node.getAddress.host.toByteArray),
            "country" -> geo.country,
            "city" -> geo.city,
            "lat" -> geo.lat,
            "lng" -> geo.lng,
          )
        }))
      }
    }
  }

}
