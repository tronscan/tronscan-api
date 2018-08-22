package org.tronscan.importer

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink}
import javax.inject.Inject
import org.tron.protos.Tron.Block
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AssetIssueContract, ParticipateAssetIssueContract, TransferAssetContract, TransferContract, VoteWitnessContract, WitnessCreateContract}
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
  walletClient: WalletClient,
  blockImporter: BlockImporter) {

  /**
    * Build importers for Full Node
    * @param importAction
    * @param actorSystem
    * @param executionContext
    * @return
    */
  def buildFullNodeImporters(importAction: ImportAction)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext) = {

    val redisCleaner = if (importAction.cleanRedisCache) Flow[Address].alsoTo(redisCacheCleaner) else Flow[Address]

    val accountUpdaterFlow: Flow[Address, Address, NotUsed] = {
      if (importAction.updateAccounts) {
        if (importAction.asyncAddressImport) {
          Flow[Address].alsoTo(accountImporter.buildAddressMarkDirtyFlow)
        } else {
          accountImporter.buildAddressSynchronizerFlow(walletClient)(actorSystem.scheduler, executionContext)
        }
      } else {
        Flow[Address]
      }
    }

    val eventsPublisher = if (importAction.publishEvents) {
      Flow[ContractFlow].alsoTo(blockChainBuilder.publishContractEvents(
        actorSystem.eventStream,
        List(
          TransferContract,
          TransferAssetContract,
          WitnessCreateContract
        ))
      )
    } else {
      Flow[ContractFlow]
    }

    val blockFlow = Flow[Block].alsoTo(blockImporter.fullNodeBlockImporter(importAction.confirmBlocks))


    BlockchainImporters()
      .addAddress(accountUpdaterFlow)
      .addAddress(redisCleaner)
      .addContract(eventsPublisher)
      .addBlock(blockFlow)
  }

  def buildSolidityImporters(importAction: ImportAction)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext) = {

    val eventsPublisher = if (importAction.publishEvents) {
      Flow[ContractFlow].alsoTo(
        blockChainBuilder.publishContractEvents(
          actorSystem.eventStream,
          List(
            VoteWitnessContract,
            AssetIssueContract,
            ParticipateAssetIssueContract
          ))
      )
    } else {
      Flow[ContractFlow]
    }

    val blockFlow = Flow[Block].alsoTo(blockImporter.buildSolidityBlockQueryImporter)

    BlockchainImporters()
        .addContract(eventsPublisher)
        .addBlock(blockFlow)
  }

  /**
    * Invalidate addresses in the redis cache
    */
  def redisCacheCleaner: Sink[Any, Future[Done]] = Sink
    .foreach { address =>
      redisCache.removeMatching(s"address/$address/*")
    }
}
