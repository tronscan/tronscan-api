package org.tronscan.network

import java.util.concurrent.TimeUnit

import io.grpc.ManagedChannel
import org.tron.api.api.{WalletGrpc, WalletSolidityGrpc}

case class NodeChannel(ip: String, port: Int = 50051, channel: ManagedChannel) {
  lazy val full = WalletGrpc.stub(channel)
  lazy val solidity = WalletSolidityGrpc.stub(channel)
  def close() : Unit = {
    channel.shutdown()
    channel.awaitTermination(6, TimeUnit.SECONDS)
  }
}
