package org
package tronscan.api.models

import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json => Js}
import org.joda.time.DateTime
import org.tron.common.utils.{Base58, ByteArray, Crypto}
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AccountCreateContract, AccountUpdateContract, AssetIssueContract, CreateSmartContract, ExchangeCreateContract, ExchangeInjectContract, ExchangeTransactionContract, ExchangeWithdrawContract, FreezeBalanceContract, ParticipateAssetIssueContract, ProposalApproveContract, ProposalCreateContract, ProposalDeleteContract, TransferAssetContract, TransferContract, TriggerSmartContract, UnfreezeAssetContract, UnfreezeBalanceContract, UpdateAssetContract, VoteAssetContract, VoteWitnessContract, WithdrawBalanceContract, WitnessCreateContract, WitnessUpdateContract}
import org.tron.protos.Tron.{AccountType, Transaction}
import org.tronscan.Extensions._
import org.tronscan.protocol.MainNetFormatter

object TransactionSerializer {

  implicit val addressFormatter = new MainNetFormatter

  implicit val encodeAssetIssueContract = new Encoder[org.tron.protos.Contract.AssetIssueContract] {
    def apply(assetIssueContract: org.tron.protos.Contract.AssetIssueContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(assetIssueContract.ownerAddress.toByteArray).asJson,
      "name" -> new String(assetIssueContract.name.toByteArray).trim.asJson,
      "abbr" -> new String(assetIssueContract.abbr.toByteArray).trim.asJson,
      "totalSupply" -> assetIssueContract.totalSupply.asJson,
      "frozenSupply" -> assetIssueContract.frozenSupply.map(frozen => Js.obj(
          "amount" -> frozen.frozenAmount.asJson,
          "days" -> frozen.frozenDays.asJson,
        ).asJson
      ).asJson,
      "trxNum" -> assetIssueContract.trxNum.asJson,
      "num" -> assetIssueContract.num.asJson,
      "startTime" -> new DateTime(assetIssueContract.startTime).asJson,
      "endTime" -> new DateTime(assetIssueContract.endTime).asJson,
      "voteScore" -> assetIssueContract.voteScore.asJson,
      "description" -> new String(assetIssueContract.description.toByteArray).asJson,
      "url" -> new String(assetIssueContract.url.toByteArray).asJson,
    )
  }

  implicit val encodeUpdateAssetContract = new Encoder[org.tron.protos.Contract.UpdateAssetContract] {
    def apply(contract: org.tron.protos.Contract.UpdateAssetContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(contract.ownerAddress.toByteArray).asJson,
      "newPublicLimit" -> contract.newPublicLimit.asJson,
      "newLimit" -> contract.newLimit.asJson,
      "description" -> new String(contract.description.toByteArray).asJson,
      "url" -> new String(contract.url.toByteArray).asJson,
    )
  }

  implicit val decodeUpdateAssetContract = new Decoder[org.tron.protos.Contract.UpdateAssetContract] {
    def apply(c: HCursor) = {
      for {
        from <- c.downField("ownerAddress").as[String]
        description <- c.downField("description").as[String]
        url <- c.downField("url").as[String]
      } yield {
        org.tron.protos.Contract.UpdateAssetContract(
          ownerAddress = from.decodeAddress,
          description = description.encodeString,
          url = url.encodeString,
          newLimit = c.downField("newLimit").as[Long].getOrElse(0L),
          newPublicLimit = c.downField("newPublicLimit").as[Long].getOrElse(0L)
        )
      }
    }
  }

  implicit val encodeTransferContract = new Encoder[org.tron.protos.Contract.TransferContract] {
    def apply(transferContract: org.tron.protos.Contract.TransferContract): Js = Js.obj(
      "from" -> Base58.encode58Check(transferContract.ownerAddress.toByteArray).asJson,
      "to" -> Base58.encode58Check(transferContract.toAddress.toByteArray).asJson,
      "amount" -> transferContract.amount.asJson,
    )
  }

  implicit val decodeTransferContract = new Decoder[org.tron.protos.Contract.TransferContract] {
    def apply(c: HCursor) = {
      for {
        from <- c.downField("ownerAddress").as[String]
        to <- c.downField("toAddress").as[String]
        amount <- c.downField("amount").as[Long]
      } yield {
        org.tron.protos.Contract.TransferContract(
          ownerAddress = from.decodeAddress,
          toAddress = to.decodeAddress,
          amount = amount
        )
      }
    }
  }

