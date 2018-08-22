package org.tronscan.importer

import org.tron.protos.Tron.{Block, Transaction}

object StreamTypes {
  type ContractFlow = (Block, Transaction, Transaction.Contract)
}
