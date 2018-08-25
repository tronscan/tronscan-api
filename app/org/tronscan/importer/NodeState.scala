package org.tronscan.importer

case class NodeState(
  solidityEnabled: Boolean = true,
  fullNodeBlock: Long,
  solidityBlock: Long,
  dbUnconfirmedBlock: Long,
  dbLatestBlock: Long,
  fullNodeBlockHash: String,
  solidityBlockHash: String,
  dbBlockHash: String) {

  /**
    * Full Node Synchronisation Progress
    */
  val fullNodeProgress: Double = (dbLatestBlock.toDouble / fullNodeBlock.toDouble) * 100

  /**
    * Solidity Synchronisation Progress
    */
  val solidityBlockProgress: Double = (dbUnconfirmedBlock.toDouble / solidityBlock.toDouble) * 100

  /**
    * How many blocks to sync from the full node
    */
  val fullNodeBlocksToSync = fullNodeBlock - dbLatestBlock

  /**
    * To which block the solidity block will be synced
    */
  val soliditySyncToBlock = if (solidityBlock > dbLatestBlock) dbLatestBlock else solidityBlock

  /**
    * To which block the solidity block will be synced
    */
  val solidityBlocksToSync = dbLatestBlock - dbUnconfirmedBlock

  /**
    * Total Progress
    */
  val totalProgress = (fullNodeProgress + solidityBlockProgress) / 2
}
