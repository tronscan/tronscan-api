package org
package tron.common.repositories

import com.google.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.Repository
import play.api.db.slick.DatabaseConfigProvider

@Singleton()
class StatsRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends Repository {

  // TODO check this by looking up the genesis block
  val chainStartedAt = "2018-06-25T00:00:00"

  import scala.concurrent.ExecutionContext.Implicits.global

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
       generate_series('#$chainStartedAt', CURRENT_DATE, '1 day'::interval) as d
    """.as[(String, Int)]
  }.map(_.map(x => (DateTime.parse(x._1).getMillis, x._2)))

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
       generate_series('#$chainStartedAt', CURRENT_DATE, '1 day'::interval) as d
    """.as[(String, Int)]
  }.map(_.map(x => (DateTime.parse(x._1).getMillis, x._2)))

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
       generate_series('#$chainStartedAt', CURRENT_DATE, '1 day'::interval) as d
    """.as[(String, Int)]
  }.map(_.map(x => (DateTime.parse(x._1).getMillis, x._2)))

  def averageBlockSize = run {
    sql"""
     SELECT
       date_trunc('day', d)::date as d,
       (
         SELECT
           ceil(AVG(size)) as avg_size
         FROM blocks
         WHERE
           date_trunc('day', date_created) = date_trunc('day', d)
       )
     FROM
       generate_series('#$chainStartedAt', CURRENT_DATE, '1 day'::interval) as d
    """.as[(String, Int)]
  }.map(_.map(x => (DateTime.parse(x._1).getMillis, x._2)))

  def totalBlockSize = run {
    sql"""
     SELECT
       date_trunc('day', d)::date as d,
       (
         SELECT
           SUM(size) as size
         FROM blocks
         WHERE
           date_created < date_trunc('day', d)
       )
     FROM
       generate_series('#$chainStartedAt', CURRENT_DATE, '1 day'::interval) as d
    """.as[(String, Int)]
  }.map(_.map(x => (DateTime.parse(x._1).getMillis, x._2)))


}