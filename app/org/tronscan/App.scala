package org.tronscan

import org.joda.time.DateTime
import play.api.libs.json._

object App {

  val defaultJodaDatePattern = "yyyy-MM-dd"

  implicit val dateWrites = new Writes[DateTime] {
    override def writes(o: DateTime): JsValue = {
      JsNumber(o.getMillis)
    }
  }

  implicit val dateReads = new Reads[DateTime] {
    override def reads(json: JsValue): JsResult[DateTime] = {
      JsSuccess(new DateTime(json.as[Long]))
    }
  }


}
