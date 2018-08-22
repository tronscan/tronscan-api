package org.tronscan.importer

import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import javax.inject.Inject
import org.tron.protos.Tron.Block
import org.tronscan.models.{BlockModel, BlockModelRepository, TransactionModelRepository, TransferModel}
import org.tronscan.utils.ModelUtils
import play.api.Logger
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction
import org.tronscan.Extensions._

import scala.collection.mutable.ListBuffer
import concurrent.duration._
import scala.concurrent.Future

class BlockImporter @Inject() (
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  databaseImporter: DatabaseImporter) {

  def buildContractSqlBuilder = {
    import databaseImporter._
    importWitnessCreate orElse importTransfers orElse buildConfirmedEvents orElse elseEmpty
  }

  /**
    * Build block importer
    *
    * @param confirmBlocks if all blocks that are being imported should be automatically confirmed
    */
  def fullNodeBlockImporter(confirmBlocks: Boolean = false): Sink[Block, Future[Done]] = {
    val importer = buildContractSqlBuilder

    Flow[Block]
      .map { block =>

        val header = block.getBlockHeader.getRawData
        val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

        Logger.info(s"FULL NODE BLOCK: ${header.number}, TX: ${block.transactions.size}, CONFIRM: $confirmBlocks")

        // Import Block
        queries.append(blockModelRepository.buildInsert(BlockModel.fromProto(block).copy(confirmed = confirmBlocks)))

        // Import Transactions
        queries.appendAll(block.transactions.map { trx =>
          transactionModelRepository.buildInsertOrUpdate(ModelUtils.transactionToModel(trx, block).copy(confirmed = confirmBlocks))
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
}
