package org.tronscan.importer

import javax.inject.Inject
import org.tron.protos.Tron.Transaction
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AssetIssueContract, ParticipateAssetIssueContract, TransferAssetContract, TransferContract, UnfreezeBalanceContract, UpdateAssetContract, VoteWitnessContract, WitnessCreateContract, WitnessUpdateContract}
import org.tronscan.models._
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction
import org.tronscan.Extensions._


/**
  * Builds queries from transaction contracts
  */
class DatabaseImporter @Inject() (
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  voteWitnessContractModelRepository: VoteWitnessContractModelRepository,
  witnessModelRepository: WitnessModelRepository,
  assetIssueContractModelRepository: AssetIssueContractModelRepository,
  participateAssetIssueRepository: ParticipateAssetIssueModelRepository,
) {

  type ContractQueryBuilder = PartialFunction[(Transaction.Contract.ContractType, Transaction.Contract, Any), Seq[FixedSqlAction[Int, NoStream, Effect.Write]]]

  def importWitnessCreate: ContractQueryBuilder = {
    case (WitnessCreateContract, _, witness: WitnessModel) =>
      Seq(witnessModelRepository.buildInsertOrUpdate(witness))
  }

  def importWitnessUpdate: ContractQueryBuilder = {
    case (WitnessUpdateContract, _, witness: WitnessModel) =>
      Seq(witnessModelRepository.buildInsertOrUpdate(witness))
  }

  def importWitnessVote: ContractQueryBuilder = {
    case (VoteWitnessContract, _, votes: VoteWitnessList) =>
      voteWitnessContractModelRepository.buildUpdateVotes(votes.voterAddress, votes.votes)
  }

  def importAssetIssue: ContractQueryBuilder = {
    case (AssetIssueContract, _, assetIssue: AssetIssueContractModel) =>
      Seq(assetIssueContractModelRepository.buildInsert(assetIssue))
  }

  def importAssetUpdateIssue: ContractQueryBuilder = {
    case (UpdateAssetContract, _, updateAssetModel: UpdateAssetModel) =>
      Seq(assetIssueContractModelRepository.buildUpdateAsset(updateAssetModel))
  }

  def importParticipateAssetIssue: ContractQueryBuilder = {
    case (ParticipateAssetIssueContract, _, participate: ParticipateAssetIssueModel) =>
      Seq(participateAssetIssueRepository.buildInsert(participate))
  }

  def importUnfreezeBalance: ContractQueryBuilder = {
    case (UnfreezeBalanceContract, contract, _) =>
      val unfreezeBalanceContract = org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(contract.getParameter.value.toByteArray)
      Seq(voteWitnessContractModelRepository.buildDeleteVotesForAddress(unfreezeBalanceContract.ownerAddress.encodeAddress))
  }

  def importTransfers: ContractQueryBuilder = {
    case (TransferContract, _, transferModel: TransferModel) =>
      Seq(transferRepository.buildInsert(transferModel))

    case (TransferAssetContract, _, transferModel: TransferModel) =>
      Seq(transferRepository.buildInsert(transferModel))
  }

  def elseEmpty: ContractQueryBuilder = {
    case _ =>
      Seq.empty
  }

  def buildConfirmedEvents = {
    importWitnessCreate orElse
    importWitnessVote orElse
    importWitnessUpdate orElse
    importTransfers orElse
    importAssetIssue orElse
    importParticipateAssetIssue orElse
    importUnfreezeBalance orElse
    importAssetUpdateIssue
  }
}
