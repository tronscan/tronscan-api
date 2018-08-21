package org.tronscan.test

import akka.stream.ActorMaterializer

trait WithMaterializer {
  this: WithActorSystem =>
  implicit val materializer = ActorMaterializer()
}
