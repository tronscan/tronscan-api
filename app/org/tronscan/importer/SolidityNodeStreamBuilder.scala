package org.tronscan.importer

import akka.NotUsed
import akka.stream.scaladsl.Source
import javax.inject.Inject
import org.tron.api.api.{BlockLimit, NumberMessage}
import org.tron.protos.Tron.Block
import org.tronscan.grpc.{FullNodeClient, SolidityClient}

import scala.concurrent.{ExecutionContext, Future}

class SolidityNodeStreamBuilder @Inject()(client: SolidityClient) {

  def grpc = client.client

  /**
    * Reads the blocks with getBlockByNum
    */
  def readBlocks(from: Long, to: Long, parallel: Int = 12): Source[Block, NotUsed] = {
    Source(from to to)
      .mapAsync(parallel) { i => grpc.getBlockByNum(NumberMessage(i)) }
      .filter(_.blockHeader.isDefined)
  }
}
