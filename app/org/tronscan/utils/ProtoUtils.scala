package org.tronscan.utils

import org.tron.protos.Contract.{BuyStorageBytesContract, BuyStorageContract, SellStorageContract}
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

      case ProposalCreateContract =>
        org.tron.protos.Contract.ProposalCreateContract.parseFrom(any.value.toByteArray)

      case ProposalApproveContract =>
        org.tron.protos.Contract.ProposalApproveContract.parseFrom(any.value.toByteArray)

      case ProposalDeleteContract =>
        org.tron.protos.Contract.ProposalDeleteContract.parseFrom(any.value.toByteArray)

      case CreateSmartContract =>
        org.tron.protos.Contract.CreateSmartContract.parseFrom(any.value.toByteArray)

      case TriggerSmartContract =>
        org.tron.protos.Contract.TriggerSmartContract.parseFrom(any.value.toByteArray)

//      case BuyStorageBytesContract =>
//        org.tron.protos.Contract.BuyStorageBytesContract.parseFrom(any.value.toByteArray)

//      case BuyStorageContract =>
//        org.tron.protos.Contract.BuyStorageContract.parseFrom(any.value.toByteArray)

//      case SellStorageContract =>
//        org.tron.protos.Contract.SellStorageContract.parseFrom(any.value.toByteArray)

      case ExchangeCreateContract =>
        org.tron.protos.Contract.ExchangeCreateContract.parseFrom(any.value.toByteArray)

      case ExchangeInjectContract =>
        org.tron.protos.Contract.ExchangeInjectContract.parseFrom(any.value.toByteArray)

      case ExchangeWithdrawContract =>
        org.tron.protos.Contract.ExchangeWithdrawContract.parseFrom(any.value.toByteArray)

      case ExchangeTransactionContract =>
        org.tron.protos.Contract.ExchangeTransactionContract.parseFrom(any.value.toByteArray)

      case _ =>
        throw new Exception("Unknown Contract")
    }
  }
}
