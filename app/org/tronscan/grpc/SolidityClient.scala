package org.tronscan.grpc

import org.tron.api.api.{EmptyMessage, NumberMessage}
import org.tron.api.api.WalletGrpc.WalletStub
import org.tron.api.api.WalletSolidityGrpc.WalletSolidityStub
import org.tron.protos.Tron.Block
import org.tronscan.domain.BlockChain

import scala.concurrent.{ExecutionContext, Future}

trait SolidityClient {
  def client: WalletSolidityStub
}

class SolidityBlockChain(val client: WalletSolidityStub) extends BlockChain with SolidityClient {

  def genesisBlock: Future[Block] = {
    client.getBlockByNum(NumberMessage(0))
  }


  def headBlock: Future[Block] = {
    client.getNowBlock(EmptyMessage())
  }


  def getBlockByNum(number: Long)(implicit executionContext: ExecutionContext) = {
    client.getBlockByNum(NumberMessage(number)).map {
      case block if block.blockHeader.isDefined =>
        Some(block)
      case _ =>
        None
    }
  }
}
