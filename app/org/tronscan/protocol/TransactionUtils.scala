package org.tronscan.protocol

import org.tronscan.Extensions._
import org.tron.protos.Tron.Transaction
import org.tron.protos.Tron.Transaction.Contract.ContractType._

object TransactionUtils {

  def getOwner(contract: Transaction.Contract) = {
    contract.`type` match {
      case AccountCreateContract =>
        org.tron.protos.Contract.AccountCreateContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case TransferContract =>
        org.tron.protos.Contract.TransferContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case TransferAssetContract =>
        org.tron.protos.Contract.TransferAssetContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case VoteAssetContract =>
        org.tron.protos.Contract.VoteAssetContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case VoteWitnessContract =>
        org.tron.protos.Contract.VoteWitnessContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case AssetIssueContract =>
        org.tron.protos.Contract.AssetIssueContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case DeployContract =>
        org.tron.protos.Contract.DeployContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case ParticipateAssetIssueContract =>
        org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case WitnessCreateContract =>
        org.tron.protos.Contract.WitnessCreateContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case WitnessUpdateContract =>
        org.tron.protos.Contract.WitnessUpdateContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case FreezeBalanceContract =>
        org.tron.protos.Contract.FreezeBalanceContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case UnfreezeBalanceContract =>
        org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case AccountUpdateContract =>
        org.tron.protos.Contract.AccountUpdateContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case WithdrawBalanceContract =>
        org.tron.protos.Contract.WithdrawBalanceContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case UnfreezeAssetContract =>
        org.tron.protos.Contract.UnfreezeAssetContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case UpdateAssetContract =>
        org.tron.protos.Contract.UpdateAssetContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.toAddress

      case _ =>
        ""
    }
  }
}
