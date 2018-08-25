package org.tronscan.test

import akka.actor.ActorSystem

trait WithActorSystem {
  implicit val system = ActorSystem()
}
