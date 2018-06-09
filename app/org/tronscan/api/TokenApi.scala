package org
package tronscan.api

import com.google.protobuf.ByteString
import io.circe.Json
import javax.inject.Inject
import org.joda.time.DateTime
import play.api.mvc.InjectedController
import org.tronscan.db.PgProfile.api._
import org.tronscan.models._
import io.circe.syntax._
import io.circe.generic.auto._
import org.tron.api.api.BytesMessage
import org.tronscan.grpc.WalletClient

import scala.concurrent.ExecutionContext.Implicits.global

class TokenApi @Inject()(
    repo: AssetIssueContractModelRepository,
    transferRepository: TransferModelRepository,
    addressBalanceModelRepository: AddressBalanceModelRepository,
    walletClient: WalletClient,
    participateAssetIssueModelRepository: ParticipateAssetIssueModelRepository) extends InjectedController {

  def findAll() = Action.async { implicit request =>

    import repo._

    var q = sortWithRequest() {
      case (t, "name") => t.name
      case (t, "date_start") => t.startTime
      case (t, "date_end") => t.endTime
      case (t, "supply") => t.totalSupply
    }

    q = q andThen filterRequest {
      case (query, ("name", value)) =>
        if (value.endsWith("%") || value.startsWith("%")) {
          query.filter(_.name.toLowerCase like value.toLowerCase)
        } else {
          query.filter(_.name === value)
        }
      case (query, ("date_start", value)) =>
        query.filter(_.startTime >= DateTime.parse(value))
      case (query, ("date_end", value)) =>
        query.filter(_.endTime <= DateTime.parse(value))
      case (query, ("status", "ico")) =>
        val now = DateTime.now
        query.filter(x => x.endTime >= now && x.startTime <= now)
      case (query, ("owner", value)) =>
        query.filter(x => x.ownerAddress === value)
      case (query, _) =>
        query
    }

    for {
      total <- readTotals(q)
      items <- readQuery(q andThen limitWithRequest() andThen withParticipation())
    } yield {

      Ok(Json.obj(
        "total" -> total.asJson,
        "data" -> items.map { case (asset, account) =>

          val frozenSupply = asset.frozenSupply
          val totalSupply = asset.totalSupply.toDouble

          val availableSupply = totalSupply - frozenSupply
          val availableTokens = account.tokenBalances.hcursor.downField(asset.name).as[Double].getOrElse(0D)

          val issuedTokens = availableSupply - availableTokens
          val issuedPercentage = (issuedTokens / availableSupply) * 100

          val remainingTokens = totalSupply - frozenSupply - issuedTokens
          val percentage = (remainingTokens / availableSupply) * 100

          val frozenSupplyPercentage = (frozenSupply / totalSupply) * 100

          asset.asJson.deepMerge(Json.obj(
            "price" -> asset.price.asJson,

            "issued" -> issuedTokens.asJson,
            "issuedPercentage" -> issuedPercentage.asJson,

            "available" -> availableTokens.asJson,
            "availableSupply" -> availableSupply.asJson,

            "remaining" -> remainingTokens.asJson,
            "remainingPercentage" -> percentage.asJson,
            "percentage" -> percentage.asJson,

            "frozen" -> frozenSupply.asJson,
            "frozenPercentage" -> frozenSupplyPercentage.asJson,
          ))
        }.asJson
      ))
    }
  }

  def findByName(name: String) = Action.async {
    for {
      token <- repo.findByName(name).map(_.get)
      totalTransactions <- transferRepository.countTokenTransfers(token.name)
      tokenHolders <- addressBalanceModelRepository.countTokenHolders(token.name)
    } yield {
      Ok(token.asJson.deepMerge(Json.obj(
        "totalTransactions" -> totalTransactions.asJson,
        "nrOfTokenHolders" -> tokenHolders.asJson,
      )))
    }
  }

  def getAccounts(name: String) = Action.async { implicit request =>

    import addressBalanceModelRepository._

    var q = sortWithRequest() {
      case (t, "address") => t.address
      case (t, "balance") => t.balance
    }

    q = q andThen { q1 => q1.filter(_.token === name) }

    for {
      total <- readTotals(q)
      items <- readQuery(q andThen limitWithRequest())
    } yield {
      Ok(Json.obj(
        "total" -> total.asJson,
        "data" -> items.asJson,
      ))
    }
  }
}
