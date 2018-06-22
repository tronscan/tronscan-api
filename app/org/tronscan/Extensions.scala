package org.tronscan

import com.google.protobuf.ByteString
import org.tron.common.utils.{Base58, ByteArray, Sha256Hash}
import org.tron.protos.Tron.{Block, Transaction}

object Extensions {

  implicit class ImplicitBlock(block: Block) {
    def hash: String = hashBytes.toString
    def hashBytes = {
      val numBytes = ByteArray.fromLong(number)
      val hash = block.getBlockHeader.getRawData.toByteArray
      Array.copy(numBytes, 0, hash, 0, 8)
      Sha256Hash.of(numBytes)
    }
    def rawHashBytes = Sha256Hash.of(block.getBlockHeader.getRawData.toByteArray)

    def number: Long = block.getBlockHeader.getRawData.number

    def parentHash = {
      val numBytes = ByteArray.fromLong(number - 1)
      val hash = block.getBlockHeader.getRawData.parentHash.toByteArray
      Array.copy(numBytes, 0, hash, 0, 8)
      Sha256Hash.of(numBytes)
    }
  }

  implicit class ImplicitTransaction(trx: Transaction) {
    def hash: String = Sha256Hash.of(trx.getRawData.toByteArray).toString
    def hashBytes = Sha256Hash.of(trx.getRawData.toByteArray).getBytes
  }

  implicit class ByteStringUtils(byteString: ByteString) {
    def encodeAddress = {
      Base58.encode58Check(byteString.toByteArray)
    }

    def decodeString = {
      new String(byteString.toByteArray)
    }
  }

  implicit class StringUtils(str: String) {
    def decodeAddress = {
      ByteString.copyFrom(Base58.decode58Check(str))
    }

    def encodeString = {
      ByteString.copyFromUtf8(str)
    }
  }

}
