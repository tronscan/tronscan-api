package org.tron.common

import org.tron.common.utils.ByteArray

case class BlockId(num: Long, blockHash: Array[Byte]) {
  def hash = {
    val numBytes = ByteArray.fromLong(num)
    numBytes ++ blockHash.drop(8)
  }
}