package com.wavesplatform.masspay

import java.util.UUID

import play.api.libs.json.{JsObject, Json}
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.EllipticCurveImpl
import scorex.crypto.encode.Base58

import scala.util.Try

trait Task {
  def id: String
  def name: String
  def timestamp: Long
  def transactionCount: Int
}

case class BroadcastSignedTask(id: String, name: String, timestamp: Long,
                               transactions: Seq[AssetTransferRequest]) extends Task {
  override def transactionCount: Int = transactions.size
}

object BroadcastSignedTask {
  def signTransactions(account: PrivateKeyAccount, task: BroadcastUnsignedTask): BroadcastSignedTask = {
    val startTs = System.currentTimeMillis()
    val signedTxs = task.transactions.map(_.copy(senderPublicKey = account, timestamp = startTs))
      .map(t => t.copy(signature =  EllipticCurveImpl.sign(account, t.toSign)))
    BroadcastSignedTask(task.id, task.name, startTs, signedTxs)
  }
}

case class BroadcastUnsignedTask(id: String, name: String, timestamp: Long,
                               transactions: Seq[AssetTransferRequest]) extends Task {
  override def transactionCount: Int = transactions.size

  def  getNeededBalances: Seq[AssetBalance] = {
    transactions.foldLeft(Map.empty[Option[String], Long]) { case(a, t) =>
      a + (t.assetId -> (a.getOrElse(t.assetId, 0L) + t.amount))
    }.map(v => AssetBalance(v._1, v._2)).toSeq
  }

  def getTotalAmount: Long = {
    transactions.foldLeft(0L)(_ + _.amount)
  }

  def toJson: JsObject = Json.obj(
    "id" -> id,
    "name" -> name,
    "createdAt" -> timestamp,
    "totalAmount" -> getTotalAmount,
    "transactions" -> transactions.map(t => Json.obj("recipient" -> t.recipient, "amount" -> t.amount))
  )
}

object Tasks {

  def createTaskFromSignedTransactions(name: String, csv: String): Try[BroadcastSignedTask] = Try {
    val txs = Csv.readCsvTransfer(csv).map {row => AssetTransferRequest(
      PublicKeyAccount(Base58.decode(row.senderPubKey).get),
      if (row.assetId.isEmpty) None else Some(row.assetId),
      row.recipient,
      BigDecimal(row.amount).bigDecimal.unscaledValue().longValue(),
      BigDecimal(row.fee).setScale(8).bigDecimal.unscaledValue().longValue(),
      None,
      row.timestamp.toLong,
      if (row.attachment.isEmpty) None else Some(row.attachment),
      Base58.decode(row.signature).get)
    }
    BroadcastSignedTask(UUID.randomUUID().toString, name, System.currentTimeMillis(), txs)
  }

  def createTaskFromUnsignedTransactions(name: String, csv: String, fee: Long,
                                         publicKeyAccount: PublicKeyAccount): Try[BroadcastUnsignedTask] = Try {
    val ts =  System.currentTimeMillis()
    val txs = Csv.readCsvUnsignedTransfer(csv).map {row => AssetTransferRequest(
      publicKeyAccount,
      if (row.assetId.isEmpty) None else Some(row.assetId),
      row.recipient,
      BigDecimal(row.amount).bigDecimal.unscaledValue().longValue(),
      fee,
      None,
      ts,
      if (row.attachment.isEmpty) None else Some(row.attachment),
      Array())
    }
    BroadcastUnsignedTask(UUID.randomUUID().toString, name, ts, txs)
  }
}
