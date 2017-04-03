package scorex.account

trait AccountOrAlias {
  def stringRepr: String

  def bytes: Array[Byte]
}

object AccountOrAlias {

  def fromBytes(bytes: Array[Byte], position: Int): Either[ValidationError, (AccountOrAlias, Int)] = {
    bytes(position) match {
      case Account.AddressVersion =>
        val addressEnd = position + Account.AddressLength
        val addressBytes = bytes.slice(position, addressEnd)
        Account.fromBytes(addressBytes).map((_, addressEnd))
      case _ => Left(ValidationError.InvalidAddress)
    }
  }

}