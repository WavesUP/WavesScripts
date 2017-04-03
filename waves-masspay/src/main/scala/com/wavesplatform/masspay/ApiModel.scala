package com.wavesplatform.masspay

import com.google.common.primitives.{Bytes, Longs}
import play.api.libs.functional.syntax.{unlift, _}
import play.api.libs.json._
import scorex.account.PublicKeyAccount
import scorex.crypto.encode.Base58
import scorex.crypto.hash.FastCryptographicHash
import scorex.serialization.BytesSerializable

import scala.util.{Failure, Success}

case class Block(signature: String, timestamp: Long, transactions: Seq[Transaction])

object Block {
  implicit val paymentWrites: Format[Block] = (
    (JsPath \ "signature").format[String] and
      (JsPath \ "timestamp").format[Long] and
      (JsPath \ "transactions").format[Seq[Transaction]])(Block.apply, unlift(Block.unapply))
}

case class Transaction(id: String, timestamp: Long)
object Transaction {
  implicit val txFormat: Format[Transaction] = (
    (JsPath \ "id").format[String]  and
      (JsPath \ "timestamp").format[Long])(Transaction.apply, unlift(Transaction.unapply))
}

case class AssetBalance(assetId: Option[String], balance: Long)

object AssetBalance {
  implicit val assetBalanceFormat = Json.format[AssetBalance]
}

case class AssetTransferRequest(senderPublicKey: PublicKeyAccount,
                                assetId: Option[String],
                                recipient: String,
                                amount: Long,
                                fee: Long,
                                feeAssetId: Option[String],
                                timestamp: Long,
                                attachment: Option[String],
                                signature: Array[Byte]) {

  val transactionType = 4
  lazy val id: Array[Byte] = FastCryptographicHash(toSign)
  lazy val idStr = Base58.encode(id)

  lazy val toSign: Array[Byte] = {
    val timestampBytes  = Longs.toByteArray(timestamp)
    val assetIdBytes    = assetId.map(a => (1: Byte) +: Base58.decode(a).get).getOrElse(Array(0: Byte))
    val amountBytes     = Longs.toByteArray(amount)
    val feeAssetIdBytes = feeAssetId.map(a => (1: Byte) +: Base58.decode(a).get).getOrElse(Array(0: Byte))
    val feeBytes        = Longs.toByteArray(fee)

    Bytes.concat(Array(transactionType.toByte),
      senderPublicKey.publicKey,
      assetIdBytes,
      feeAssetIdBytes,
      timestampBytes,
      amountBytes,
      feeBytes,
      Base58.decode(recipient).get,
      BytesSerializable.arrayWithSize(attachment.map(_.getBytes("utf-8")).getOrElse(Array.empty[Byte])))
  }

  val toJson: JsValue = Json.obj(
    "senderPublicKey" -> Base58.encode(senderPublicKey.publicKey),
    "assetId" -> assetId,
    "recipient" -> recipient,
    "amount" -> amount,
    "fee" -> fee,
    "timestamp" -> timestamp,
    "attachment" ->  Base58.encode(attachment.map(_.getBytes("utf-8")).getOrElse(Array.empty[Byte])),
    "signature" -> Base58.encode(signature)
  )

}

object AssetTransferRequest {
  implicit val publicKeyAccountFormat = new Format[PublicKeyAccount] {
    def reads(json: JsValue): JsResult[PublicKeyAccount] = json match {
      case JsString(s) => Base58.decode(s) match {
        case Success(bytes) if bytes.length == 32 => JsSuccess(PublicKeyAccount(bytes))
        case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.incorrect.publicKeyAccount"))))
      }
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.jsstring"))))
    }
    override def writes(o: PublicKeyAccount): JsValue = JsString(Base58.encode(o.publicKey))
  }

  implicit val byteArrayReads = new Format[Array[Byte]] {
    def reads(json: JsValue) = json match {
      case JsString(s) if s.isEmpty => JsSuccess(Array())
      case JsString(s) if s.nonEmpty => Base58.decode(s) match {
        case Success(bytes) => JsSuccess(bytes)
        case Failure(_) => JsError(JsPath, JsonValidationError("error.incorrect.base58"))
      }
      case _ => JsError(JsPath, JsonValidationError("error.expected.jsstring"))
    }

    override def writes(o: Array[Byte]): JsValue = JsString(Base58.encode(o))
  }



  implicit val assetTransferRequestFormat: Format[AssetTransferRequest] = (
    (__ \ "senderPublicKey").format[PublicKeyAccount] and
      (__ \ "assetId").formatNullable[String] and
      (__ \ "recipient").format[String] and
      (__ \ "amount").format[Long] and
      (__ \ "fee").format[Long] and
      (__ \ "feeAssetId").formatNullable[String] and
      (__ \ "timestamp").format[Long] and
      (__ \ "attachment").formatNullable[String] and
      (__ \ "signature").format[Array[Byte]])(AssetTransferRequest.apply, unlift(AssetTransferRequest.unapply))

}