package org.tronscan.utils

import cats.Monoid
import org.tron.protos.Tron.Block

object CatsUtils {

  implicit val blockSemiGroup = new Monoid[Block] {
    def empty = Block()
    def combine(x: Block, y: Block): Block = {
      x.withTransactions(x.transactions ++ y.transactions)
    }
  }

}
