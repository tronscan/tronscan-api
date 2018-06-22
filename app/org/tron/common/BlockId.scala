package org.tron.common

import org.tron.common.utils.ByteArray

case class BlockId(num: Long, blockHash: Array[Byte]) {
  def hash = {
    val numBytes = ByteArray.fromLong(num)
    val hash = blockHash
    Array.copy(numBytes, 0, hash, 0, 8)
    hash
  }
}