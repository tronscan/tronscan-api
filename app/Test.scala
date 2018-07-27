import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import java.util.Calendar

import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}

object Test {


  def getMaintenanceTimeStamp(): Long = {
    DateTimeZone.setDefault(DateTimeZone.UTC)
    val now = DateTime.now
    val currentHour = now.getHourOfDay
    val today = now.toString("yyyy-MM-dd")
    var maintenanceTime = today
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
    ISODateTimeFormat.dateTimeParser().parseDateTime(maintenanceTime).getMillis/1000
  }


  def main(args: Array[String]) {
   // DateTimeZone.setDefault(DateTimeZone.UTC)
    val x = getMaintenanceTimeStamp()
    println(x)

  }
}