  implicit val encodeTransferAssetContract = new Encoder[org.tron.protos.Contract.TransferAssetContract] {
    def apply(transferAssetContract: org.tron.protos.Contract.TransferAssetContract): Js = Js.obj(
      "from" -> transferAssetContract.ownerAddress.encodeAddress.asJson,
      "to" -> transferAssetContract.toAddress.encodeAddress.asJson,
      "amount" -> transferAssetContract.amount.asJson,
      "token" -> transferAssetContract.assetName.decodeString.asJson
    )
  }

  implicit val decodeTransferAssetContract = new Decoder[org.tron.protos.Contract.TransferAssetContract] {
    def apply(c: HCursor) = {
      for {
        from <- c.downField("ownerAddress").as[String]
        assetName <- c.downField("assetName").as[String]
        to <- c.downField("toAddress").as[String]
        amount <- c.downField("amount").as[Long]
      } yield {
        org.tron.protos.Contract.TransferAssetContract(
          ownerAddress = from.decodeAddress,
          toAddress = to.decodeAddress,
          assetName = assetName.encodeString,
          amount = amount
        )
      }
    }
  }

  implicit val encodeParticipateAssetIssueContract = new Encoder[org.tron.protos.Contract.ParticipateAssetIssueContract] {
    def apply(participateAssetIssueContract: org.tron.protos.Contract.ParticipateAssetIssueContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(participateAssetIssueContract.ownerAddress.toByteArray).asJson,
      "toAddress" -> Base58.encode58Check(participateAssetIssueContract.toAddress.toByteArray).asJson,
      "amount" -> participateAssetIssueContract.amount.asJson,
      "token" -> new String(participateAssetIssueContract.assetName.toByteArray).asJson,
    )
  }


  implicit val encodeWitnessUpdateContract = new Encoder[org.tron.protos.Contract.WitnessUpdateContract] {
    def apply(witnessUpdateContract: org.tron.protos.Contract.WitnessUpdateContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(witnessUpdateContract.ownerAddress.toByteArray).asJson,
      "url" -> new String(witnessUpdateContract.updateUrl.toByteArray).asJson,
    )
  }

  implicit val encodeWitnessCreateContract = new Encoder[org.tron.protos.Contract.WitnessCreateContract] {
    def apply(contract: org.tron.protos.Contract.WitnessCreateContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(contract.ownerAddress.toByteArray).asJson,
      "url" -> new String(contract.url.toByteArray).asJson,
    )
  }

  implicit val encodeVoteWitnessContract = new Encoder[org.tron.protos.Contract.VoteWitnessContract] {
    def apply(voteWitnessContract: org.tron.protos.Contract.VoteWitnessContract): Js = Js.obj(
      "ownerAddress" -> voteWitnessContract.ownerAddress.encodeAddress.asJson,
      "votes" -> voteWitnessContract.votes.map { vote =>
        Js.obj(
          "voteAddress" -> vote.voteAddress.encodeAddress.asJson,
          "voteCount" -> vote.voteCount.asJson,
        )
      }.asJson
    )
  }

  implicit val decodeVoteWitnessContractVote = new Decoder[org.tron.protos.Contract.VoteWitnessContract.Vote] {
    def apply(c: HCursor) = {
      for {
        voteAddress <- c.downField("voteAddress").as[String]
        voteCount <- c.downField("voteCount").as[Long]
      } yield org.tron.protos.Contract.VoteWitnessContract.Vote(
        voteAddress = voteAddress.decodeAddress,
        voteCount = voteCount
      )
    }
  }

  implicit val decodeVoteWitnessContract = new Decoder[org.tron.protos.Contract.VoteWitnessContract] {
    def apply(c: HCursor) = {
      for {
        ownerAddress <- c.downField("ownerAddress").as[String]
        votes <- c.downField("votes").as[List[org.tron.protos.Contract.VoteWitnessContract.Vote]]
      } yield org.tron.protos.Contract.VoteWitnessContract(
        ownerAddress = ownerAddress.decodeAddress,
        votes = votes
      )
    }
  }

