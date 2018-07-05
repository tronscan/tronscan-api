package org.tronscan.actions

import javax.inject.Inject
import org.tron.common.repositories.StatsRepository

import scala.concurrent.ExecutionContext

class StatsOverview @Inject()(
  statsRepository: StatsRepository) {

  def execute(implicit executionContext: ExecutionContext) = {
    for {
      totalTransaction <- statsRepository.totalTransactions.map(_.map(x => (x._1, x._2)).toMap)
      avgBlockSize <- statsRepository.averageBlockSize.map(_.map(x => (x._1, x._2)).toMap)
      totalBlockCount <- statsRepository.blocksCreated.map(_.map(x => (x._1, x._2)).toMap)
      newAddressesSeen <- statsRepository.accountsCreated.map(_.map(x => (x._1, x._2)).toMap)
    } yield (totalTransaction, avgBlockSize, totalBlockCount, newAddressesSeen)
  }

}
