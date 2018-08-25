package org.tronscan.test

import akka.stream.scaladsl.{Flow, Sink}
import org.tronscan.domain.Types.Address

import scala.collection.mutable.ListBuffer

trait StreamSpecUtils {

  def buildListSink[A] = {
    val list = ListBuffer[A]()

    val flow = Flow[A]
        .alsoTo(Sink.foreach(a => list.append(a)))

    (list, flow)
  }

}
