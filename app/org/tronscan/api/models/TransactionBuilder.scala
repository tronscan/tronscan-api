package org.tronscan.api.models

import io.swagger.annotations.ApiModelProperty

// Approve Proposal
case class ProposalApproveTransaction(
  contract: ProposalApprove) extends TransactionCreateBase

case class ProposalApprove(
  ownerAddress: String,
  @ApiModelProperty(value = "ID of the proposal")
  proposalId: Long,
  @ApiModelProperty(value = "true to approve, false to reject")
  approved: Boolean)


// Transfer
case class TransferTransaction(
  contract: Transfer) extends TransactionCreateBase

case class Transfer(
  ownerAddress: String,
  toAddress: String,
  @ApiModelProperty(value = "Amount of TRX to send in Sun")
  amount: Long)

// Transfer Asset
case class TransferAssetTransaction(
  contract: TransferAsset) extends TransactionCreateBase

case class TransferAsset(
  ownerAddress: String,
  toAddress: String,
  @ApiModelProperty(value = "Name of the token")
  assetName: String,
  @ApiModelProperty(value = "Amount of tokens to send")
  amount: Long)


// Withdraw Balance
case class WithdrawBalanceTransaction(
  contract: WithdrawBalance) extends TransactionCreateBase

case class WithdrawBalance(
  ownerAddress: String)

// Account Update
case class AccountUpdateTransaction(
  contract: AccountUpdate) extends TransactionCreateBase

case class AccountUpdate(
  ownerAddress: String,
  accountName: String)

// Account Create
case class AccountCreateTransaction(
  contract: AccountCreate) extends TransactionCreateBase

case class AccountCreate(
  ownerAddress: String,
  accountAddress: String)


// Update Asset
case class UpdateAssetTransaction(
  contract: UpdateAsset) extends TransactionCreateBase

case class UpdateAsset(
  ownerAddress: String,
  url: String,
  description: String,
  newLimit: Option[Long],
  newPublicLimit: Option[Long])