  implicit val encodeAccountUpdateContract = new Encoder[org.tron.protos.Contract.AccountUpdateContract] {
    def apply(accountUpdateContract: org.tron.protos.Contract.AccountUpdateContract): Js = Js.obj(
      "ownerAddress" -> accountUpdateContract.ownerAddress.encodeAddress.asJson,
      "name" -> new String(accountUpdateContract.accountName.toByteArray).asJson,
    )
  }

  implicit val decodeAccountUpdateContract = new Decoder[org.tron.protos.Contract.AccountUpdateContract] {
    def apply(c: HCursor) = {
      for {
        ownerAddress <- c.downField("ownerAddress").as[String]
        name <- c.downField("accountName").as[String]
      } yield org.tron.protos.Contract.AccountUpdateContract(
        ownerAddress = ownerAddress.decodeAddress,
        accountName = name.encodeString
      )
    }
  }

  implicit val encodeAccountCreateContract = new Encoder[org.tron.protos.Contract.AccountCreateContract] {
    def apply(contract: org.tron.protos.Contract.AccountCreateContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "accountAddress" -> contract.accountAddress.encodeAddress.asJson,
      "type" -> contract.`type`.value.asJson,
    )
  }

  implicit val decodeAccountCreateContract = new Decoder[org.tron.protos.Contract.AccountCreateContract] {
    def apply(c: HCursor) = {
      for {
        ownerAddress <- c.downField("ownerAddress").as[String]
        accountAddress <- c.downField("accountAddress").as[String]
      } yield {
        org.tron.protos.Contract.AccountCreateContract(
          ownerAddress = ownerAddress.decodeAddress,
          accountAddress = accountAddress.decodeAddress,
          `type` = AccountType.Normal
        )
      }
    }
  }

  implicit val encodeVoteAssetContract = new Encoder[org.tron.protos.Contract.VoteAssetContract] {
    def apply(contract: org.tron.protos.Contract.VoteAssetContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(contract.ownerAddress.toByteArray).asJson,
    )
  }

  implicit val encodeFreezeBalanceContract = new Encoder[org.tron.protos.Contract.FreezeBalanceContract] {
    def apply(contract: org.tron.protos.Contract.FreezeBalanceContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(contract.ownerAddress.toByteArray).asJson,
      "frozenDuration" -> contract.frozenDuration.asJson,
      "frozenBalance" -> contract.frozenBalance.asJson,
    )
  }

  implicit val encodeUnfreezeBalanceContract = new Encoder[org.tron.protos.Contract.UnfreezeBalanceContract] {
    def apply(contract: org.tron.protos.Contract.UnfreezeBalanceContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(contract.ownerAddress.toByteArray).asJson,
    )
  }

  implicit val encodeWithdrawBalanceContract = new Encoder[org.tron.protos.Contract.WithdrawBalanceContract] {
    def apply(contract: org.tron.protos.Contract.WithdrawBalanceContract): Js = Js.obj(
      "ownerAddress" -> Base58.encode58Check(contract.ownerAddress.toByteArray).asJson,
    )
  }

  implicit val decodeWithdrawBalanceContract = new Decoder[org.tron.protos.Contract.WithdrawBalanceContract] {
    def apply(c: HCursor) = {
      for {
        ownerAddress <- c.downField("ownerAddress").as[String]
      } yield {
        org.tron.protos.Contract.WithdrawBalanceContract(
          ownerAddress = ownerAddress.decodeAddress
        )
      }
    }
  }

  implicit val encodeUnfreezeAssetContract = new Encoder[org.tron.protos.Contract.UnfreezeAssetContract] {
    def apply(contract: org.tron.protos.Contract.UnfreezeAssetContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
    )
  }


  implicit val encodeProposal = new Encoder[org.tron.protos.Tron.Proposal] {
    def apply(contract: org.tron.protos.Tron.Proposal): Js = Js.obj(
      "proposalId" -> contract.proposalId.asJson,
      "proposerAddress" -> contract.proposerAddress.encodeAddress.asJson,
      "parameters" -> contract.parameters.asJson,
      "expirationTime" -> contract.expirationTime.asJson,
      "createTime" -> contract.createTime.asJson,
      "approvals" -> contract.approvals.map(_.encodeAddress).asJson,
      "state" -> contract.state.name.asJson,
    )
  }

