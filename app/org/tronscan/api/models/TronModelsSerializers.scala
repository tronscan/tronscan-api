package org.tronscan.api.models

import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.tron.common.utils.{ByteArray, ByteUtil, Sha256Hash}
import org.tronscan.Extensions._

class TronModelsSerializers(includeLinks: Boolean = false) {

  implicit val encodeBlock = new Encoder[org.tron.protos.Tron.Block] {
    def apply(block: org.tron.protos.Tron.Block): Json = Json.obj(
      "number" -> block.getBlockHeader.getRawData.number.asJson,
      "hash" -> block.hash.asJson,
      "timestamp" -> block.getBlockHeader.getRawData.timestamp.asJson,
      "txTrieRoot" -> ByteArray.toHexString(block.getBlockHeader.getRawData.txTrieRoot.toByteArray).asJson,
      "parentHash" -> Sha256Hash.wrap(block.getBlockHeader.getRawData.parentHash).toString.asJson,
      "witnessId" -> block.getBlockHeader.getRawData.witnessId.asJson,
      "witnessAddress" -> block.getBlockHeader.getRawData.witnessAddress.encodeAddress.asJson,
      "transactions" -> block.transactions.map(TransactionSerializer.serialize).asJson,
    )
  }

  implicit val encodeWitness = new Encoder[org.tron.protos.Tron.Witness] {
    def apply(witness: org.tron.protos.Tron.Witness): Json = Json.obj(
      "voteCount" -> witness.voteCount.asJson,
      "pubKey" -> ByteUtil.toHexString(witness.pubKey.toByteArray).asJson,
      "url" -> witness.url.asJson,
      "totalProduced" -> witness.totalProduced.asJson,
      "totalMissed" -> witness.totalMissed.asJson,
      "latestBlockNum" -> witness.latestBlockNum.asJson,
      "latestSlotNum" -> witness.latestSlotNum.asJson,
      "isJobs" -> witness.isJobs.asJson,
    )
  }

  implicit val encodeAccount = new Encoder[org.tron.protos.Tron.Account] {
    def apply(account: org.tron.protos.Tron.Account): Json = Json.obj(
      "accountName" -> account.accountName.decodeString.asJson,
      "type" -> account.`type`.value.asJson,
      "typeName" -> account.`type`.name.asJson,
      "address" -> account.address.encodeAddress.asJson,
      "balance" -> account.balance.asJson,
      "votes" -> account.votes.map(vote => Json.obj(
        "voteAddress" -> vote.voteAddress.encodeAddress.asJson,
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

  implicit val encodeAccountNet = new Encoder[org.tron.api.api.AccountNetMessage] {
    def apply(accountNet: org.tron.api.api.AccountNetMessage): Json = Json.obj(
        "freeNetUsed" -> accountNet.freeNetUsed.asJson,
        "freeNetLimit" -> accountNet.freeNetLimit.asJson,
        "netUsed" -> accountNet.netUsed.asJson,
        "netLimit" -> accountNet.netLimit.asJson,
        "assetNetUsed" -> accountNet.assetNetUsed.asJson,
        "assetNetLimit" -> accountNet.assetNetLimit.asJson,
        "totalNetLimit" -> accountNet.totalNetLimit.asJson,
        "totalNetWeight" -> accountNet.totalNetWeight.asJson,
    )
  }
}
