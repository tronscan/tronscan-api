package org.tronscan.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.contrib.Retry
import akka.stream.scaladsl.{Flow, RestartFlow, Sink, Source}
import akka.stream.{ActorMaterializer, FlowShape, Graph}
import org.specs2.matcher.{FutureMatchers, Matchers}
import org.specs2.mutable._
import org.tronscan.test.Awaiters
import org.tronscan.utils.FutureUtils

import concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Random, Success, Try}


class StreamSpec extends Specification with Matchers with FutureMatchers with Awaiters {

  "Stream Test" should {

//    "Retryable" in {
//
//      implicit val system = ActorSystem()
//      implicit val materializer = ActorMaterializer()
//
//      var counter = 0
//
//      val restarter= {
//
//        val failedElem: Try[Int] = Failure(new Exception("cooked failure"))
//        def flow[T] = Flow.fromFunction[(Int, T), (Try[Int], T)] {
//          case (i, j) if i % 2 == 0 =>
//            println("FAIL", i, j, failedElem)
//            (failedElem, j)
//          case (i, j)               =>
//            println("SUCCESS", i, j)
//            (Success(i + 1), j)
//        }
//
//        Retry(flow[Long]) { s =>
//          println("S", s)
//          if (s < 42) Some((s + 1, s + 1))
//          else None
//        }
//      }
//
//      val f = Source(1L to 1000L)
//          .map(i => (i, i))
//          .via(restarter)
//          .runWith(Sink.ignore)
//
////      awaitSync(f)
//
//      ok
//    }

    "RetryFlow" in {

      implicit val system = ActorSystem()
      implicit val materializer = ActorMaterializer()
      import scala.concurrent.ExecutionContext.Implicits.global
      implicit val scheduler = system.scheduler

      var counter = 0

      val restarter = Flow[Long]
        .mapAsync(1) { blockNum =>
          FutureUtils.retry(1.second, 30.seconds, 0.5) { () =>
            Future {
              if (new Random().nextInt(100) % 3 == 0) {
//                println("FAIL", blockNum)
                throw new Exception("fail")
              }

              blockNum
            }
          }
//            println("NUMBER", blockNum)

        }

      val f = Source(1L to 100L)
          .via(restarter)
          .map { l =>
            println("GOT", l)
            l
          }
          .runWith(Sink.ignore)

      awaitSync(f)

      ok
    }
  }
}
