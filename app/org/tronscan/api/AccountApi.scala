package org
package tronscan.api

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import com.beachape.metascraper.Messages.ScrapeUrl
import com.beachape.metascraper.Scraper
import com.google.protobuf.ByteString
import dispatch.Http
import io.circe.generic.auto._
import io.circe.syntax._
import io.lemonlabs.uri.Url
import io.swagger.annotations._
import javax.inject.Inject
import org.apache.commons.lang3.exception.ExceptionUtils
import org.joda.time.DateTime
import org.tron.common.crypto.ECKey
import org.tron.common.utils.{Base58, ByteArray}
import org.tron.protos.Tron.Account
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.cache.Cached
import play.api.inject.ConfigurationProvider
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.InjectedController
import org.tronscan.Constants
import org.tronscan.db.PgProfile.api._
import org.tronscan.grpc.WalletClient
import org.tronscan.models._
import org.tronscan.Extensions._
import org.tronscan.service.SRService

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

@Api(
  value = "Accounts",
  produces = "application/json")
class AccountApi @Inject()(
  repo: AccountModelRepository,
  cached: Cached,
  system: ActorSystem,
  transferRepository: TransferModelRepository,
  srRepository: SuperRepresentativeModelRepository,
  blockModelRepository: BlockModelRepository,
  addressBalanceModelRepository: AddressBalanceModelRepository,
  configurationProvider: ConfigurationProvider,
  walletClient: WalletClient,
  witnessRepository: WitnessModelRepository,
  srService: SRService) extends InjectedController {

  val key = configurationProvider.get.get[String]("play.http.secret.key")

  @ApiResponses(Array(
    new ApiResponse(
      code = 200,
      message = "Accounts found",
      response = classOf[AccountModel])
  ))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "address",
      value = "Account Address",
      required = false,
      dataType = "string",
      paramType = "query"),
    new ApiImplicitParam(
      name = "name",
      value = "Account Name",
      required = false,
      dataType = "string",
      paramType = "query"),
  ))
  @ApiOperation(
    value = "List Accounts",
    response = classOf[AccountModel],
    responseContainer = "List")
  def findAll() = Action.async { implicit request =>

    import repo._

    var q = sortWithRequest() {
      case (t, "address") => t.address
      case (t, "name") => t.name
      case (t, "balance") => t.balance
    }

    q = q andThen filterRequest {
      case (query, ("address", value)) =>
        query.filter(_.address === value)
      case (query, ("name", value)) =>
        query.filter(_.name === value)
      case (query, _) =>
        query
    }

    for {
      total <- readTotals(q)
      accounts <- readQuery(q andThen limitWithRequest())
    } yield {
      Ok(Json.obj(
        "total" -> total,
        "data" -> accounts.asJson,
      ))
    }
  }

  @ApiOperation(
    value = "Find account by address",
    response = classOf[AccountModel])
  def findByAddress(address: String) = Action.async {

    for {
      wallet <- walletClient.full

      account = Account(
        address = address.decodeAddress
      )

      // Run in parallel
      accountF = wallet.getAccount(account)
      accountBandwidthF = wallet.getAccountNet(account)
      witnessF = witnessRepository.findByAddress(address)

      account <- accountF
      accountBandwidth <- accountBandwidthF
      witness <- witnessF

      accountBandwidthCapsule = AccountBandwidthCapsule(accountBandwidth)
    } yield {

      val balances = List(
        Json.obj(
          "name" -> "TRX",
          "balance" -> account.balance.toDouble / Constants.ONE_TRX)
      ) ++ account.asset.map {
        case (name, balance) =>
          Json.obj(
            "name" -> name,
            "balance" -> balance)
      }

      Ok(Json.obj(
        "representative" -> Json.obj(
          "enabled" -> witness.isDefined,
          "lastWithDrawTime" -> account.latestWithdrawTime,
          "allowance" -> account.allowance,
          "url" -> witness.map(_.url),
        ),
        "name" -> new String(account.accountName.toByteArray).toString,
        "address" -> address,
        "bandwidth" -> Json.obj(
          "freeNetUsed" -> accountBandwidth.freeNetUsed,
          "freeNetLimit" -> accountBandwidth.freeNetLimit,
          "freeNetRemaining" -> accountBandwidthCapsule.freeNetRemaining,
          "freeNetPercentage" -> accountBandwidthCapsule.freeNetRemainingPercentage,
          "netUsed" -> accountBandwidth.netUsed,
          "netLimit" -> accountBandwidth.netLimit,
          "netRemaining" -> accountBandwidthCapsule.netRemaining,
          "netPercentage" -> accountBandwidthCapsule.netRemainingPercentage,
          //          "assetNetUsed" -> accountBandwidth.assetNetUsed,
          //          "assetNetLimit" -> accountBandwidth.assetNetLimit,
          "assets" -> accountBandwidthCapsule.assets,
        ),
        "balances" -> Json.toJson(balances),
        "balance" -> account.balance,
        "tokenBalances" -> Json.toJson(balances),
        "frozen" -> Json.obj(
          "total" -> account.frozen.map(_.frozenBalance).sum,
          "balances" -> account.frozen.map { balance =>
            Json.obj(
              "amount" -> balance.frozenBalance,
              "expires" -> balance.expireTime,
            )
          }
        )
      ))
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def getAddressBalance(address: String) = Action.async {
    for {
      wallet <- walletClient.full
      account <- wallet.getAccount(Account(
        address = address.decodeAddress
      ))
    } yield {

      val balances = List(
        Json.obj(
          "name" -> "TRX",
          "balance" -> account.balance / Constants.ONE_TRX)
      ) ++ account.asset.map {
        case (name, balance) =>
          Json.obj(
            "name" -> name,
            "balance" -> balance)
      }

      Ok(Json.obj(
        "allowance" -> account.allowance,
        "entropy" -> account.netUsage,
        "balances" -> Json.toJson(balances),
        "frozen" -> Json.obj(
          "total" -> account.frozen.map(_.frozenBalance).sum,
          "balances" -> account.frozen.map { balance =>
            Json.obj(
              "amount" -> balance.frozenBalance,
              "expires" -> balance.expireTime,
            )
          }
        )
      ))
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def getVotes(address: String) = Action.async {
    for {
      wallet <- walletClient.full
      account <- wallet.getAccount(Account(
        address = ByteString.copyFrom(Base58.decode58Check(address))
      ))
    } yield {

      Ok(Json.obj(
        "votes" -> account.votes.map(vote => (Base58.encode58Check(vote.voteAddress.toByteArray), vote.voteCount)).toMap.asJson
      ))
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def getSr(address: String) = Action.async {
    for {
      sr <- srRepository.findByAddress(address)
    } yield {
      Ok(Json.toJson(sr))
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def getSrPages(address: String) = Action.async { request =>
    async {
      val language = request.getQueryString("lang").getOrElse("en")

      await(srRepository.findByAddress(address)) match {
        case Some(sr) if sr.githubLink.isDefined =>
          val pages = await(srService.getPages(sr.githubLink.get, language))
          val response: JsObject = pages.asJson
          Ok(Json.toJson(response))
        case _ =>
          NotFound
      }
    }
  }

  @ApiOperation(
    value = "",
    hidden = true)
  def updateSr(address: String) = Action.async { req =>

    (for {
      json <- req.body.asJson
      model <- json.asOpt[SuperRepresentativeModel]
      keyHeader <- req.headers.get("X-Key")
      key <- JwtJson.decodeJson(keyHeader, key, Seq(JwtAlgorithm.HS256)).toOption.map(x => (x \ "address").as[String])
      if address == key
    } yield {
      for {
        _ <- srRepository.saveAsync(model.copy(address = address))
      } yield {
        Ok(Json.obj(
          "data" -> true,
        ))
      }
    }).getOrElse(Future.successful(BadRequest))
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def getStats(address: String) = Action.async {
    for {
      transactionCount <- transferRepository.countByAddress(address)
      toCount <- transferRepository.countToAddress(address)
      fromCount <- transferRepository.countFromAddress(address)
    } yield {
      Ok(Json.obj(
        "transactions" -> transactionCount,
        "transactions_out" -> fromCount,
        "transactions_in" -> toCount,
      ))
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def resync = Action.async { req =>

    throw new Exception("DISABLED")

    val decider: Supervision.Decider = {
      case exc =>
        println("SOLIDITY STREAM ERROR", exc, ExceptionUtils.getStackTrace(exc))
        Supervision.Resume
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(system)
        .withSupervisionStrategy(decider))(system)

    async {

      val accounts = await(repo.findAll).toList

      val source = Source(accounts)
        .mapAsync(8) { existingAccount =>
          async {

            val walletSolidity = await(walletClient.full)

            val account = await(walletSolidity.getAccount(Account(
              address = ByteString.copyFrom(Base58.decode58Check(existingAccount.address))
            )))

            if (account != null) {

              val accountModel = AccountModel(
                address = existingAccount.address,
                name = new String(account.accountName.toByteArray),
                balance = account.balance,
                power = account.frozen.map(_.frozenBalance).sum,
                tokenBalances = Json.toJson(account.asset),
                dateUpdated = DateTime.now,
              )

              List(repo.buildInsertOrUpdate(accountModel)) ++ addressBalanceModelRepository.buildUpdateBalance(accountModel)
            } else {
              List.empty
            }

          }
        }
        .flatMapConcat(queries => Source(queries))
        .groupedWithin(150, 10.seconds)
        .mapAsync(1) { queries =>
          blockModelRepository.executeQueries(queries)
        }
        .toMat(Sink.ignore)(Keep.right)
        .run

      await(source)

      Ok("Done")
    }
  }

  @ApiOperation(
    value = "",
    hidden = true)
  def sync(address: String) = Action.async { req =>

    async {

        val wallet = await(walletClient.full)

        val account = await(wallet.getAccount(Account(
          address = address.decodeAddress,
        )))

        if (account != null) {

          val accountModel = AccountModel(
            address = address,
            name = new String(account.accountName.toByteArray),
            balance = account.balance,
            power = account.frozen.map(_.frozenBalance).sum,
            tokenBalances = Json.toJson(account.asset),
            dateUpdated = DateTime.now,
          )

          await(repo.insertOrUpdate(accountModel))
          await(addressBalanceModelRepository.updateBalance(accountModel))
        }

      Ok("Done")
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def getInfo(address: String) = cached.status(x => "address.info." + address, 200, 1.hour) {

    try {
      Action.async {

        for {
          witness <- witnessRepository.findByAddress(address)
          result <- async {

            witness.map(_.url) match {
              case Some(witnessUrl) =>

                val url = Url.parse(witnessUrl)

                url.hostOption.map(_.value) match {
                  case Some("twitter.com") =>

                    val modifiedUrl = url.withScheme("https")

                    println("GOT TWITTER, scraping", modifiedUrl.toAbsoluteUrl.toStringRaw)
                    val scraper = new Scraper(Http, Seq(modifiedUrl.schemeOption.get))

                    val image = await(scraper
                      .fetch(ScrapeUrl(modifiedUrl.toAbsoluteUrl.toStringRaw))
                      .map(scrapedData => scrapedData.mainImageUrl))

                    Ok(Json.obj(
                      "success" -> true,
                      "image" -> image
                    ))
                  case _ =>
                    val scraper = new Scraper(Http, Seq(url.schemeOption.get))
                    val image = await(scraper
                      .fetch(ScrapeUrl(url.toAbsoluteUrl.toStringRaw))
                      .map(scrapedData => scrapedData.mainImageUrl))

                    Ok(Json.obj(
                      "success" -> true,
                      "image" -> image,
                    ))
                }

              case _ =>
                Ok(Json.obj(
                  "success" -> false,
                  "reason" -> "Account does not contain an URL"
                ))
            }
          }
        } yield result
      }
    } catch {
      case _: Exception =>
        Ok(Json.obj(
          "success" -> false,
          "reason" -> "Could not retrieve file"
        ))
    }
  }

  @ApiOperation(
    value = "",
    hidden = true
  )
  def richList = cached.status(x => "richtlist", 200, 10.minutes) {
    Action.async {

      for {
        list <- Future.sequence(for (i <- 2 to 17) yield {
          val y = math.pow(10, i).toLong
          repo.getBetweenBalance(y, y * 10L).map(x => (
            x._1,
            x._2.toDouble / Constants.ONE_TRX.toDouble,
            y.toDouble / Constants.ONE_TRX.toDouble,
            (y * 10).toDouble / Constants.ONE_TRX.toDouble
          ))
        })
        (totalAccounts, totalBalance) <- repo.getTotals()
      } yield {
        val result = list.map {
          case (account, balance, start, stop) =>
            Json.obj(
              "accounts" -> account,
              "balance" -> balance,
              "from" -> start,
              "to" -> stop)
        }.toList

        Ok(Json.obj(
          "total" -> Json.obj(
            "accounts" -> totalAccounts,
            "coins" -> (totalBalance.toDouble / Constants.ONE_TRX.toDouble),
          ),
          "data" -> result
        ))
      }
    }
  }

  def create = Action {

    val ecKey = new ECKey()
    val priKey = ecKey.getPrivKeyBytes
    val address = ecKey.getAddress
    val addressStr = ByteString.copyFrom(address).encodeAddress
    val priKeyStr = ByteArray.toHexString(priKey)

    Ok(Json.obj(
      "key" -> priKeyStr,
      "address" -> addressStr
    ))
  }
}
