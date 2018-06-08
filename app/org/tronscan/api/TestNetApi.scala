package org
package tronscan.api

import io.circe.Json
import io.circe.syntax._
import javax.inject.Inject
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.common.crypto.ECKey
import org.tron.common.utils.{Base58, ByteArray}
import org.tronscan.service.TransactionBuilder
import play.api.inject.ConfigurationProvider
import play.api.libs.ws.WSClient
import play.api.mvc.InjectedController

import scala.concurrent.Future

class TestNetApi @Inject()(
  configurationProvider: ConfigurationProvider,
  transactionBuilder: TransactionBuilder,
  wallet: Wallet,
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

  def requestTrx = Action.async { req =>

    val to = (req.body.asJson.get \ "address").as[String]
    val captchaCode = (req.body.asJson.get \ "captchaCode").as[String]

    verifyCode(captchaCode).flatMap {
      case true =>

        val fromAccount = config.get[String]("testnet.trx-distribution.pk")
        val amount = config.get[Long]("testnet.trx-distribution.amount")
        val fromAccountKey = ECKey.fromPrivate(ByteArray.fromHexString(fromAccount))

        val transfer = transactionBuilder.buildTrxTransfer(
          fromAccountKey.getAddress,
          to,
          amount)

        for {
          transactionWithRef <- transactionBuilder.setReference(transfer)
          signedTransaction = transactionBuilder.sign(transactionWithRef, ByteArray.fromHexString(fromAccount))
          result <- wallet.broadcastTransaction(signedTransaction)
        } yield {
          Ok(Json.obj(
            "success" -> result.result.asJson,
            "amount" -> amount.asJson,
            "code" -> result.code.toString.asJson,
            "message" -> new String(result.message.toByteArray).toString.asJson,
          ))
        }

      case false =>
        Future.successful(Ok(Json.obj(
          "success" -> false.asJson,
          "code" -> "WRONG_CAPTCHA".asJson,
          "message" -> "Wrong Captcha Code".asJson,
        )))
    }
  }
}
