package org.tronscan.api.models

import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.tron.common.utils.{ByteArray, Sha256Hash}
import org.tron.protos.Tron.Transaction.Result.code
import org.tronscan.Extensions._

object TronModelsSerializers {

  implicit val encodeBlock = new Encoder[org.tron.protos.Tron.Block] {
    def apply(block: org.tron.protos.Tron.Block): Json = Json.obj(
      "number" -> block.getBlockHeader.getRawData.number.asJson,
      "timestamp" -> block.getBlockHeader.getRawData.timestamp.asJson,
      "txTrieRoot" -> ByteArray.toHexString(block.getBlockHeader.getRawData.txTrieRoot.toByteArray).asJson,
      "parentHash" -> Sha256Hash.wrap(block.getBlockHeader.getRawData.parentHash).toString.asJson,
      "witnessId" -> block.getBlockHeader.getRawData.witnessId.asJson,
      "witnessAddress" -> block.getBlockHeader.getRawData.witnessAddress.toAddress.asJson,
      "transactions" -> block.transactions.map(TransactionSerializer.serialize).asJson,
    )
  }

  implicit val encodeAccount = new Encoder[org.tron.protos.Tron.Account] {
    def apply(account: org.tron.protos.Tron.Account): Json = Json.obj(
      "accountName" -> account.accountName.decodeString.asJson,
      "type" -> account.`type`.value.asJson,
      "typeName" -> account.`type`.name.asJson,
      "address" -> account.address.toAddress.asJson,
      "balance" -> account.balance.asJson,
      "votes" -> account.votes.map(vote => Json.obj(
        "voteAddress" -> vote.voteAddress.toAddress.asJson,
        "voteCount" -> vote.voteCount.asJson,
      )).asJson,
      "asset" -> account.asset.asJson,
      "frozen" -> account.frozen.map(frozen => Json.obj(
        "frozenBalance" -> frozen.frozenBalance.asJson,
        "expireTime" -> frozen.expireTime.asJson,
      )).asJson,
      "netUsage" -> account.netUsage.asJson,
      "createTime" -> account.createTime.asJson,
      "latestOperationTime" -> account.latestOprationTime.asJson,
      "allowance" -> account.allowance.asJson,
      "latestWithdrawTime" -> account.latestWithdrawTime.asJson,
      "code" -> account.code.decodeString.asJson,
      "isWitness" -> account.isWitness.asJson,
      "isCommittee" -> account.isCommittee.asJson,
      "frozenSupply" -> account.frozenSupply.map(frozen => Json.obj(
        "frozenBalance" -> frozen.frozenBalance.asJson,
        "expireTime" -> frozen.expireTime.asJson,
      )).asJson,
      "assetIssuedName" -> account.assetIssuedName.decodeString.asJson,
      "latestAssetOperationTime" -> account.latestAssetOperationTime.asJson,
      "freeNetUsage" -> account.freeNetUsage.asJson,
      "freeAssetNetUsage" -> account.freeAssetNetUsage.asJson,
      "latestConsumeTime" -> account.latestConsumeTime.asJson,
      "latestConsumeFreeTime" -> account.latestConsumeFreeTime.asJson,
    )
  }
}