  implicit val encodeExchange = new Encoder[org.tron.protos.Tron.Exchange] {
    def apply(contract: org.tron.protos.Tron.Exchange): Js = Js.obj(
      "exchangeId" -> contract.exchangeId.asJson,
      "creatorAddress" -> contract.creatorAddress.encodeAddress.asJson,
      "createTime" -> contract.createTime.asJson,
      "firstTokenId" -> contract.firstTokenId.decodeString.asJson,
      "firstTokenBalance" -> contract.firstTokenBalance.asJson,
      "secondTokenId" -> contract.secondTokenId.decodeString.asJson,
      "secondTokenBalance" -> contract.secondTokenBalance.asJson,
    )
  }

  implicit val encodeChainParameter = new Encoder[org.tron.protos.Tron.ChainParameters.ChainParameter] {
    def apply(contract: org.tron.protos.Tron.ChainParameters.ChainParameter): Js = Js.obj(
      "key" -> contract.key.asJson,
      "value" -> contract.value.asJson,
    )
  }

  implicit val encodeProposalCreateContract = new Encoder[org.tron.protos.Contract.ProposalCreateContract] {
    def apply(contract: org.tron.protos.Contract.ProposalCreateContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "parameters" -> contract.parameters.asJson,
    )
  }

  implicit val encodeProposalApproveContract = new Encoder[org.tron.protos.Contract.ProposalApproveContract] {
    def apply(contract: org.tron.protos.Contract.ProposalApproveContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "proposalId" -> contract.proposalId.asJson,
      "isAddApproval" -> contract.isAddApproval.asJson,
    )
  }

  implicit val decodeProposalApproveContract = new Decoder[org.tron.protos.Contract.ProposalApproveContract] {
    def apply(c: HCursor) = {
      for {
        ownerAddress <- c.downField("ownerAddress").as[String]
        proposalId <- c.downField("proposalId").as[Long]
        isAddApproval <- c.downField("approve").as[Boolean]
      } yield {
        org.tron.protos.Contract.ProposalApproveContract(
          ownerAddress = ownerAddress.decodeAddress,
          proposalId = proposalId,
          isAddApproval = isAddApproval
        )
      }
    }
  }

  implicit val encodeProposalDeleteContract = new Encoder[org.tron.protos.Contract.ProposalDeleteContract] {
    def apply(contract: org.tron.protos.Contract.ProposalDeleteContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "proposalId" -> contract.proposalId.asJson,
    )
  }

  implicit val encodeCreateSmartContract = new Encoder[org.tron.protos.Contract.CreateSmartContract] {
    def apply(contract: org.tron.protos.Contract.CreateSmartContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
    )
  }

  implicit val encodeTriggerSmartContract = new Encoder[org.tron.protos.Contract.TriggerSmartContract] {
    def apply(contract: org.tron.protos.Contract.TriggerSmartContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "contractAddress" -> contract.contractAddress.encodeAddress.asJson,
      "callValue" -> contract.callValue.asJson,
      "data" -> ByteArray.toHexString(contract.data.toByteArray).asJson,
    )
  }

  implicit val encodeExchangeCreateContract = new Encoder[org.tron.protos.Contract.ExchangeCreateContract] {
    def apply(contract: org.tron.protos.Contract.ExchangeCreateContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "firstTokenId" -> contract.firstTokenId.decodeString.asJson,
      "firstTokenBalance" -> contract.firstTokenBalance.asJson,
      "secondTokenId" -> contract.secondTokenId.decodeString.asJson,
      "secondTokenBalance" -> contract.secondTokenBalance.asJson,
    )
  }

  implicit val encodeExchangeInjectContract = new Encoder[org.tron.protos.Contract.ExchangeInjectContract] {
    def apply(contract: org.tron.protos.Contract.ExchangeInjectContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "exchangeId" -> contract.exchangeId.asJson,
      "tokenId" -> contract.tokenId.decodeString.asJson,
      "quant" -> contract.quant.asJson,
    )
  }

  implicit val encodeExchangeWithdrawContract = new Encoder[org.tron.protos.Contract.ExchangeWithdrawContract] {
    def apply(contract: org.tron.protos.Contract.ExchangeWithdrawContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "exchangeId" -> contract.exchangeId.asJson,
      "tokenId" -> contract.tokenId.decodeString.asJson,
      "quant" -> contract.quant.asJson,
    )
  }

