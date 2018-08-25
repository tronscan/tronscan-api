package org.tronscan.models

import org.tron.api.api.AccountNetMessage

case class AccountBandwidthCapsule(accountNetMessage: AccountNetMessage) {

  val freeNetRemaining = accountNetMessage.freeNetLimit - accountNetMessage.freeNetUsed
  val netRemaining = accountNetMessage.netLimit - accountNetMessage.netUsed

  val netRemainingPercentage =
    if (accountNetMessage.netLimit == 0) 0
    else (accountNetMessage.netUsed.toDouble / accountNetMessage.netLimit.toDouble) * 100

  val freeNetRemainingPercentage =
    if (accountNetMessage.freeNetLimit == 0) 0
    else (accountNetMessage.freeNetUsed.toDouble / accountNetMessage.freeNetLimit.toDouble) * 100

  val assets = accountNetMessage.assetNetUsed.map { case (token, used) =>

    val limit = accountNetMessage.assetNetLimit(token)

    if (limit == 0) {
      token -> Map(
        "netUsed" -> 0D,
        "netLimit" -> 0D,
        "netRemaining" -> 0D,
        "netPercentage" -> 0D,
      )
    } else {
      token -> Map(
        "netUsed" -> used.toDouble,
        "netLimit" -> limit.toDouble,
        "netRemaining" -> (limit.toDouble - used.toDouble),
        "netPercentage" -> (used.toDouble / limit.toDouble) * 100,
      )
    }
  }
}
