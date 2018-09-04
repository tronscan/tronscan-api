package org.tronscan.importer

import java.util.UUID

import akka.NotUsed
import akka.event.EventStream
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.tron.api.api.WalletGrpc.WalletStub
import org.tron.api.api.WalletSolidityGrpc.WalletSolidityStub
import org.tron.api.api.{BlockLimit, NumberMessage}
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AssetIssueContract, ParticipateAssetIssueContract, TransferAssetContract, TransferContract, VoteWitnessContract, WitnessCreateContract}
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.domain.Events._
import org.tronscan.grpc.WalletClient
import org.tronscan.importer.StreamTypes.ContractFlow
import org.tronscan.models._
import org.tronscan.utils.ModelUtils
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class BlockChainStreamBuilder {

  /**
    * Reads the blocks with getBlockByNum
    */
  def readSolidityBlocks(from: Long, to: Long, parallel: Int = 36)(client: WalletSolidityStub): Source[Block, NotUsed] = {
    Source(from to to)
      .mapAsync(parallel) { i => client.getBlockByNum(NumberMessage(i)) }
      .filter(_.blockHeader.isDefined)
  }

  /**
    * Reads the blocks with getBlockByNum
    */
  def readFullNodeBlocks(from: Long, to: Long, parallel: Int = 36)(client: WalletStub): Source[Block, NotUsed] = {
    Source(from to to)
      .mapAsync(parallel) { i => client.getBlockByNum(NumberMessage(i)) }
      .filter(_.blockHeader.isDefined)
  }

  /**
    * Reads all the blocks using batch calls
    */
  def readFullNodeBlocksBatched(from: Long, to: Long, batchSize: Int = 50)(client: WalletClient)(implicit executionContext: ExecutionContext): Source[Block, NotUsed] = {
    Source.unfold(from) { fromBlock =>
      if (fromBlock < to) {

        val nextBlock = fromBlock + batchSize
        val toBlock = if (nextBlock <= to) nextBlock else to
        Some((toBlock + 1, (fromBlock, toBlock)))

      } else {
        None
      }
    }
    .mapAsync(1) { case (fromBlock, toBlock) =>
      val id = UUID.randomUUID()
      val range = BlockLimit(fromBlock, toBlock + 1)
      client.fullRequest(_.getBlockByLimitNext(range)).map { blocks =>
        val bs = blocks.block.sortBy(_.getBlockHeader.getRawData.number)
//        if (range.startNum != bs.head.getBlockHeader.getRawData.number) {
//          Logger.warn(s"WRONG START BLOCK: ${range.startNum} => ${bs.head.getBlockHeader.getRawData.number}")
//        }
//        if (range.endNum - 1 != bs.last.getBlockHeader.getRawData.number) {
//          Logger.warn(s"WRONG END BLOCK: ${range.endNum} => ${bs.last.getBlockHeader.getRawData.number}")
//        }
//        Logger.info(s"DOWNLOADED $fromBlock to $toBlock. GOT ${bs.headOption.map(_.getBlockHeader.getRawData.number)} => ${bs.lastOption.map(_.getBlockHeader.getRawData.number)}")
        bs
      }
    }
    .mapConcat(x => x.toList)
    .map { block =>
      Logger.info("Block: " + block.getBlockHeader.getRawData.number)
      block
    }
  }

  def filterContracts(contractTypes: List[Transaction.Contract.ContractType]) = {
    Flow[Transaction.Contract]
      .filter(contract  => contractTypes.contains(contract.`type`))
  }

  /**
    * Publishes contracts to the given eventstream
    */
  def publishContractEvents(eventStream: EventStream, contractTypes: List[Transaction.Contract.ContractType]) = {
    Flow[ContractFlow]
      .filter(contract  => contractTypes.contains(contract._3.`type`))
      .toMat(Sink.foreach { contractBlock =>

        val (block, transaction, contract) = contractBlock

        (contract.`type`, ModelUtils.contractToModel(contract, transaction, block)) match {
          case (TransferContract, Some(transfer: TransferModel)) =>
            eventStream.publish(TransferCreated(transfer))

          case (TransferAssetContract, Some(transfer: TransferModel)) =>
            eventStream.publish(AssetTransferCreated(transfer))

          case (WitnessCreateContract, Some(witness: WitnessModel)) =>
            eventStream.publish(WitnessCreated(witness))

          case (VoteWitnessContract, Some(votes: VoteWitnessList)) =>
            votes.votes.foreach { vote =>
              eventStream.publish(VoteCreated(vote))
            }

          case (AssetIssueContract, Some(assetIssue: AssetIssueContractModel)) =>
            eventStream.publish(AssetIssueCreated(assetIssue))

          case (ParticipateAssetIssueContract, Some(participate: ParticipateAssetIssueModel)) =>
            eventStream.publish(ParticipateAssetIssueModelCreated(participate))

          case _ =>
            // ignore
        }
      })(Keep.right)
  }
}
