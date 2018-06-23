package org
package tronscan.websockets

import play.socketio.scaladsl.SocketIOEventCodec._
import CirceDecoder._
import CirceEncoder._
import io.circe.generic.auto._
import org.tronscan.events._
import org.tronscan.models._
import org.tronscan.tools.nodetester.NodeStatus

object SocketCodecs {

  /**
    * Incoming
    */
  val decoder = decodeByName[Any] {

    case "sign-request" =>
      fromJson[SignRequest] withAckEncoder toJson[SignResponse] andThen {
        case (trxRequest, callback) => SignRequestCallback(trxRequest.transaction, callback)
      }

    case "app-login" =>
      fromJson[AppLogin]

    case "client-connected" =>
      fromJson[String]
  }

  /**
    * Outgoing
    */
  val encoder = encodeByType[Any] {

    case _: TransferCreated =>
      "transfer" -> (toJson[TransferModel] compose[TransferCreated] {
        case TransferCreated(trx) => trx
      })

    case _: AssetTransferCreated =>
      "transfer" -> (toJson[TransferModel] compose[AssetTransferCreated] {
        case AssetTransferCreated(trx) => trx
      })

    case _: VoteCreated =>
      "vote" -> (toJson[VoteWitnessContractModel] compose[VoteCreated] {
        case VoteCreated(vote) => vote
      })

    case _: ParticipateAssetIssueModelCreated =>
      "asset-participate" -> (toJson[ParticipateAssetIssueModel] compose[ParticipateAssetIssueModelCreated] {
        case ParticipateAssetIssueModelCreated(model) => model
      })

    case _: AssetIssueCreated =>
      "asset-create" -> (toJson[AssetIssueContractModel] compose[AssetIssueCreated] {
        case AssetIssueCreated(model) => model
      })

    case x: NodeStatus =>
      "node-status" -> toJson[NodeStatus]

    case _: WitnessCreated =>
      "witness-create" -> (toJson[WitnessModel] compose[WitnessCreated] {
        case WitnessCreated(model) => model
      })

    case _: RequestTransactionSign =>
      "sign-request" -> (toJson[TransactionHex] withAckDecoder fromJson[SignResponse]).compose[RequestTransactionSign] {
        x =>  (x.transaction, x.response)
      }

    case _: AppLogin =>
      "app-login" -> toJson[AppLogin]

    case x: String =>
      "msg" -> toJson[String]
  }

}
