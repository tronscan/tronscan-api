package org
package tronscan.api

import io.circe.Json
import io.circe.syntax._
import javax.inject.Inject
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.common.crypto.ECKey
import org.tron.common.utils.{Base58, ByteArray}
import org.tronscan.models.{TrxRequestModel, TrxRequestModelRepository}
import org.tronscan.service.TransactionBuilder
import play.api.inject.ConfigurationProvider
import play.api.libs.ws.WSClient
import play.api.mvc.InjectedController

import scala.concurrent.Future

class TestNetApi @Inject()(
  configurationProvider: ConfigurationProvider,
  transactionBuilder: TransactionBuilder,
  wallet: Wallet,
  trxRequestModelRepository: TrxRequestModelRepository,
  ws: WSClient) extends InjectedController {

  import scala.concurrent.ExecutionContext.Implicits.global

  val config = configurationProvider.get

  def verifyCode(code: String) = {

    val siteCode = config.get[String]("testnet.trx-distribution.captcha.sitekey")

    for {
      result <- ws
        .url("https://www.google.com/recaptcha/api/siteverify")
        .post(Map(
          "secret" -> Seq(siteCode),
          "response" -> Seq(code),
        ))
    } yield (result.json \ "success").as[Boolean]
  }

  /**
    * Request TRX
    * @return
    */
  def requestTrx = Action.async { req =>

    async {

      val to = (req.body.asJson.get \ "address").as[String]
      val captchaCode = (req.body.asJson.get \ "captchaCode").as[String]
      val ip = req.headers.get("X-Real-IP").getOrElse("")

      await(trxRequestModelRepository.findByRecentIp(ip)) match {
        case Some(_) =>
          Ok(Json.obj(
            "success" -> false.asJson,
            "code" -> "ALREADY_REQUESTED_IP".asJson,
            "message" -> s"Already requested TRX from IP recently".asJson,
          ))
        case None =>
          await(trxRequestModelRepository.findByAddress(to)) match {
            case None =>
              if (await(verifyCode(captchaCode))) {

                val fromAccount = config.get[String]("testnet.trx-distribution.pk")
                val amount = config.get[Long]("testnet.trx-distribution.amount")
                val fromAccountKey = ECKey.fromPrivate(ByteArray.fromHexString(fromAccount))

                val transfer = transactionBuilder.buildTrxTransfer(
                  fromAccountKey.getAddress,
                  to,
                  amount)

                await(for {
                  transactionWithRef <- transactionBuilder.setReference(transfer)
                  signedTransaction = transactionBuilder.sign(transactionWithRef, ByteArray.fromHexString(fromAccount))
                  result <- wallet.broadcastTransaction(signedTransaction)
                } yield {

                  if (result.result) {
                    trxRequestModelRepository.insertAsync(TrxRequestModel(
                      address = to,
                      ip = ip,
                    ))
                  }

                  Ok(Json.obj(
                    "success" -> result.result.asJson,
                    "amount" -> amount.asJson,
                    "code" -> result.code.toString.asJson,
                    "message" -> new String(result.message.toByteArray).toString.asJson,
                  ))
                })
              } else {
                Ok(Json.obj(
                  "success" -> false.asJson,
                  "code" -> "WRONG_CAPTCHA".asJson,
                  "message" -> "Wrong Captcha Code".asJson,
                ))
              }
            case Some(_) =>
              Ok(Json.obj(
                "success" -> false.asJson,
                "code" -> "ALREADY_REQUESTED_ADDRESS".asJson,
                "message" -> s"Already requested for address $to".asJson,
              ))
          }

      }

    }
  }
}
