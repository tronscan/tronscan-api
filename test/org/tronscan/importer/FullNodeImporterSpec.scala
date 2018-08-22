package org.tronscan.importer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.TestProbe
import org.specs2.matcher.{FutureMatchers, Matchers}
import org.specs2.mutable._
import org.specs2.specification.AfterAll
import org.tron.protos.Contract.{FreezeBalanceContract, TransferAssetContract, TransferContract, WitnessCreateContract}
import org.tron.protos.Tron.Transaction.Contract.ContractType
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.domain.Events.{AddressEvent, AssetTransferCreated, TransferCreated, WitnessCreated}
import org.tronscan.domain.Types.Address
import org.tronscan.importer.StreamTypes.ContractFlow
import org.tronscan.models.{TransferModel, WitnessModel}
import org.tronscan.test.{Awaiters, BaseStreamSpec, BlockBuilder, StreamSpecUtils}

import scala.concurrent.duration._


class FullNodeImporterSpec extends Specification with BaseStreamSpec with FutureMatchers with Awaiters with StreamSpecUtils {

  val factory = new ImportStreamFactory(null, null, null)
  val blockchainStreamFactory = new BlockChainStreamBuilder

  "Full Node Importer" should {

    "Test Single Block Import" in {

      val block = BlockBuilder()
        .addContract(
          ContractType.TransferContract,
          TransferContract(
            ownerAddress = "TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp".decodeAddress,
            toAddress = "TGj1Ej1qRzL9feLTLhjwgxXF4Ct6GTWg2U".decodeAddress,
            amount = 1000))

      val (addresses, addressSink)  = buildListSink[Address]
      val (blocks,    blockSink)    = buildListSink[Block]
      val (contracts, contractSink) = buildListSink[(Block, Transaction, Transaction.Contract)]

      val importers = BlockchainImporters()
        .addAddress(addressSink)
        .addBlock(blockSink)
        .addContract(contractSink)

      val source = Source.single(block.block)
        .runWith(factory.buildBlockSink(importers))

      awaitSync(source)

      addresses must contain("TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp", "TGj1Ej1qRzL9feLTLhjwgxXF4Ct6GTWg2U")
      blocks.size must equalTo(1)
      contracts.size must equalTo(1)
    }

    "Event Broadcasting" in {

      val block = BlockBuilder()
        .addContract(
          ContractType.TransferContract,
          TransferContract(
            ownerAddress = "TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp".decodeAddress,
            toAddress = "TGj1Ej1qRzL9feLTLhjwgxXF4Ct6GTWg2U".decodeAddress,
            amount = 1000))
        .addContract(
          ContractType.TransferAssetContract,
          TransferAssetContract(
            ownerAddress = "TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp".decodeAddress,
            toAddress = "TGj1Ej1qRzL9feLTLhjwgxXF4Ct6GTWg2U".decodeAddress,
            assetName = "TRX".encodeString,
            amount = 1000))
        .addContract(
          ContractType.FreezeBalanceContract,
          FreezeBalanceContract(
            ownerAddress = "TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp".decodeAddress,
            frozenBalance = 100,
            frozenDuration = 100))
        .addContract(
          ContractType.WitnessCreateContract,
          WitnessCreateContract(
            ownerAddress = "TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp".decodeAddress,
            url = "https://tronscan.org".encodeString))

      // Create listener to listen to address events
      val eventListener = TestProbe()
      system.eventStream.subscribe(eventListener.ref, classOf[AddressEvent])

      val importers = BlockchainImporters()
        .addContract(Flow[ContractFlow].alsoTo(blockchainStreamFactory.publishContractEvents(system.eventStream, List(
          ContractType.TransferContract,
          ContractType.TransferAssetContract,
          ContractType.WitnessCreateContract
        ))))

      val source = Source.single(block.block)
        .runWith(factory.buildBlockSink(importers))

      eventListener.expectMsgAnyClassOf(1.seconds, classOf[TransferCreated])
      eventListener.expectMsgAnyClassOf(1.seconds, classOf[AssetTransferCreated])
      eventListener.expectMsg(1.seconds, WitnessCreated(WitnessModel(
        "TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp",
        "https://tronscan.org"
      )))
      eventListener.expectNoMessage(1.seconds)

      awaitSync(source)

      ok
    }
  }
}
