package org.tronscan.utils

import org.tron.protos.Tron.Transaction

object ProtoUtils {

  /**
    * Convert proto any contract to contract protobuf
    */
  def fromContract(contract: Transaction.Contract): Option[Any] = {
    import org.tron.protos.Tron.Transaction.Contract.ContractType._

    val any = contract.getParameter
    contract.`type` match {
      case TransferContract =>
        Some(org.tron.protos.Contract.TransferContract.parseFrom(any.value.toByteArray))

      case TransferAssetContract =>
        Some(org.tron.protos.Contract.TransferAssetContract.parseFrom(any.value.toByteArray))

      case VoteWitnessContract =>
        Some(org.tron.protos.Contract.VoteWitnessContract.parseFrom(any.value.toByteArray))

      case AssetIssueContract =>
        Some(org.tron.protos.Contract.AssetIssueContract.parseFrom(any.value.toByteArray))

      case UpdateAssetContract =>
        Some(org.tron.protos.Contract.UpdateAssetContract.parseFrom(any.value.toByteArray))

      case ParticipateAssetIssueContract =>
        Some(org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(any.value.toByteArray))

      case WitnessCreateContract =>
        Some(org.tron.protos.Contract.WitnessCreateContract.parseFrom(any.value.toByteArray))

      case WitnessUpdateContract =>
        Some(org.tron.protos.Contract.WitnessUpdateContract.parseFrom(any.value.toByteArray))

      case UnfreezeBalanceContract =>
        Some(org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(any.value.toByteArray))

      case FreezeBalanceContract =>
        Some(org.tron.protos.Contract.FreezeBalanceContract.parseFrom(any.value.toByteArray))

      case WithdrawBalanceContract =>
        Some(org.tron.protos.Contract.WithdrawBalanceContract.parseFrom(any.value.toByteArray))

      case AccountUpdateContract =>
        Some(org.tron.protos.Contract.AccountUpdateContract.parseFrom(any.value.toByteArray))

      case UnfreezeAssetContract =>
        Some(org.tron.protos.Contract.UnfreezeAssetContract.parseFrom(any.value.toByteArray))

      case AccountCreateContract =>
        Some(org.tron.protos.Contract.AccountCreateContract.parseFrom(any.value.toByteArray))

      case ProposalCreateContract =>
        Some(org.tron.protos.Contract.ProposalCreateContract.parseFrom(any.value.toByteArray))

      case ProposalApproveContract =>
        Some(org.tron.protos.Contract.ProposalApproveContract.parseFrom(any.value.toByteArray))

      case ProposalDeleteContract =>
        Some(org.tron.protos.Contract.ProposalDeleteContract.parseFrom(any.value.toByteArray))

      case CreateSmartContract =>
        Some(org.tron.protos.Contract.CreateSmartContract.parseFrom(any.value.toByteArray))

      case TriggerSmartContract =>
        Some(org.tron.protos.Contract.TriggerSmartContract.parseFrom(any.value.toByteArray))

      //      case BuyStorageBytesContract =>
      //        org.tron.protos.Contract.BuyStorageBytesContract.parseFrom(any.value.toByteArray)

      //      case BuyStorageContract =>
      //        org.tron.protos.Contract.BuyStorageContract.parseFrom(any.value.toByteArray)

      //      case SellStorageContract =>
      //        org.tron.protos.Contract.SellStorageContract.parseFrom(any.value.toByteArray)

      case ExchangeCreateContract =>
        Some(org.tron.protos.Contract.ExchangeCreateContract.parseFrom(any.value.toByteArray))

      case ExchangeInjectContract =>
        Some(org.tron.protos.Contract.ExchangeInjectContract.parseFrom(any.value.toByteArray))

      case ExchangeWithdrawContract =>
        Some(org.tron.protos.Contract.ExchangeWithdrawContract.parseFrom(any.value.toByteArray))

      case ExchangeTransactionContract =>
        Some(org.tron.protos.Contract.ExchangeTransactionContract.parseFrom(any.value.toByteArray))

      case _ =>
        None
    }
  }

}