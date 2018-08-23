package org
package tronscan.api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import io.circe.generic.auto._
import io.circe.syntax._
import javax.inject.{Inject, Named}
import org.tronscan.models.BlockModelRepository
import org.tronscan.network.NetworkScanner.{NodeStatus, RequestStatus}
import play.api.cache.Cached
import play.api.inject.ConfigurationProvider
import play.api.libs.json.Json
import play.api.mvc.InjectedController

import scala.concurrent.duration._

class NodeApi @Inject()(
  actorSystem: ActorSystem,
  cached: Cached,
  blockModelRepository: BlockModelRepository,
  configurationProvider: ConfigurationProvider,
  @Named("node-watchdog") actorRef: ActorRef) extends InjectedController {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(10.seconds)
  val config = configurationProvider.get

  def status = {

    val action = Action.async { req =>

      (actorRef ? RequestStatus()).mapTo[NodeStatus].map { status =>

        val nodes = status.nodes.filter(_.permanent == false)

        Ok(Json.obj(
          "nodes" -> nodes.asJson,
          "status" -> status.status,
        ))
      }
    }

    if (config.get[Boolean]("cache.api.nodes")) {
      cached.status(x => "node.status", 200, 1.minute)(action)
    } else {
      action
    }

  }
}
