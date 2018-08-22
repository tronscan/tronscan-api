package org.tronscan.utils

import akka.stream.scaladsl.Flow
import shapeless.T

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object StreamUtils {

  /**
    * Only pass distinct values
    */
  def distinct[T] = Flow[T]
    .statefulMapConcat { () =>
      var ids = Set.empty[T]
      ip => {
        if (ids.contains(ip)) {
          List.empty
        } else {
          ids = ids + ip
          List(ip)
        }
      }
    }
}