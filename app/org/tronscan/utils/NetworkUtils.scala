package org.tronscan.utils

import java.net.{InetSocketAddress, Socket}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object NetworkUtils {

  /**
    * Try to open a socket for the given ip and port
    */
  def ping(ip: String, port: Int, timeout: FiniteDuration = 5.seconds)(implicit executionContext: ExecutionContext) = {
    Future {
      AutoClose(new Socket()).map { socket =>
        socket.connect(new InetSocketAddress(ip, port), timeout.toMillis.toInt)
      }
      true
    }.recover {
      case _ =>
        false
    }
  }
}
