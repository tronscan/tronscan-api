package org.tronscan.grpc

import akka.actor.ActorRef
import akka.util
import javax.inject.{Inject, Named, Singleton}
import org.tron.api.api.{WalletExtensionGrpc, WalletGrpc, WalletSolidityGrpc}
import play.api.inject.ConfigurationProvider
import org.tronscan.grpc.GrpcPool.{Channel, RequestChannel}
import akka.pattern.ask

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class WalletClient @Inject() (
  @Named("grpc-pool") grpcPool: ActorRef,
  configurationProvider: ConfigurationProvider) {

  val config = configurationProvider.get

  def full = {
    implicit val timeout = util.Timeout(3.seconds)
    val ip = config.get[String]("fullnode.ip")
    val port = config.get[Int]("fullnode.port")
    (grpcPool ? RequestChannel(ip, port)).mapTo[Channel].map(c => WalletGrpc.stub(c.channel))
  }

  def solidity = {
    implicit val timeout = util.Timeout(3.seconds)
    val ip = config.get[String]("solidity.ip")
    val port = config.get[Int]("solidity.port")
    (grpcPool ? RequestChannel(ip, port)).mapTo[Channel].map(c => WalletSolidityGrpc.stub(c.channel))
  }

  def extension = {
    implicit val timeout = util.Timeout(3.seconds)
    val ip = config.get[String]("fullnode.ip")
    val port = config.get[Int]("fullnode.port")
    (grpcPool ? RequestChannel(ip, port)).mapTo[Channel].map(c => WalletExtensionGrpc.stub(c.channel))
  }

}
