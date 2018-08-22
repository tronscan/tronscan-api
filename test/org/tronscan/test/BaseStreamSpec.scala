package org.tronscan.test


import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.{Config, ConfigFactory}
import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAfterAll}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

trait BaseStreamSpec extends Specification with Matchers with AfterAll {

  protected implicit val system = {
//    def systemConfig = ConfigFactory.parseString(s"akka.stream.materializer.auto-fusing=$autoFusing")
//      .withFallback(config)
//      .withFallback(ConfigFactory.load())
    ActorSystem("default"/*, systemConfig*/)
  }

  protected implicit val mat = ActorMaterializer()

  def afterAll() = {
    Await.ready(system.terminate(), 42.seconds)
    mat.shutdown()
  }

//  protected def autoFusing: Boolean
  protected def config: Config = ConfigFactory.empty()
}
