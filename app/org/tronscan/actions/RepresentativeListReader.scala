package org.tronscan.actions

import javax.inject.Inject
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletGrpc.Wallet
import org.tronscan.Extensions._
import org.tronscan.models.{AccountModelRepository, WitnessModelRepository}

import scala.concurrent.ExecutionContext

class RepresentativeListReader @Inject() (
  witnessModelRepository: WitnessModelRepository,
  accountModelRepository: AccountModelRepository,
  wallet: Wallet) {

  def execute(implicit executionContext: ExecutionContext) = {
    for {
      witnesses <- wallet.listWitnesses(EmptyMessage()).map(_.witnesses)
      accounts <- accountModelRepository.findByAddresses(witnesses.map(_.address.toAddress)).map(_.map(x => x.address -> x.name).toMap)
      witnessTrx <- witnessModelRepository.findTransactionsByWitness()
    } yield (witnesses, accounts, witnessTrx)
  }

}
