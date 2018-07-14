package org.tronscan.actions

import javax.inject.Inject
import org.tron.common.repositories.StatsRepository

import scala.concurrent.ExecutionContext

class StatsOverview @Inject()(
  statsRepository: StatsRepository) {

  def execute(implicit executionContext: ExecutionContext) = {

    def toMap(list: Seq[(Long, Int)]) = list.map(x => (x._1, x._2)).toMap

    for {
      totalTransaction <- statsRepository.totalTransactions
      avgBlockSize <- statsRepository.averageBlockSize
      totalBlockCount <- statsRepository.blocksCreated
      newAddressesSeen <- statsRepository.accountsCreated
      totalBlockSize <- statsRepository.totalBlockSize
    } yield (toMap(totalTransaction), toMap(avgBlockSize), toMap(totalBlockCount), toMap(newAddressesSeen), toMap(totalBlockSize))
  }

}
