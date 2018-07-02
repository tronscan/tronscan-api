package org.tronscan.protocol

import org.tron.protos.Tron.Transaction
import org.tronscan.Extensions._
import org.tronscan.domain.Types.Address
import org.tron.protos.Contract._
import org.tronscan.utils.ProtoUtils

object ContractUtils {

  def getOwner(contract: Transaction.Contract) = {

    ProtoUtils.fromContract(contract) match {
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

      case c: DeployContract =>
        c.ownerAddress.encodeAddress

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
    }
  }

  def getTo(contract: Transaction.Contract): Option[Address] = {

    ProtoUtils.fromContract(contract) match {
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

  def getAddresses(contract: Transaction.Contract): List[Address] = {
    List(getOwner(contract)) ++ getTo(contract).map(List(_)).getOrElse(List.empty)
  }

}
