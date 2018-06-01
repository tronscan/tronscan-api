package org.tronscan.events

sealed trait SignEvent {
  def channel: String
}

case class TransactionHex(hex: String)

// Socket IO Messages
case class SignRequest(transaction: TransactionHex)
case class SignRequestCallback(transaction: TransactionHex, callback: SignResponse => Unit)
case class SignResponse(transaction: TransactionHex)

case class SignRequestOnSigner(transactionHex: TransactionHex, callback: TransactionHex => Unit)

// P2P Messages
case class RequestTransactionSign(channel: String, transaction: TransactionHex, response: SignResponse => Unit) extends SignEvent

// Login
case class App(name: String, logo: String)
case class Wallet(address: String)
case class AppLogin(code: String, app: App, wallet: Wallet)