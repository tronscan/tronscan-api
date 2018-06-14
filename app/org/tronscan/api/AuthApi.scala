package org.tronscan.api

import java.util
import java.util.Arrays

import com.google.protobuf.ByteString
import io.swagger.annotations.Api
import javax.inject.Inject
import org.tron.common.crypto.{ECKey, Hash}
import org.tron.common.utils.{Base58, ByteArray, Crypto, Sha256Hash}
import org.tron.protos.Tron.Transaction
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.inject.ConfigurationProvider
import play.api.libs.json.Json
import play.api.mvc.InjectedController
import org.tronscan.Extensions._


class AuthApi @Inject() (configurationProvider: ConfigurationProvider) extends InjectedController {

  val key = configurationProvider.get.get[String]("play.http.secret.key")

  /**
    * Token Request
    */
  def requestToken = Action { req =>

    val jsonData = (req.body.asJson.get \ "transaction").as[String]

    val transaction = Transaction.parseFrom(ByteArray.fromHexString(jsonData))
    val rawHash = transaction.hashBytes

    val signatureAddress = ECKey.signatureToAddress(rawHash, Crypto.getBase64FromByteString(transaction.signature.head))

    val transferContract = org.tron.protos.Contract.TransferContract.parseFrom(transaction.getRawData.contract.head.getParameter.value.toByteArray)
    val transactionAddress = transferContract.ownerAddress.toByteArray

    val addressEncoded = Base58.encode58Check(signatureAddress)

    val claim = Json.obj(
      "address" -> addressEncoded
    )
    val token = JwtJson.encode(claim, key, JwtAlgorithm.HS256)

    if (util.Arrays.equals(transactionAddress, signatureAddress)) {
      Ok(Json.obj(
        "key" -> token,
      ))
    } else {
      BadRequest
    }
  }
}
