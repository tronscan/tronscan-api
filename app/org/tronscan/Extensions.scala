package org.tronscan

import com.google.protobuf.ByteString
import org.tronscan.domain.Types.{BlockHash, TxHash}
import org.tron.common.BlockId
import org.tron.common.utils.{Base58, ByteArray, Sha256Hash}
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.protocol.ContractUtils

object Extensions {

  implicit class ImplicitBlock(block: Block) {
    def hash: BlockHash = ByteArray.toHexString(hashBytes)
    def rawHash = Sha256Hash.of(block.getBlockHeader.getRawData.toByteArray)
    def hashBytes = BlockId(number, rawHash.getBytes).hash
    def number: Long = block.getBlockHeader.getRawData.number
    def parentHash = BlockId(number - 1, block.getBlockHeader.getRawData.parentHash.toByteArray).hash

    def witness = block.getBlockHeader.getRawData.witnessAddress.encodeAddress
  }

  implicit class ImplicitTransaction(trx: Transaction) {
    def hash: TxHash = Sha256Hash.of(trx.getRawData.toByteArray).toString
    def hashBytes = Sha256Hash.of(trx.getRawData.toByteArray).getBytes
  }

  implicit class ImplicitContract(contract: Transaction.Contract) {
    def addresses = ContractUtils.getAddresses(contract)
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
