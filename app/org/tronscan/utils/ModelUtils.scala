package org.tronscan.utils

import org.joda.time.DateTime
import org.tron.protos.Contract._
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer
import org.tronscan.models._

object ModelUtils {

  /**
    * Converts transaction to database model
    * @param trx
    * @param block
    * @return
    */
  def transactionToModel(trx: Transaction, block: Block) = {
    val transactionHash = trx.hash
    val header = block.getBlockHeader.getRawData
    val transactionTime = new DateTime(header.timestamp)

    TransactionModel(
      hash = transactionHash,
      block = header.number,
      timestamp = transactionTime,
      ownerAddress = ContractUtils.getOwner(trx.getRawData.contract.head),
      contractData = TransactionSerializer.serializeContract(trx.getRawData.contract.head),
      contractType = trx.getRawData.contract.head.`type`.value,
    )
  }

  /**
    * Converts a contract to a database model
    */
  def contractToModel(contract: Transaction.Contract, trx: Transaction, block: Block): Option[Any] = {

    val header = block.getBlockHeader.getRawData
    val transactionHash = trx.hash
    val transactionTime = new DateTime(header.timestamp)

    ProtoUtils.fromContract(contract) match {
      case c: TransferContract =>
        Some(TransferModel(
          transactionHash = transactionHash,
          block = header.number,
          timestamp = transactionTime,
          transferFromAddress = c.ownerAddress.encodeAddress,
          transferToAddress = c.toAddress.encodeAddress,
          amount = c.amount,
          confirmed = header.number == 0))

      case c: TransferAssetContract =>
        Some(TransferModel(
          transactionHash = transactionHash,
          block = header.number,
          timestamp = transactionTime,
          transferFromAddress = c.ownerAddress.encodeAddress,
          transferToAddress = c.toAddress.encodeAddress,
          amount = c.amount,
          tokenName = new String(c.assetName.toByteArray),
          confirmed = header.number == 0))

      case c: VoteWitnessContract =>
        val inserts = for (vote <- c.votes) yield {
          VoteWitnessContractModel(
            transaction = transactionHash,
            block = header.number,
            timestamp = transactionTime,
            voterAddress = c.ownerAddress.encodeAddress,
            candidateAddress = vote.voteAddress.encodeAddress,
            votes = vote.voteCount,
          )
        }

        Some(VoteWitnessList(c.ownerAddress.encodeAddress, inserts.toList))

      case c: AssetIssueContract =>
        Some(AssetIssueContractModel(
          block = header.number,
          transaction = transactionHash,
          ownerAddress = c.ownerAddress.encodeAddress,
          name = c.name.decodeString.trim,
          abbr = c.abbr.decodeString.trim,
          totalSupply = c.totalSupply,
          trxNum = c.trxNum,
          num = c.num,
          startTime = new DateTime(c.startTime),
          endTime = new DateTime(c.endTime),
          voteScore = c.voteScore,
          description = c.description.decodeString,
          url = c.url.decodeString,
          dateCreated = transactionTime,
        ).withFrozen(c.frozenSupply))

      case c: ParticipateAssetIssueContract =>
        Some(ParticipateAssetIssueModel(
          transaction_hash = transactionHash,
          ownerAddress = c.ownerAddress.encodeAddress,
          toAddress = c.toAddress.encodeAddress,
          amount = c.amount,
          block = header.number,
          token = c.assetName.decodeString,
          dateCreated = transactionTime))

      case c: WitnessCreateContract =>
        Some(WitnessModel(
          address = c.ownerAddress.encodeAddress,
          url = c.url.decodeString))

      case c: WitnessUpdateContract =>
        Some(WitnessModel(
          address = c.ownerAddress.encodeAddress,
          url = c.updateUrl.decodeString))

//      case c: AccountCreateContract =>
//        c.ownerAddress.encodeAddress
//      case c: DeployContract =>
//        c.ownerAddress.encodeAddress
//      case c: VoteAssetContract =>
//        c.ownerAddress.encodeAddress
//      case c: FreezeBalanceContract =>
//        c.ownerAddress.encodeAddress
//      case c: UnfreezeBalanceContract =>
//        c.ownerAddress.encodeAddress
//      case c: AccountUpdateContract =>
//        c.ownerAddress.encodeAddress
//      case c: WithdrawBalanceContract =>
//        c.ownerAddress.encodeAddress
//      case c: UnfreezeAssetContract =>
//        c.ownerAddress.encodeAddress
//      case c: UpdateAssetContract =>
//        c.ownerAddress.encodeAddress
      case _ =>
        None
    }
  }
}
