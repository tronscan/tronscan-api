package org.tronscan.api.models

import io.swagger.annotations.{ApiModel, ApiModelProperty}
import org.tron.protos.Tron.Transaction

@ApiModel(
  value="TransactionCreate")
case class TransactionCreate(
  @ApiModelProperty(value = "Contract")
  contract: Any,

  @ApiModelProperty(value = "If the transaction should be broadcast to the network")
  broadcast: Boolean,

  @ApiModelProperty(value = "Private Key to sign the transaction")
  key: Option[String] = None,

  @ApiModelProperty(value = "Optional extra data to attach to the transaction")
  data: Option[String] = None
)


trait TransactionCreateBase {
  @ApiModelProperty(value = "If the transaction should be broadcast to the network")
  val broadcast: Boolean = false

  @ApiModelProperty(value = "Private Key to sign the transaction")
  val key: Option[String] = None

  @ApiModelProperty(value = "Optional extra data to attach to the transaction")
  val data: Option[String] = None
}