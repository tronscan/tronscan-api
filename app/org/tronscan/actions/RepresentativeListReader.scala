package org.tronscan.actions

import javax.inject.Inject
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import play.api.cache.Cached
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{AccountModelRepository, VoteWitnessContractModelRepository, WitnessModelRepository}
import org.tronscan.protocol.AddressFormatter
import org.tronscan.service.GeoIPService
import org.tronscan.Extensions._

import scala.concurrent.ExecutionContext

class RepresentativeListReader @Inject() (
  witnessModelRepository: WitnessModelRepository,
  accountModelRepository: AccountModelRepository,
  walletSolidity: WalletSolidity) {

  def execute(implicit executionContext: ExecutionContext) = {
    for {
      witnesses <- walletSolidity.listWitnesses(EmptyMessage()).map(_.witnesses)
      accounts <- accountModelRepository.findByAddresses(witnesses.map(_.address.toAddress)).map(_.map(x => x.address -> x.name).toMap)
      witnessTrx <- witnessModelRepository.findTransactionsByWitness()
    } yield (witnesses, accounts, witnessTrx)
  }

}
