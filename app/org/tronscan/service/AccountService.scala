package org.tronscan.service

import io.circe.syntax._
import javax.inject.Inject
import org.joda.time.DateTime
import org.tronscan.Extensions._
import org.tronscan.grpc.WalletClient
import org.tronscan.models.{AccountModel, AccountModelRepository, AddressBalanceModelRepository}

import scala.async.Async._
import scala.concurrent.ExecutionContext

class AccountService @Inject() (
  accountModelRepository: AccountModelRepository,
  addressBalanceModelRepository: AddressBalanceModelRepository) {

  /**
    * Synchronize the address from the node
    */
  def syncAddress(address: String, walletClient: WalletClient)(implicit executionContext: ExecutionContext) = async {

    val wallet  = await(walletClient.full)
    val account = await(wallet.getAccount(address.toAccount))

    if (account != null) {
      val accountModel = AccountModel(
        address = address,
        name = new String(account.accountName.toByteArray),
        balance = account.balance,
        power = account.frozen.map(_.frozenBalance).sum,
        tokenBalances = account.asset.asJson,
        dateCreated = new DateTime(account.createTime),
        dateUpdated = DateTime.now.minusSeconds(5), // Set update a few seconds behind so it doesn't trigger another resync
        dateSynced = DateTime.now,
      )

      await(accountModelRepository.insertOrUpdate(accountModel))
      await(addressBalanceModelRepository.updateBalance(accountModel))
    }
  }

  /**
    * Marks the address as dirty so it will be synchronized by the address importer
    */
  def markAddressDirty(address: String)(implicit executionContext: ExecutionContext) = async {
    if (!await(accountModelRepository.markDirty(address))) {
      await(accountModelRepository.insertAsync(AccountModel(
        address       = address,
        name          = "-",
        balance       = 0,
        power         = 0,
        dateCreated   = DateTime.now,
        dateUpdated   = DateTime.now,
        dateSynced    = DateTime.now.minusMinutes(5)
      )))
    }
  }
}
