package org.tronscan.actions

import akka.actor.Actor
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import javax.inject.Inject
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider

import scala.concurrent.duration._

class ActionRunner @Inject()(
  @NamedCache("redis") redisCache: CacheAsyncApi,
  representativeListReader: RepresentativeListReader,
  statsOverview: StatsOverview,
  voteList: VoteList,
  voteScraper: VoteScraper,
  configurationProvider: ConfigurationProvider) extends Actor {

  val decider: Supervision.Decider = { exc =>
    Logger.error("CACHE WARMER ERROR", exc)
    Supervision.Restart
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

  def startVoteScraper() = {
    Source.tick(10.minutes, 10.minutes, "refresh")
      .mapAsyncUnordered(1)(_ => voteScraper.execute)
      .runWith(Sink.ignore)
  }

  def startStatsOverview() = {
    Source.tick(0.second, 30.minutes, "refresh")
      .mapAsyncUnordered(1)(_ => statsOverview.execute)
      .runWith(writeToKey("stats.overview", 1.hour))
  }

  def writeToKey[T](name: String, duration: Duration = Duration.Inf) = {
    Sink.foreachParallel[T](1)(x => redisCache.set(name, x, duration))
  }

  override def preStart(): Unit = {
    if (configurationProvider.get.get[Boolean]("cache.warmer")) {
      startWitnessReader()
      startVoteListWarmer()
      startVoteScraper()
      startStatsOverview()
    }
  }

  def receive = {
    case x =>
  }
}
