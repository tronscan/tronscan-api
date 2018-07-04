package org.tronscan.utils

import org.tron.protos.Tron.Transaction

object ProtoUtils {

  /**
    * Convert proto any contract to contract protobuf
    */
  def fromContract(contract: Transaction.Contract): Any = {
    import org.tron.protos.Tron.Transaction.Contract.ContractType._

    val any = contract.getParameter
    contract.`type` match {
      case TransferContract =>
        org.tron.protos.Contract.TransferContract.parseFrom(any.value.toByteArray)
      case TransferAssetContract =>
        org.tron.protos.Contract.TransferAssetContract.parseFrom(any.value.toByteArray)
      case VoteWitnessContract =>
        org.tron.protos.Contract.VoteWitnessContract.parseFrom(any.value.toByteArray)
      case AssetIssueContract =>
        org.tron.protos.Contract.AssetIssueContract.parseFrom(any.value.toByteArray)
      case UpdateAssetContract =>
        org.tron.protos.Contract.UpdateAssetContract.parseFrom(any.value.toByteArray)
      case ParticipateAssetIssueContract =>
        org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(any.value.toByteArray)
      case WitnessCreateContract =>
        org.tron.protos.Contract.WitnessCreateContract.parseFrom(any.value.toByteArray)
      case WitnessUpdateContract =>
        org.tron.protos.Contract.WitnessUpdateContract.parseFrom(any.value.toByteArray)
      case UnfreezeBalanceContract =>
        org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(any.value.toByteArray)
      case FreezeBalanceContract =>
        org.tron.protos.Contract.FreezeBalanceContract.parseFrom(any.value.toByteArray)
      case WithdrawBalanceContract =>
        org.tron.protos.Contract.WithdrawBalanceContract.parseFrom(any.value.toByteArray)
      case AccountUpdateContract =>
        org.tron.protos.Contract.AccountUpdateContract.parseFrom(any.value.toByteArray)
      case UnfreezeAssetContract =>
        org.tron.protos.Contract.UnfreezeAssetContract.parseFrom(any.value.toByteArray)
      case AccountCreateContract =>
        org.tron.protos.Contract.AccountCreateContract.parseFrom(any.value.toByteArray)
      case _ =>
        throw new Exception("Unknown Contract")
    }
  }
}
