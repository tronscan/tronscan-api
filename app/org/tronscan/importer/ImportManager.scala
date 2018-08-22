package org.tronscan.importer

import akka.actor.{Actor, ActorRef}
import javax.inject.{Inject, Named}
import org.tronscan.importer.ImportManager.Sync
import play.api.inject.ConfigurationProvider

object ImportManager {
  case class Sync()
  case class SyncAccounts()
}

/**
  * Handles the Full and Solidity Import
  */
class ImportManager @Inject() (
  configurationProvider: ConfigurationProvider,
  @Named("fullnode-reader") fullNodeReader: ActorRef,
  @Named("solidity-reader") solidityNodeReader: ActorRef) extends Actor {

  override def preStart(): Unit = {

    val syncSolidity = configurationProvider.get.get[Boolean]("sync.solidity")
    val syncFull = configurationProvider.get.get[Boolean]("sync.full")

    if (syncFull) {
      fullNodeReader ! Sync()
    }

    if (syncSolidity) {
      solidityNodeReader ! Sync()
    }
  }

  def receive = {
    case _ =>
  }
}
