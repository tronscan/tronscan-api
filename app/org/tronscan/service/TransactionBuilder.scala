package org.tronscan.service

import com.google.protobuf.ByteString
import com.google.protobuf.any.Any
import javax.inject.Inject
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.common.crypto.ECKey
import org.tron.common.utils.{Base58, ByteArray, Sha256Hash}
import org.tron.protos.Contract.TransferContract
import org.tron.protos.Tron.Transaction
import org.tron.protos.Tron.Transaction.Contract.ContractType
import org.tronscan.Extensions._

import scala.concurrent.ExecutionContext

class TransactionBuilder @Inject() (wallet: Wallet) {

  def buildTransactionWithContract(contract: Transaction.Contract) = {
    Transaction(
      rawData = Some(Transaction.raw(
        contract = Seq(contract)
      ))
    )
  }

  def buildTrxTransfer(from: Array[Byte], to: String, amount: Long) = {

    val transferContract = TransferContract(
      ownerAddress = ByteString.copyFrom(from),
      toAddress = ByteString.copyFrom(Base58.decode58Check(to)),
      amount = amount)

    val contract = Transaction.Contract(
      `type` = ContractType.TransferContract,
      parameter = Some(Any.pack(transferContract))
    )

    buildTransactionWithContract(contract)
  }

  /**
    * Add block reference
    */
  def setReference(transaction: Transaction)(implicit executionContext: ExecutionContext) = {
    for {
      latestBlock <- wallet.getNowBlock(EmptyMessage())
    } yield {
      val raw = transaction.rawData.get
        .withRefBlockHash(ByteString.copyFrom(ByteArray.subArray(latestBlock.hashBytes, 8, 16)))
        .withRefBlockBytes(ByteString.copyFrom(ByteArray.subArray(ByteArray.fromLong(latestBlock.getBlockHeader.getRawData.number), 6, 8)))
        .withExpiration(latestBlock.getBlockHeader.getRawData.timestamp + (60 * 5 * 1000))

      transaction
        .withRawData(raw)
    }
  }

  /**
    * Add block reference
    */
  def sign(transaction: Transaction, pk: Array[Byte]) = {
    val ecKey = ECKey.fromPrivate(pk)
    val signature = ecKey.sign(Sha256Hash.hash(transaction.getRawData.toByteArray))
    val sig = ByteString.copyFrom(signature.toByteArray)
    transaction.addSignature(sig)
  }
}
