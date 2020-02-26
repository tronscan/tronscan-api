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
import org.tronscan.Extensions._
import org.tronscan.models.MaintenanceRoundModelRepository
import async.Async._
import scala.concurrent.{ExecutionContext, Future}

class ImportersFactory @Inject() (
  syncService: SynchronisationService,
  blockChainBuilder: BlockChainStreamBuilder,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  accountImporter: AccountImporter,
  walletClient: WalletClient,
  maintenanceRoundModelRepository: MaintenanceRoundModelRepository,
  blockImporter: BlockImporter) {

  /**
    * Build importers for Full Node
    * @return
    */
  def buildFullNodeImporters(importAction: ImportAction)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext) = async {

    val redisCleaner = if (importAction.cleanRedisCache) Flow[Address].alsoTo(redisCacheCleaner) else Flow[Address]

    val maintenanceRound = blockImporter.buildVotingRoundImporter(await(maintenanceRoundModelRepository.findLatest)).toFlow

    val accountUpdaterFlow: Flow[Address, Address, NotUsed] = {
      if (importAction.updateAccounts) {
        if (importAction.asyncAddressImport) {
          accountImporter.buildAddressMarkDirtyFlow.toFlow
        } else {
          accountImporter.buildAddressSynchronizerFlow(walletClient)(actorSystem.scheduler, executionContext).toFlow
        }
      } else {
        Flow[Address]
      }
    }

    val eventsPublisher = if (importAction.publishEvents) {
      blockChainBuilder.publishContractEvents(
        actorSystem.eventStream,
        List(
          TransferContract,
          TransferAssetContract,
          WitnessCreateContract
        )
      ).toFlow
    } else {
      Flow[ContractFlow]
    }

    val blockFlow = blockImporter.fullNodeBlockImporter(importAction.confirmBlocks).toFlow

    BlockchainImporters()
      .addAddress(accountUpdaterFlow)
      .addAddress(redisCleaner)
      .addContract(eventsPublisher)
      .addBlock(blockFlow)
      .addBlock(maintenanceRound)
  }

  /**
    * Build Solidity Blockchain importers
    */
  def buildSolidityImporters(importAction: ImportAction)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext) = {

    val eventsPublisher = if (importAction.publishEvents) {
      blockChainBuilder.publishContractEvents(
        actorSystem.eventStream,
        List(
          VoteWitnessContract,
          AssetIssueContract,
          ParticipateAssetIssueContract
        )).toFlow
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
