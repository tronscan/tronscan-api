package org.tronscan.streams

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import monix.eval.Task
import monix.execution.Scheduler
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.{FutureMatchers, Matchers}
import org.specs2.mutable._
import org.specs2.concurrent.ExecutionEnv
import org.tronscan.test.Awaiters

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._



class StreamSpec(implicit ee: ExecutionEnv) extends Specification with Matchers with FutureMatchers with Awaiters {

  "Stream Test" should {

    "Spec" in {

      implicit val system = ActorSystem()
      implicit val materializer = ActorMaterializer()

      awaitSync(system.terminate())
      materializer.shutdown()

      ok
    }
  }
}
