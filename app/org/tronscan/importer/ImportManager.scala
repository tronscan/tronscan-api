package org.tronscan.importer

import akka.actor.{Actor, ActorRef}
import javax.inject.{Inject, Named}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models.BlockModelRepository
import play.api.inject.ConfigurationProvider
import scala.concurrent.duration._

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

    import context.dispatcher

    val syncSolidity = configurationProvider.get.get[Boolean]("sync.solidity")
    val syncFull = configurationProvider.get.get[Boolean]("sync.full")

    if (syncFull) {
      context.system.scheduler.scheduleOnce(2.seconds, fullNodeReader, Sync())
    }

    if (syncSolidity) {
      context.system.scheduler.scheduleOnce(12.seconds, solidityNodeReader, Sync())
    }
  }

  def receive = {
    case _ =>
  }
}
