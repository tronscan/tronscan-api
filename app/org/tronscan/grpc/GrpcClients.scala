package org.tronscan.grpc

import io.grpc.ManagedChannel
import org.tron.api.api.{WalletGrpc, WalletSolidityGrpc}

case class GrpcClients(clients: List[ManagedChannel]) {

  private def random = {
    val r = new scala.util.Random
    r.nextInt(clients.size)
  }

  def full = WalletGrpc.stub(client)
  def solidity = WalletSolidityGrpc.stub(client)
  def fullClients = clients.map(WalletGrpc.stub)
  def solidityClients = clients.map(WalletSolidityGrpc.stub)
  def client = clients(random)
}
