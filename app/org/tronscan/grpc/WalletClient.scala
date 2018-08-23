package org.tronscan.grpc

import akka.actor.ActorRef
import akka.util
import javax.inject.{Inject, Named, Singleton}
import org.tron.api.api.{WalletGrpc, WalletSolidityGrpc}
import play.api.inject.ConfigurationProvider
import org.tronscan.grpc.GrpcPool.{Channel, RequestChannel}
import akka.pattern.ask
import akka.util.Timeout
import org.tron.api.api.WalletGrpc.WalletStub

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class WalletClient @Inject() (
   @Named("grpc-pool") grpcPool: ActorRef,
   @Named("grpc-balancer") grpcBalancer: ActorRef,
  configurationProvider: ConfigurationProvider) {

  val config = configurationProvider.get

  def full = {
    implicit val timeout = util.Timeout(3.seconds)
    val ip = config.get[String]("fullnode.ip")
    val port = config.get[Int]("fullnode.port")
    (grpcPool ? RequestChannel(ip, port)).mapTo[Channel].map(c => WalletGrpc.stub(c.channel))
  }

  def fullRequest[A](request: WalletStub => Future[A]) = {
    implicit val timeout = Timeout(9.seconds)
    (grpcBalancer ? GrpcRequest(request)).mapTo[GrpcResponse].map(_.response.asInstanceOf[A])
  }

  def solidity = {
    implicit val timeout = util.Timeout(3.seconds)
    val ip = config.get[String]("solidity.ip")
    val port = config.get[Int]("solidity.port")
    (grpcPool ? RequestChannel(ip, port)).mapTo[Channel].map(c => WalletSolidityGrpc.stub(c.channel))
  }

}
