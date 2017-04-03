package scorex.account

import scala.language.implicitConversions


trait PublicKeyAccount {
  def publicKey: Array[Byte]

  override def equals(b: Any): Boolean = b match {
    case a: PublicKeyAccount => publicKey.sameElements(a.publicKey)
    case _ => false
  }

  override def hashCode(): Int = publicKey.hashCode()

  override lazy val toString: String = PublicKeyAccount.toAddress(this).address
}

object PublicKeyAccount {

  private case class PublicKeyAccountImpl(publicKey: Array[Byte]) extends PublicKeyAccount

  def apply(publicKey: Array[Byte]): PublicKeyAccount = PublicKeyAccountImpl(publicKey)

  implicit def toAddress(publicKeyAccount: PublicKeyAccount): Account = Account.fromPublicKey(publicKeyAccount.publicKey)

}
