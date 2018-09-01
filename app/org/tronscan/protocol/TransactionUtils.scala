package org.tronscan.protocol

import org.tron.protos.Tron.Transaction
import org.tron.protos.Tron.Transaction.Contract.ContractType._
import org.tronscan.Extensions._

object TransactionUtils {

  def getOwner(contract: Transaction.Contract) = {
    contract.`type` match {
      case AccountCreateContract =>
        org.tron.protos.Contract.AccountCreateContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case TransferContract =>
        org.tron.protos.Contract.TransferContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case TransferAssetContract =>
        org.tron.protos.Contract.TransferAssetContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case VoteAssetContract =>
        org.tron.protos.Contract.VoteAssetContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case VoteWitnessContract =>
        org.tron.protos.Contract.VoteWitnessContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case AssetIssueContract =>
        org.tron.protos.Contract.AssetIssueContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

//      case DeployContract =>
//        org.tron.protos.Contract.DeployContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case ParticipateAssetIssueContract =>
        org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case WitnessCreateContract =>
        org.tron.protos.Contract.WitnessCreateContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case WitnessUpdateContract =>
        org.tron.protos.Contract.WitnessUpdateContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case FreezeBalanceContract =>
        org.tron.protos.Contract.FreezeBalanceContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case UnfreezeBalanceContract =>
        org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case AccountUpdateContract =>
        org.tron.protos.Contract.AccountUpdateContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case WithdrawBalanceContract =>
        org.tron.protos.Contract.WithdrawBalanceContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case UnfreezeAssetContract =>
        org.tron.protos.Contract.UnfreezeAssetContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case UpdateAssetContract =>
        org.tron.protos.Contract.UpdateAssetContract.parseFrom(contract.getParameter.value.toByteArray).ownerAddress.encodeAddress

      case _ =>
        ""
    }
  }
}
