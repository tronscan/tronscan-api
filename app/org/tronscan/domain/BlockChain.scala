package org.tronscan.domain

import org.tron.protos.Tron.Block

import scala.concurrent.{ExecutionContext, Future}

trait BlockChain {

  def genesisBlock: Future[Block]
  def headBlock: Future[Block]
  def getBlockByNum(number: Long)(implicit executionContext: ExecutionContext): Future[Option[Block]]

}
