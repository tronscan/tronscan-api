package org.tronscan.api.models

import io.swagger.annotations.{ApiModel, ApiModelProperty}

@ApiModel(
  value="CreateTransaction")
case class CreateTransaction(
  @ApiModelProperty(value = "Transaction in HEX format")
  transaction: String
)
