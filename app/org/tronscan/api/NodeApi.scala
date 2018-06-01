package org
package tronscan.api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import io.circe.generic.auto._
import io.circe.syntax._
import javax.inject.{Inject, Named}
import play.api.cache.Cached
import play.api.inject.ConfigurationProvider
import play.api.libs.json.Json
import play.api.mvc.InjectedController
import org.tronscan.models.BlockModelRepository
import org.tronscan.watchdog.NodeWatchDog.{NodeStatus, RequestStatus}

import scala.concurrent.duration._

class NodeApi @Inject()(
  actorSystem: ActorSystem,
  cached: Cached,
  blockModelRepository: BlockModelRepository,
  configurationProvider: ConfigurationProvider,
  @Named("node-watchdog") actorRef: ActorRef) extends InjectedController {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(10.seconds)

  def status =  cached.status(x => "node.status", 200, 1.minute) {
    Action.async { req =>

      (actorRef ? RequestStatus()).mapTo[NodeStatus].map { status =>

        val nodes = status.nodes.filter(_.permanent == false)

        Ok(Json.obj(
          "nodes" -> nodes.asJson,
          "status" -> status.status,
        ))
      }
    }
  }
}
