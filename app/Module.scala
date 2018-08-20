import com.google.inject.{AbstractModule, Provides}
import io.grpc.ManagedChannelBuilder
import javax.inject.{Inject, Singleton}
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import org.tron.api.api.{WalletGrpc, WalletSolidityGrpc}
import org.tronscan.actors.VoteScraper
import org.tronscan.cache.CacheWarmer
import org.tronscan.grpc.GrpcPool
import org.tronscan.importer.{FullNodeReader, ImportManager, PartialReader, SolidityNodeReader}
import org.tronscan.protocol.{AddressFormatter, TestNetFormatter}
import org.tronscan.realtime.SocketIOEngine
import org.tronscan.service.Bootstrap
import org.tronscan.watchdog.NodeWatchDog
import play.api.inject.ConfigurationProvider
import play.api.libs.concurrent.AkkaGuiceSupport
import play.engineio.EngineIOController

class Module extends AbstractModule with AkkaGuiceSupport {

  def configure = {
    bindActor[FullNodeReader]("fullnode-reader")
    bindActor[SolidityNodeReader]("solidity-reader")
    bindActor[PartialReader]("partial-reader")
    bindActor[ImportManager]("blockchain-importer")
    bindActor[VoteScraper]("vote-scraper")
    bindActor[NodeWatchDog]("node-watchdog")
    bindActor[GrpcPool]("grpc-pool")
    bindActor[CacheWarmer]("cache-warmer")
    bind(classOf[WalletSolidity]).to(classOf[WalletSolidityGrpc.WalletSolidityStub])
    bind(classOf[AddressFormatter]).to(classOf[TestNetFormatter])
    bind(classOf[EngineIOController]).toProvider(classOf[SocketIOEngine])
    bind(classOf[Bootstrap]).asEagerSingleton()
  }

  @Provides
  @Singleton
  @Inject
  def buildGrpcClient(configurationProvider: ConfigurationProvider): Wallet = {
    val config = configurationProvider.get
    val channel = ManagedChannelBuilder
      .forAddress(config.get[String]("fullnode.ip"), config.get[Int]("fullnode.port"))
      .usePlaintext(true)
      .build

    WalletGrpc.stub(channel)
  }

  @Provides
  @Singleton
  @Inject
  def buildSolidityClient(configurationProvider: ConfigurationProvider): WalletSolidityGrpc.WalletSolidityStub = {
    val config = configurationProvider.get
    val channel = ManagedChannelBuilder
      .forAddress(config.get[String]("solidity.ip"), config.get[Int]("solidity.port"))
      .usePlaintext(true)
      .build

    WalletSolidityGrpc.stub(channel)
  }
}