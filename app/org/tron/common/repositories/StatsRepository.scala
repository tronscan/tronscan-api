package org.tron.common.repositories

import com.google.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.tronscan.db.{Repository, TableRepository}
import org.tronscan.models.{AccountModel, AccountModelTable}
import play.api.db.slick.DatabaseConfigProvider
import slick.lifted.TableQuery
import org.tronscan.db.PgProfile.api._

@Singleton()
class StatsRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends Repository {

  lazy val table = TableQuery[AccountModelTable]

  // TODO check this by looking up the genesis block
  val chainStartedAt = DateTime.parse("2018-06-25T00:00:00")

  def accountsCreated = run {
    sql"""
     SELECT
       date_trunc('day', d)::date as d,
       (
         SELECT
           COUNT(*) as accounts_created
         FROM accounts
         WHERE
           date_trunc('day', date_created) = date_trunc('day', d)
       )
     FROM
       generate_series($chainStartedAt, CURRENT_DATE - 1, '1 day'::interval) as d
    """.as[(DateTime, Int)]
  }

  def blocksCreated = run {
    sql"""
     SELECT
       date_trunc('day', d)::date as d,
       (
         SELECT
           COUNT(*) as blocks_created
         FROM blocks
         WHERE
           date_created < date_trunc('day', d)
       )
     FROM
       generate_series($chainStartedAt, CURRENT_DATE - 1, '1 day'::interval) as d
    """.as[(DateTime, Int)]
  }

  def totalTransactions = run {
    sql"""
     SELECT
       date_trunc('day', d)::date as d,
       (
         SELECT
           COUNT(*) as trx_created
         FROM transactions
         WHERE
           date_created < date_trunc('day', d)
       )
     FROM
       generate_series($chainStartedAt, CURRENT_DATE - 1, '1 day'::interval) as d
    """.as[(DateTime, Int)]
  }

  def averageBlockSize = run {
    sql"""
     SELECT
       date_trunc('day', d)::date as d,
       (
         SELECT
           AVG(size) as avg_size
         FROM blocks
         WHERE
           date_created = date_trunc('day', d)
       )
     FROM
       generate_series($chainStartedAt, CURRENT_DATE - 1, '1 day'::interval) as d
    """.as[(DateTime, Int)]
  }


}