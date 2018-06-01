package org.tronscan.events

import org.tronscan.models._

sealed trait BlockChainEvent
sealed trait AddressEvent extends BlockChainEvent {
  def isAddress(address: String): Boolean
}

sealed trait TransactionEvent extends BlockChainEvent {
  def isTransaction(hash: String): Boolean
}

case class TransferCreated(trx: TransferModel) extends AddressEvent {
  def isAddress(address: String) = {
    trx.transferFromAddress == address || trx.transferToAddress == address
  }
}

case class AssetTransferCreated(trx: TransferModel) extends AddressEvent {
  def isAddress(address: String) = {
    trx.transferFromAddress == address || trx.transferToAddress == address
  }
}

case class ParticipateAssetIssueModelCreated(trx: ParticipateAssetIssueModel) extends AddressEvent {
  def isAddress(address: String) = {
    trx.ownerAddress == address || trx.toAddress == address
  }
}

case class AssetIssueCreated(trx: AssetIssueContractModel) extends AddressEvent {
  def isAddress(address: String) = {
    trx.ownerAddress == address
  }
}

case class VoteCreated(vote: VoteWitnessContractModel) extends AddressEvent {
  def isAddress(address: String) = {
    vote.candidateAddress == address || vote.voterAddress == address
  }
}

case class WitnessCreated(vote: WitnessModel) extends AddressEvent {
  def isAddress(address: String) = {
    vote.address == address
  }
}
