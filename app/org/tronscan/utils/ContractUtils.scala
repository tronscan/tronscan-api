package org.tronscan.utils

import org.tron.protos.Contract._
import org.tron.protos.Tron.Transaction
import org.tronscan.Extensions._

object ContractUtils {

  def getOwner(contract: Transaction.Contract) = {

    ProtoUtils.fromContract(contract).map {
      case c: AccountCreateContract =>
        c.ownerAddress.encodeAddress

      case c: TransferContract =>
        c.ownerAddress.encodeAddress

      case c: TransferAssetContract =>
        c.ownerAddress.encodeAddress

      case c: VoteAssetContract =>
        c.ownerAddress.encodeAddress

      case c: VoteWitnessContract =>
        c.ownerAddress.encodeAddress

      case c: AssetIssueContract =>
        c.ownerAddress.encodeAddress

//      case c: DeployContract =>
//        c.ownerAddress.encodeAddress

      case c: ParticipateAssetIssueContract =>
        c.ownerAddress.encodeAddress

      case c: WitnessCreateContract =>
        c.ownerAddress.encodeAddress

      case c: WitnessUpdateContract =>
        c.ownerAddress.encodeAddress

      case c: FreezeBalanceContract =>
        c.ownerAddress.encodeAddress

      case c: UnfreezeBalanceContract =>
        c.ownerAddress.encodeAddress

      case c: AccountUpdateContract =>
        c.ownerAddress.encodeAddress

      case c: WithdrawBalanceContract =>
        c.ownerAddress.encodeAddress

      case c: UnfreezeAssetContract =>
        c.ownerAddress.encodeAddress

      case c: UpdateAssetContract =>
        c.ownerAddress.encodeAddress

      case c: TriggerSmartContract =>
        c.ownerAddress.encodeAddress

      case c: CreateSmartContract =>
        c.ownerAddress.encodeAddress

      case c: ProposalApproveContract =>
        c.ownerAddress.encodeAddress

      case c: ProposalDeleteContract =>
        c.ownerAddress.encodeAddress

      case c: ProposalCreateContract =>
        c.ownerAddress.encodeAddress

      case c: ProposalCreateContract =>
        c.ownerAddress.encodeAddress

      case _ =>
        ""

    }.getOrElse("")
  }

  def getTo(contract: Transaction.Contract): Option[String] = {

    ProtoUtils.fromContract(contract).flatMap {
      case c: AccountCreateContract =>
        Some(c.accountAddress.encodeAddress)

      case c: TransferContract =>
        Some(c.toAddress.encodeAddress)

      case c: TransferAssetContract =>
        Some(c.toAddress.encodeAddress)

      case c: ParticipateAssetIssueContract =>
        Some(c.toAddress.encodeAddress)

      case _ =>
        None
    }
  }

  def getAddresses(contract: Transaction.Contract): List[String] = {
    List(getOwner(contract)) ++ getTo(contract).map(List(_)).getOrElse(List.empty)
  }

}
