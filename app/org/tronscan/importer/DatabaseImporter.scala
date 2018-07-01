package org.tronscan.importer

import javax.inject.Inject
import org.tronscan.models._

class DatabaseImporter @Inject() (
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  voteWitnessContractModelRepository: VoteWitnessContractModelRepository,
  witnessModelRepository: WitnessModelRepository,
) {

  def buildImportBlock(block: BlockModel) = {
    blockModelRepository.buildInsert(block)
  }

  def buildConfirmBlock(block: BlockModel) = {
    Seq(
      blockModelRepository.buildDeleteByNumber(block.number),
      buildImportBlock(block.copy(confirmed = true))
    )
  }
}