  implicit val encodeExchangeTransactionContract = new Encoder[org.tron.protos.Contract.ExchangeTransactionContract] {
    def apply(contract: org.tron.protos.Contract.ExchangeTransactionContract): Js = Js.obj(
      "ownerAddress" -> contract.ownerAddress.encodeAddress.asJson,
      "exchangeId" -> contract.exchangeId.asJson,
      "tokenId" -> contract.tokenId.decodeString.asJson,
      "quant" -> contract.quant.asJson,
    )
  }

  def serializeContract(contract: Transaction.Contract, includeType: Boolean = false) = {
    val contractJson = contract.`type` match {
      case AccountCreateContract =>
        org.tron.protos.Contract.AccountCreateContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case TransferContract =>
        org.tron.protos.Contract.TransferContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case TransferAssetContract =>
        org.tron.protos.Contract.TransferAssetContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case VoteAssetContract =>
        org.tron.protos.Contract.VoteAssetContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case VoteWitnessContract =>
        org.tron.protos.Contract.VoteWitnessContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case AssetIssueContract =>
        org.tron.protos.Contract.AssetIssueContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case ParticipateAssetIssueContract =>
        org.tron.protos.Contract.ParticipateAssetIssueContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case WitnessCreateContract =>
        org.tron.protos.Contract.WitnessCreateContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case WitnessUpdateContract =>
        org.tron.protos.Contract.WitnessUpdateContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case FreezeBalanceContract =>
        org.tron.protos.Contract.FreezeBalanceContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case UnfreezeBalanceContract =>
        org.tron.protos.Contract.UnfreezeBalanceContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case AccountUpdateContract =>
        org.tron.protos.Contract.AccountUpdateContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case WithdrawBalanceContract =>
        org.tron.protos.Contract.WithdrawBalanceContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case UnfreezeAssetContract =>
        org.tron.protos.Contract.UnfreezeAssetContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case UpdateAssetContract =>
        org.tron.protos.Contract.UpdateAssetContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case ProposalCreateContract =>
        org.tron.protos.Contract.ProposalCreateContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case ProposalApproveContract =>
        org.tron.protos.Contract.ProposalApproveContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case ProposalDeleteContract =>
        org.tron.protos.Contract.ProposalDeleteContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case CreateSmartContract =>
        org.tron.protos.Contract.CreateSmartContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case TriggerSmartContract =>
        org.tron.protos.Contract.TriggerSmartContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      //      case BuyStorageBytesContract =>
      //        org.tron.protos.Contract.BuyStorageBytesContract.parseFrom(any.value.toByteArray)

      //      case BuyStorageContract =>
      //        org.tron.protos.Contract.BuyStorageContract.parseFrom(any.value.toByteArray)

      //      case SellStorageContract =>
      //        org.tron.protos.Contract.SellStorageContract.parseFrom(any.value.toByteArray)

      case ExchangeCreateContract =>
        org.tron.protos.Contract.ExchangeCreateContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case ExchangeInjectContract =>
        org.tron.protos.Contract.ExchangeInjectContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case ExchangeWithdrawContract =>
        org.tron.protos.Contract.ExchangeWithdrawContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case ExchangeTransactionContract =>
        org.tron.protos.Contract.ExchangeTransactionContract.parseFrom(contract.getParameter.value.toByteArray).asJson

      case _ =>
        Js.obj()
    }


    if (includeType) {
      contractJson
        .deepMerge(Js.obj(
          "contractType" -> contract.`type`.toString().asJson,
          "contractTypeId" -> contract.`type`.value.asJson
        ))
    } else {
      contractJson
    }
  }

  def serialize(transaction: Transaction) = Js.obj(
    "hash" -> transaction.hash.asJson,
    "timestamp" -> transaction.getRawData.timestamp.asJson,
    "contracts" -> transaction.getRawData.contract.map(contract => serializeContract(contract, includeType = true)).asJson,
    "data" -> transaction.getRawData.data.decodeString.asJson,
    "bandwidth" -> transaction.serializedSize.asJson,
    "signatures" -> transaction.signature.map { signature =>
      Js.obj(
        "bytes" -> Crypto.getBase64FromByteString(signature).asJson,
//        "address" -> ByteString.copyFrom(ECKey.signatureToAddress(Sha256Hash.of(transaction.getRawData.toByteArray).getBytes, Crypto.getBase64FromByteString(transaction.signature(0)))).encodeAddress.asJson,
      )
    }.asJson,
  )
}
