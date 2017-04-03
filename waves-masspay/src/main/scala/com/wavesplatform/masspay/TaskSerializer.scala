package com.wavesplatform.masspay

import java.io.NotSerializableException

import akka.serialization._
import com.wavesplatform.masspay.TasksActor.Snapshot
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

class TaskSerializer extends SerializerWithStringManifest {
  import TaskSerializer._
  import TasksActor._
  override def identifier: Int = id
  override def manifest(o: AnyRef): String = o match {
    case _: Snapshot => Manifest.Snapshot
    case _: BroadcastSignedTask => Manifest.BroadcastSignedTask
    case _: BroadcastUnsignedTask => Manifest.BroadcastUnsignedTask
    case _: TransactionsConfirmed => Manifest.TransactionsConfirmed
    case _: TaskCompleted => Manifest.TaskCompleted
    case _: CancelTask => Manifest.CancelTask
    case _: BalanceReceived => Manifest.BalanceReceived
  }

  override def toBinary(o: AnyRef): Array[Byte] = Json.stringify(o match {
    case s: Snapshot => snapshotFormat.writes(s)
    case b: BroadcastSignedTask => broadcastSignedTaskFormat.writes(b)
    case b: BroadcastUnsignedTask => broadcastUnsignedTaskFormat.writes(b)
    case t: TransactionsConfirmed => transactionsConfirmedFormat.writes(t)
    case t: TaskCompleted => taskCompletedFormat.writes(t)
    case t: CancelTask => cancelTaskFormat.writes(t)
    case r: BalanceReceived => balanceReceivedFormat.writes(r)
  }).getBytes

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case Manifest.Snapshot => snapshotFormat.reads(Json.parse(bytes)).get
    case Manifest.BroadcastSignedTask => broadcastSignedTaskFormat.reads(Json.parse(bytes)).get
    case Manifest.BroadcastUnsignedTask => broadcastUnsignedTaskFormat.reads(Json.parse(bytes)).get
    case Manifest.TransactionsConfirmed => transactionsConfirmedFormat.reads(Json.parse(bytes)).get
    case Manifest.TaskCompleted => taskCompletedFormat.reads(Json.parse(bytes)).get
    case Manifest.CancelTask => cancelTaskFormat.reads(Json.parse(bytes)).get
    case Manifest.BalanceReceived => balanceReceivedFormat.reads(Json.parse(bytes)).get
    case _ => throw new NotSerializableException(manifest)
  }
}

object TaskSerializer {
  private[TaskSerializer] val id = 5001

  private[TaskSerializer] object Manifest {
    val Snapshot = "snapshot"
    val BroadcastSignedTask = "broadcastSignedTask"
    val BroadcastUnsignedTask = "broadcastUnsignedTask"
    val TransactionsConfirmed = "event.TransactionsConfirmed"
    val TaskCompleted = "event.TaskCompleted"
    val CancelTask = "event.CancelTask"
    val BalanceReceived = "event.BalanceReceived"
  }

  implicit val broadcastSignedTaskFormat: Format[BroadcastSignedTask] = (
    (__ \ "id").format[String] and
      (__ \ "name").format[String] and
      (__ \ "ts").format[Long] and
      (__ \ "txs").format[Seq[AssetTransferRequest]])(BroadcastSignedTask.apply, unlift(BroadcastSignedTask.unapply))

  implicit val broadcastUnsignedTaskFormat: Format[BroadcastUnsignedTask] = (
    (__ \ "id").format[String] and
      (__ \ "name").format[String] and
      (__ \ "ts").format[Long] and
      (__ \ "txs").format[Seq[AssetTransferRequest]])(BroadcastUnsignedTask.apply, unlift(BroadcastUnsignedTask.unapply))

  implicit val snapshotFormat: Format[Snapshot] = (
    (__ \ "tasks").format[Map[String, BroadcastSignedTask]] and
      (__ \ "pending").format[Map[String, BroadcastUnsignedTask]] and
      (__ \ "confirmed").format[Map[String, Set[String]]])(Snapshot.apply, unlift(Snapshot.unapply))
}
