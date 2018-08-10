package org.tronscan.tools.nodetester

import org.tron.api.api.WalletGrpc.WalletStub
import org.tron.api.api.WalletSolidityGrpc.WalletSolidityStub
import org.tron.api.api.{EmptyMessage, NumberMessage}
import org.tron.protos.Tron.Block

import scala.concurrent.Future

class Tester {

}

sealed trait NodeTest {
//  def ip: String
//  def port: Int

  def blockByNum(num: Long): Future[Block]
  def latestBlock: Future[Block]
  def name: String

}
case class SolidityTest(client: WalletSolidityStub) extends NodeTest {
  val name = "Solidity"
  def latestBlock = client.getNowBlock(EmptyMessage())
  def blockByNum(num: Long) = client.getBlockByNum(NumberMessage(num))
}

case class FullNodeTest(client: WalletStub) extends NodeTest {
  val name = "Full"
  def latestBlock = client.getNowBlock(EmptyMessage())
  def blockByNum(num: Long) = client.getBlockByNum(NumberMessage(num))
}