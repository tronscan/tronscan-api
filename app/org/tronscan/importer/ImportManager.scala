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

class ImportManager @Inject() (
  configurationProvider: ConfigurationProvider,
  blockModelRepository: BlockModelRepository,
  @Named("fullnode-reader") fullNodeReader: ActorRef,
  @Named("solidity-reader") solidityNodeReader: ActorRef)  extends Actor {

  override def preStart(): Unit = {

    import context.dispatcher

    val syncSolidity = configurationProvider.get.get[Boolean]("sync.solidity")
    val syncFull = configurationProvider.get.get[Boolean]("sync.full")

    if (syncFull) {
      context.system.scheduler.schedule(6.seconds, 2.seconds, fullNodeReader, Sync())
    }

    if (syncSolidity) {
      context.system.scheduler.schedule(12.seconds, 2.seconds, solidityNodeReader, Sync())
    }
  }

  def receive = {
    case x =>
  }
}
