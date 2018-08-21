package org.tronscan.test

import com.google.protobuf.any.Any
import org.tron.protos.Tron.Transaction.Contract
import org.tron.protos.Tron.{Block, BlockHeader, Transaction}
import org.tron.protos.Tron.Transaction.Contract.ContractType
import scalapb.{GeneratedMessage, Message}

case class BlockBuilder(block: Block = Block().withBlockHeader(BlockHeader().withRawData(BlockHeader.raw()))) {

  def addContract[A <: GeneratedMessage with Message[A]](contracType: ContractType, contract: A) = {
    copy(block = block
      .addTransactions(Transaction()
        .withRawData(Transaction.raw().withContract(List(
          Contract()
            .withType(contracType)
            .withParameter(Any.pack(contract))
        )))))
  }

}
