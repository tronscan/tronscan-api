package org.tronscan.cache

import akka.actor.Actor
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.stream.scaladsl.{Sink, Source}
import javax.inject.Inject
import org.apache.commons.lang3.exception.ExceptionUtils
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import org.tronscan.actions.{RepresentativeListReader, VoteList}

import concurrent.duration._

class CacheWarmer @Inject() (
  @NamedCache("redis") redisCache: CacheAsyncApi,
  representativeListReader: RepresentativeListReader,
  voteList: VoteList) extends Actor {

  val decider: Supervision.Decider = {
    case exc =>
      println("CACHE WARMER ERROR", exc, ExceptionUtils.getStackTrace(exc))
      Supervision.Resume
  }

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider))(context)

  import context.dispatcher

  def startWitnessReader() = {
    Source.tick(1.second, 15.seconds, "refresh")
      .mapAsyncUnordered(1)(_ => representativeListReader.execute)
      .runWith(writeToKey("witness.list", 1.minute))
  }

  def startVoteListWarmer() = {
    Source.tick(3.second, 13.seconds, "refresh")
      .mapAsyncUnordered(1)(_ => voteList.execute)
      .runWith(writeToKey("votes.candidates_total", 1.minute))
  }


  def writeToKey[T](name: String, duration: Duration = Duration.Inf) = {
    Sink.foreachParallel[T](1)(x => redisCache.set(name, x, duration))
  }

  override def preStart(): Unit = {
    startWitnessReader()
    startVoteListWarmer()
  }

  def receive = {
    case x =>
  }
}
