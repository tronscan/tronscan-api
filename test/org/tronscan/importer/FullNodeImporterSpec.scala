package org.tronscan.importer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.TestProbe
import dispatch.url
import org.specs2.matcher.{FutureMatchers, Matchers}
import org.specs2.mutable._
import org.specs2.specification.AfterAll
import org.tron.protos.Contract.{TransferAssetContract, TransferContract, WitnessCreateContract}
import org.tron.protos.Tron.Transaction.Contract.ContractType
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.domain.Events.{AddressEvent, AssetTransferCreated, TransferCreated, WitnessCreated}
import org.tronscan.domain.Types.Address
import org.tronscan.models.WitnessModel
import org.tronscan.test.{Awaiters, BlockBuilder, StreamSpecUtils}

import scala.concurrent.duration._


class FullNodeImporterSpec extends Specification with Matchers with FutureMatchers with Awaiters with StreamSpecUtils with AfterAll {

  val factory = new FullNodeImporterFactory(null, null, null)
  val blockchainStreamFactory = new BlockChainStreamBuilder

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  override def afterAll = {
    awaitSync(system.terminate())
    materializer.shutdown()
  }

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

      val importers = StreamImporters()
        .addAddress(addressSink)
        .addBlock(blockSink)
        .addContract(contractSink)

      val source = Source.single(block.block)
        .runWith(factory.buildSource(importers))

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
          ContractType.WitnessCreateContract,
          WitnessCreateContract(
            ownerAddress = "TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp".decodeAddress,
            url = "https://tronscan.org".encodeString))

      val (contracts, contractSink) = buildListSink[(Block, Transaction, Transaction.Contract)]

      val eventStream = system.eventStream

      // Create listener to listen to address events
      val eventListener = TestProbe()
      eventStream.subscribe(eventListener.ref, classOf[AddressEvent])

      val importers = StreamImporters()
        .addContract(contractSink)
        .addContract(blockchainStreamFactory.publishContractEvents(eventStream, List(
          ContractType.TransferContract,
          ContractType.TransferAssetContract,
          ContractType.WitnessCreateContract
        )))

      val source = Source.single(block.block)
        .runWith(factory.buildSource(importers))

      eventListener.expectMsgAnyClassOf(1.seconds, classOf[TransferCreated])
      eventListener.expectMsgAnyClassOf(1.seconds, classOf[AssetTransferCreated])
      eventListener.expectMsg(1.seconds, WitnessCreated(WitnessModel(
        "TGzz8gjYiYRqpfmDwnLxfgPuLVNmpCswVp",
        "https://tronscan.org"
      )))

      awaitSync(source)

      contracts.size must equalTo(3)
    }
  }

}
