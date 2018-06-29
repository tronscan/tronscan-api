package org.tronscan.importer

import akka.NotUsed
import akka.stream.scaladsl.Source
import javax.inject.Inject
import org.tron.api.api.{BlockLimit, NumberMessage}
import org.tron.protos.Tron.Block
import org.tronscan.grpc.FullNodeClient

import scala.concurrent.{ExecutionContext, Future}

class FullNodeStreamBuilder @Inject() (client: FullNodeClient) {

  def grpc = client.client

  /**
    * Reads the blocks with getBlockByNum
    */
  def readBlocks(from: Long, to: Long, parallel: Int = 12): Source[Block, NotUsed] = {
    Source(from to to)
      .mapAsync(parallel) { i => grpc.getBlockByNum(NumberMessage(i)) }
      .filter(_.blockHeader.isDefined)
  }

  /**
    * Reads all the blocks using batch calls
    */
  def readBlocksBatched(from: Long, to: Long, batchSize: Int = 50)(implicit executionContext: ExecutionContext): Source[Block, NotUsed] = {

    Source.unfoldAsync(from) { prev =>

      if (prev < to) {

        val toBlock = if (prev + batchSize > to) to else prev + batchSize

        grpc
          .getBlockByLimitNext(BlockLimit(prev, toBlock))
          .map { blocks =>
            Some((toBlock, blocks.block))
          }
      } else {
        Future.successful(None)
      }
    }
    .flatMapConcat(x => Source(x.toList))
    .filter(_.blockHeader.isDefined)
  }
}
