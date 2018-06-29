package org.tronscan.service

import javax.inject.Inject
import org.tronscan.domain.BlockChain
import org.tronscan.models.BlockModelRepository
import org.tronscan.Extensions._

import scala.async.Async.await

class SynchronisationService @Inject() (
  blockModelRepository: BlockModelRepository) {

  import scala.concurrent.ExecutionContext.Implicits.global

  /**
    * Reset all the blockchain data in the database
    */
  def resetDatabase() = {
    blockModelRepository.clearAll
  }

  /**
    * Checks if the given chain is the same as the database chain
    */
  def isSameChain(blockChain: BlockChain) = {
    for {
      dbBlock <- blockModelRepository.findByNumber(0)
      genesisBlock <- blockChain.genesisBlock
    } yield dbBlock.exists(_.hash == genesisBlock.hash)
  }

  /**
    * If the database has any blocks
    */
  def hasData = {
    blockModelRepository.findByNumber(0).map(_.isDefined)
  }

  /**
    * Last synchronized block in the database
    */
  def currentSynchronizedBlock = {
    blockModelRepository.findLatest
  }

  /**
    * Last confirmed block in the database
    */
  def currentConfirmedBlock = {
    blockModelRepository.findLatestUnconfirmed
  }
}
