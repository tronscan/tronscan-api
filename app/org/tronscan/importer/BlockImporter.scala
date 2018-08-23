package org.tronscan.importer

import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import javax.inject.Inject
import org.tron.protos.Tron.Block
import org.tronscan.models._
import org.tronscan.utils.ModelUtils
import play.api.Logger
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction
import org.tronscan.Extensions._

import scala.collection.mutable.ListBuffer
import concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class BlockImporter @Inject() (
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  assetIssueContractModelRepository: AssetIssueContractModelRepository,
  participateAssetIssueModelRepository: ParticipateAssetIssueModelRepository,
  databaseImporter: DatabaseImporter) {

  /**
    * Build block importer that imports the full nodes into the database
    *
    * @param confirmBlocks if all blocks that are being imported should be automatically confirmed
    */
  def fullNodeBlockImporter(confirmBlocks: Boolean = false): Sink[Block, Future[Done]] = {

    import databaseImporter._
    val importer = importWitnessCreate orElse importTransfers orElse buildConfirmedEvents orElse elseEmpty

    Flow[Block]
      .map { block =>

        val header = block.getBlockHeader.getRawData
        val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

        if (header.number % 1000 == 0) {
          Logger.info(s"FULL NODE BLOCK: ${header.number}, TX: ${block.transactions.size}, CONFIRM: $confirmBlocks")
        }

        // Import Block
        queries.append(blockModelRepository.buildInsert(BlockModel.fromProto(block).copy(confirmed = confirmBlocks)))

        // Import Transactions
        queries.appendAll(block.transactions.map { trx =>
          transactionModelRepository.buildInsert(ModelUtils.transactionToModel(trx, block).copy(confirmed = confirmBlocks))
        })

        // Import Contracts
        queries.appendAll(block.transactionContracts.flatMap {
          case (trx, contract) =>
            ModelUtils.contractToModel(contract, trx, block).map {
              case transfer: TransferModel =>
                importer((contract.`type`, contract, transfer.copy(confirmed = confirmBlocks || block.getBlockHeader.getRawData.number == 0)))
              case x =>
                importer((contract.`type`, contract, x))
            }.getOrElse(Seq.empty)
        })

        queries.toList
      }
      // Flatmap the queries
      .mapConcat(q => q)
      // Batch queries together
      .groupedWithin(1000, 2.seconds)
      // Insert batched queries in database
      .mapAsync(1)(blockModelRepository.executeQueries)
      .toMat(Sink.ignore)(Keep.right)
  }

  /**
    * Build a stream that imports the blocks into the database
    */
  def buildSolidityBlockQueryImporter(implicit executionContext: ExecutionContext) = {

    import databaseImporter._

    val importer = buildConfirmedEvents orElse elseEmpty

    Flow[Block]
      .mapAsync(12) { solidityBlock =>
        for {
          databaseBlock <- blockModelRepository.findByNumber(solidityBlock.getBlockHeader.getRawData.number)
        } yield (solidityBlock, databaseBlock.get)
      }
      // Filter empty or confirmed blocks
      .filter(x => x._1.blockHeader.isDefined && !x._2.confirmed)
      .map {
        case (solidityBlock, databaseBlock) =>

          val queries = ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]]()

          // Block needs to be replaced if the full node block hash is different from the solidity block hash
          val replaceBlock = solidityBlock.hash != databaseBlock.hash

          if (replaceBlock) {
            val number = solidityBlock.getBlockHeader.getRawData.number
            Logger.info("REPLACE BLOCK: " + number)
            // replace block
            queries.appendAll(blockModelRepository.buildReplaceBlock(BlockModel.fromProto(solidityBlock)))
            queries.append(transactionModelRepository.deleteByNum(number))
            queries.append(transferRepository.deleteByNum(number))
            queries.append(assetIssueContractModelRepository.deleteByNum(number))
            queries.append(participateAssetIssueModelRepository.deleteByNum(number))
          } else {
            Logger.info("CONFIRM BLOCK: " + solidityBlock.getBlockHeader.getRawData.number)
            // Update Block
            queries.appendAll(blockModelRepository.buildConfirmBlock(databaseBlock.number))
          }

          // Import / Overwrite transactions
          queries.appendAll(for {
            transaction <- solidityBlock.transactions
            transactionModel = ModelUtils.transactionToModel(transaction, solidityBlock)
          } yield {
            transactionModelRepository.buildInsertOrUpdate(transactionModel.copy(confirmed = true))
          })

          queries.appendAll(solidityBlock.transactionContracts.flatMap {
            case (trx, contract) =>
              ModelUtils.contractToModel(contract, trx, solidityBlock).map {
                case _: TransferModel if !replaceBlock =>
                  // Don't import transfers if they don't need to be replaced
                  Seq.empty
                case transfer: TransferModel =>
                  // Import transfer as confirmed
                  importer((contract.`type`, contract, transfer.copy(confirmed = true)))
                case x if replaceBlock => importer((contract.`type`, contract, x))
                case _ => Seq.empty
              }.getOrElse(Seq.empty)
          })

          queries.toList
      }
      .flatMapConcat(queries => Source(queries))
      .groupedWithin(500, 10.seconds)
      .mapAsync(1)(queries => blockModelRepository.executeQueries(queries))
      .toMat(Sink.ignore)(Keep.right)
  }


  /**
    * Build a stream that just logs every 1000th block
    * @return
    */
  def buildDebugStream(): Sink[Block, Future[Done]] = {
    Sink.foreach { block =>
      val header = block.getBlockHeader.getRawData
      if (header.number % 1000 == 0) {
        Logger.info(s"FULL NODE BLOCK: ${header.number}, TX: ${block.transactions.size}")
      }
    }
  }

}
