package org.tronscan

import com.google.protobuf.ByteString
import org.tron.common.utils.{Base58, Sha256Hash}
import org.tron.protos.Tron.Block
import org.tronscan.protocol.AddressFormatter

object Extensions {

  implicit class BlockUtils(block: Block) {
    def hash = Sha256Hash.of(block.getBlockHeader.getRawData.toByteArray).toString
    def number = block.getBlockHeader.getRawData.number
  }

  implicit class ByteStringUtils(byteString: ByteString) {
    def toAddress = {
      Base58.encode58Check(byteString.toByteArray)
    }

  }

}
