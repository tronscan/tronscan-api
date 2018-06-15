package org.tronscan.slack

import java.util.Date

import akka.actor.ActorRef
import com.google.protobuf.ByteString
import javax.inject.{Inject, Named}
import org.tron.api.api.{EmptyMessage, WalletGrpc}
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import org.tron.common.crypto.ECKey
import org.tron.common.utils.{Base58, ByteArray}
import org.tron.protos.Tron.Account
import org.tronscan.Constants
import play.api.inject.ConfigurationProvider
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.mvc.InjectedController
import org.tronscan.Extensions._
import org.tronscan.grpc.{GrpcPool, WalletClient}
import org.tronscan.grpc.GrpcPool.Channel
import akka.pattern.ask

import scala.concurrent.duration._
import akka.util.Timeout
import dispatch.url
import org.ocpsoft.prettytime.PrettyTime

import scala.async.Async.async

class SlackApi @Inject() (
  configurationProvider: ConfigurationProvider,
  walletClient: WalletClient,
  wallet: Wallet,
  walletSolidity: WalletSolidity,
  @Named("grpc-pool") grpcPool: ActorRef,
  wsClient: WSClient) extends InjectedController {

  import scala.concurrent.ExecutionContext.Implicits.global

  val config = configurationProvider.get
  lazy val verificationCode = config.get[String]("slack.verificationCode")

  implicit val timeout = Timeout(2.seconds)


  def handleCommand() = Action.async { req =>

    async {

      val formData = req.body.asFormUrlEncoded.map(_.map(x => (x._1, x._2.mkString)))

      formData match {
        case Some(params) if params.get("token").contains(verificationCode) && params.get("command").isDefined =>
          val command = params("command").replace("dev-", "")
          val text = params("text")


          def respond(response: JsValue) = {
            wsClient
              .url(params("response_url"))
              .post(response)
          }


          command match {
            case "/account" =>
              for {
                account <- wallet.getAccount(Account(
                  address = ByteString.copyFrom(Base58.decode58Check(text))
                ))
                _ <- respond(Json.obj(
                    "text" -> s"""
                       |Name: ${account.accountName.decodeString}
                       |:trx: ${math.round(account.balance.toDouble / Constants.ONE_TRX)}
                       |:zap: ${math.round(account.frozen.map(_.frozenBalance).sum.toDouble / Constants.ONE_TRX)}
                       |<https://tronscan.org/#/address/${text}|View on Tronscan>
                    """.stripMargin
                  ))
              } yield ()
            case "/testtrx" =>

              val fromAccount = config.get[String]("testnet.trx-distribution.pk")
              val fromAccountKey = ECKey.fromPrivate(ByteArray.fromHexString(fromAccount))

              for {
                account <- wallet.getAccount(Account(
                  address = ByteString.copyFrom(fromAccountKey.getAddress)
                ))
                _ <- respond(Json.obj(
                  "text" -> s"""
                     |Remaining TRX: ${math.round(account.balance.toDouble / Constants.ONE_TRX)}
                    """.stripMargin
                ))
              } yield ()
            case "/height" =>
              for {
                block <- wallet.getNowBlock(EmptyMessage())
                solidityBlock <- walletSolidity.getNowBlock(EmptyMessage())
                _ <- respond(Json.obj(
                  "text" -> s"""
                     |Full: ${block.getBlockHeader.getRawData.number}
                     |Solidity: ${solidityBlock.getBlockHeader.getRawData.number}
                    """.stripMargin
                ))
              } yield ()

            case "/vote-round" =>
              for {
                client <- walletClient.full
                nextMaintenanceTime <- client.getNextMaintenanceTime(EmptyMessage()).map(_.num)
                currentTime <- client.getNowBlock(EmptyMessage()).map(_.getBlockHeader.getRawData.timestamp)
              } yield {

                val p = new PrettyTime()

                val nextRound = nextMaintenanceTime - currentTime

                respond(Json.obj(
                  "text" -> s"Next round in ${p.format(new Date(System.currentTimeMillis() + nextRound))}"
                ))
              }

            case "/test-grpc" =>

              val (ip, port) = text match {
                case hostname if hostname.contains(":") =>
                  val Array(ip, port) = hostname.split(":")
                  (ip, port.toInt)
                case hostname =>
                  (hostname, 50051)
              }

              (for {
                channel <- (grpcPool ? GrpcPool.RequestChannel(ip, port)).mapTo[Channel]
                client = WalletGrpc.stub(channel.channel)
                block <- client.getNowBlock(EmptyMessage())
                _ <- respond(Json.obj(
                  "text" -> s"""
                     |Block: ${block.getBlockHeader.getRawData.number}
                    """.stripMargin
                ))
              } yield ()).recover {
                case x =>
                  respond(Json.obj(
                  "text" -> s"Error while trying to connect with GRPC"
                ))
              }
          }

          Ok("")
        case _ =>
          Ok("")
      }
    }
  }


}
