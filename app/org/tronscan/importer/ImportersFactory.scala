package org.tronscan.importer

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink}
import javax.inject.Inject
import org.tron.protos.Tron.Transaction.Contract.ContractType.{TransferAssetContract, TransferContract, WitnessCreateContract}
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.WalletClient
import org.tronscan.importer.StreamTypes.ContractFlow
import org.tronscan.service.SynchronisationService
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi

import scala.concurrent.{ExecutionContext, Future}

class ImportersFactory @Inject() (
  syncService: SynchronisationService,
  blockChainBuilder: BlockChainStreamBuilder,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  accountImporter: AccountImporter,
  walletClient: WalletClient) {

  def buildImporters(importAction: ImportAction)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext) = {

    val redisCleaner = if (importAction.cleanRedisCache) Flow[Address].alsoTo(redisCacheCleaner) else Flow[Address]

    val accountUpdaterFlow = {
      if (importAction.updateAccounts) {
        if (importAction.asyncAddressImport) {
          accountImporter.buildAddressMarkDirtyFlow
        } else {
          accountImporter.buildAddressSynchronizerFlow(walletClient)(actorSystem.scheduler, executionContext)
        }
      } else {
        Flow[Address]
      }
    }

    val eventsPublisher = if (importAction.publishEvents) {
      blockChainBuilder.publishContractEvents(actorSystem.eventStream, List(
        TransferContract,
        TransferAssetContract,
        WitnessCreateContract
      ))
    } else {
      Flow[ContractFlow]
    }

    BlockchainImporters(
      addresses = List(
        accountUpdaterFlow,
        redisCleaner
      ),
      contracts = List(
        eventsPublisher
      )
    )
  }


  /**
    * Invalidate addresses in the redis cache
    */
  def redisCacheCleaner: Sink[Any, Future[Done]] = Sink
    .foreach { address =>
      redisCache.removeMatching(s"address/$address/*")
    }
}
