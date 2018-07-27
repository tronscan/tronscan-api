package org.tronscan.actions

import java.sql.Timestamp
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}

import javax.inject.Inject
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletGrpc.Wallet
import org.tronscan.Extensions._
import org.tronscan.models.{AccountModelRepository, BlockModelRepository, WitnessModelRepository}

import scala.concurrent.ExecutionContext

class RepresentativeListReader @Inject() (
  witnessModelRepository: WitnessModelRepository,
  accountModelRepository: AccountModelRepository,
  blockModelRepository: BlockModelRepository,
  wallet: Wallet) {

  def execute(implicit executionContext: ExecutionContext) = {
    for {
      witnesses <- wallet.listWitnesses(EmptyMessage()).map(_.witnesses)
      accounts <- accountModelRepository.findByAddresses(witnesses.map(_.address.encodeAddress)).map(_.map(x => x.address -> x.name).toMap)
      witnessTrx <- witnessModelRepository.findTransactionsByWitness()
    } yield (witnesses, accounts, witnessTrx)
  }

  def getMaintenanceTime(): String = {
    val utcZoneId = ZoneId.of("UTC")
    val zonedDateTime = ZonedDateTime.now
    val utcDateTime = zonedDateTime.withZoneSameInstant(utcZoneId)

    val currentHour = utcDateTime.getHour
    val today = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(utcDateTime)
    var maintenanceTime = today
    if (currentHour>2 && currentHour<=8) {
      maintenanceTime = maintenanceTime + " " + "00:00:00+00"
    }
    if (currentHour>8 && currentHour<=14) {
      maintenanceTime = maintenanceTime + " " + "06:00:00+00"
    }
    if (currentHour>14 && currentHour<=20) {
      maintenanceTime = maintenanceTime + " " + "12:00:00+00"
    }
    if (currentHour>20) {
      maintenanceTime = maintenanceTime + " " + "18:00:00+00"
    }
    maintenanceTime
  }

  def getMaintenanceTimeStamp() = {
    //DateTimeZone.setDefault(DateTimeZone.UTC)
    val now = DateTime.now
    val currentHour = now.getHourOfDay
    val today = now.toString("yyyy-MM-dd")
    var maintenanceTime = today

    //val ranges = for (i <- 0 to 18 by 6) yield (i, i + 6)

    //val currentTime = ranges.find(time => currentHour < time._2 && currentHour > time._1)

    if (currentHour>0 && currentHour<=6) {
      maintenanceTime = maintenanceTime + "T" + "00:00:00"
    }
    if (currentHour>6 && currentHour<=12) {
      maintenanceTime = maintenanceTime + "T" + "06:00:00"
    }
    if (currentHour>12 && currentHour<=18) {
      maintenanceTime = maintenanceTime + "T" + "12:00:00"
    }
    if (currentHour>18) {
      maintenanceTime = maintenanceTime + "T" + "18:00:00"
    }

    //ISODateTimeFormat.dateTimeParser().parseDateTime(maintenanceTime).getMillis/1000


    maintenanceTime
  }


  def maintenanceStatistic(implicit executionContext: ExecutionContext) = {
    val maintenanceTime = getMaintenanceTimeStamp()
    println(maintenanceTime + "----------------------------")
    for {
      blocks <- blockModelRepository.maintenanceStatistic(maintenanceTime)
    } yield blocks
  }

}
