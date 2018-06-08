package org.tronscan.service

import javax.inject.Inject
import org.tronscan.protocol.{MainNetFormatter, StaticAddressFormatter, TestNetFormatter}
import play.api.inject.ConfigurationProvider

class Bootstrap @Inject() (configurationProvider: ConfigurationProvider) {

  val config = configurationProvider.get

  def getNet = config.get[String]("net.type")

  def loadAddressFormat() = {

    getNet.toLowerCase match {
      case "testnet" =>
        StaticAddressFormatter.formatter = new TestNetFormatter

      case "mainnet" =>
        StaticAddressFormatter.formatter = new MainNetFormatter
    }
  }



  loadAddressFormat()
}
