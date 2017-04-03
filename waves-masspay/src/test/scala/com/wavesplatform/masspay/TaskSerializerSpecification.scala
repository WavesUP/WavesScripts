package com.wavesplatform.masspay

import java.util.UUID

import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import play.api.libs.json.Json
import scorex.account.PublicKeyAccount
import scorex.crypto.encode.Base58

class TaskSerializerSpecification extends PropSpec
  with PropertyChecks
  with Matchers {

  val t1 = AssetTransferRequest(
    PublicKeyAccount(Base58.decode("5CaZH7oKLs9rV83UdMpYkz5fMXBcJXS5MhpkpSAwraK6").get),
    Some("5tk3syx3LZTdBiwJMbiwpCRJ1o1EDr5TwtK9xrTWSBuf"),
    "3N18z4B8kyyQ96PhN5eyhCAbg4j49CgwZJx",
    90000000L,
    100000L,
    None,
    1490019746967L,
    Some("attach"),
    Base58.decode("63dU3GA6uhf6rZyXkXZLWb9gCBwXqwwBzdqqCv78eb7qarPqW23rTuj8ztyjPG3PFiJTDg4vpF9u5nza9FHG9ugo").get
  )

  val t2 = AssetTransferRequest(
    PublicKeyAccount(Base58.decode("5CaZH7oKLs9rV83UdMpYkz5fMXBcJXS5MhpkpSAwraK6").get),
    Some("5tk3syx3LZTdBiwJMbiwpCRJ1o1EDr5TwtK9xrTWSBuf"),
    "3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8",
    4000674L,
    100000L,
    None,
    1490019746967L,
    Some("attach"),
    Base58.decode("558aFgU7bCcG2ZgsiobqtAq1tynRPtXNXLaTkUaRnsUuU1b1HCJpq9q5hYev7sUzHePGxmLX2dLWiGMMU4buzf5T").get
  )

  property("Write/Read AssetTransferRequest") {
    val j = Json.toJson(t1)
    val res = j.validate[AssetTransferRequest]
    res.get.assetId.getOrElse() shouldBe "5tk3syx3LZTdBiwJMbiwpCRJ1o1EDr5TwtK9xrTWSBuf"
  }

  property("Write/Read BroadcastSignedTask") {
    import TaskSerializer._

    val b = BroadcastSignedTask(UUID.randomUUID().toString, "task1", System.currentTimeMillis(), Seq(t1, t2))
    val j = Json.toJson(b)
    val res = j.validate[BroadcastSignedTask]
    res.get.id shouldBe b.id
    res.get.transactions.size shouldBe b.transactions.size
    res.get.transactions(1).amount shouldBe 4000674L
  }

  property("Write/Read TaskData") {
    val d = TaskData(UUID.randomUUID().toString, "task1", System.currentTimeMillis(), 0, TaskData.InProgress,
      1, 0, 1, Set("123"))
    val j = Json.toJson(d)
    val res = j.validate[TaskData]
    res.get.id shouldBe d.id
    res.get.failedCount shouldBe 1
    res.get.failedTransactions shouldBe Set("123")
  }
}
