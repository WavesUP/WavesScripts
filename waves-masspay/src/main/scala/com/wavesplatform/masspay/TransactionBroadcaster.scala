package com.wavesplatform.masspay

import akka.actor.{Actor, Props}
import akka.http.scaladsl.Http
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import cats.instances.all._
import cats.syntax.semigroup._
import com.wavesplatform.masspay.BlockObserver.NewBlock
import com.wavesplatform.masspay.TasksActor.{TaskCompleted, TransactionsConfirmed}
import com.wavesplatform.masspay.TransactionBroadcaster.UtxPool
import scorex.app.RunnableApplication

import scala.collection.mutable.ArrayBuffer

class TransactionBroadcaster(application: RunnableApplication) extends Actor {

  private var remaining = ArrayBuffer.empty[(String, AssetTransferRequest)]
  private var unconfirmed = Map.empty[String, Set[String]]
  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  context.system.eventStream.subscribe(self, classOf[NewBlock])

  override def receive: Receive = {
    case BroadcastSignedTask(id, _, _, txs) =>
      remaining ++= txs.map(tx => (id, tx))
      sendUtxRequest()
    case NewBlock(block) =>
      processNewBlock(block)
      sendUtxRequest()
    case UtxPool(txs) =>
      processUtx(txs)
      processNextBatch(txs.size)
  }

  def processNewBlock(block: Block): Unit = {
    if (unconfirmed.nonEmpty) {
      val confirmedIds = block.transactions.map(_.id).toSet
      val confirmedTasks = unconfirmed.mapValues(_.intersect(confirmedIds))
      confirmedTasks.filter(_._2.nonEmpty).foreach { case (id, txs) => application.tasksActor ! TransactionsConfirmed(id, txs) }
      val newUnconfirmed = unconfirmed.mapValues(_ -- confirmedIds)

      val maybeCompleted = newUnconfirmed.filter(t => t._2.isEmpty)
      if (maybeCompleted.nonEmpty) {
        val tasks2Txs = remaining.groupBy(_._1)
        maybeCompleted
          .filter { t =>
            tasks2Txs.get(t._1).forall(_.isEmpty)
          }
          .foreach { case (id, _) =>
            application.tasksActor ! TaskCompleted(id)
          }
      }
      unconfirmed = newUnconfirmed.filter(_._2.nonEmpty)
    }
  }

  def processNextBatch(utxSize: Int): Unit = {
    if (application.settings.broadcastSettings.utxSize - utxSize > 2 * application.settings.broadcastSettings.batchSize) {
      val (batch, newRemaining) = remaining.splitAt(application.settings.broadcastSettings.batchSize)
      remaining = newRemaining
      sendBroadcast(batch.map(_._2))
      val newUnconfirmed: Map[String, Set[String]] = batch.groupBy(_._1).mapValues(_.map(_._2.idStr).toSet)
      unconfirmed = unconfirmed |+| newUnconfirmed
    }
  }

  def sendBroadcast(txs: Seq[AssetTransferRequest]): Unit = {
    if (txs.nonEmpty)
      context.actorOf(Props(classOf[BroadcastRequestActor], application.settings.broadcastSettings, txs))
  }

  def sendUtxRequest(): Unit = {
    context.actorOf(Props(classOf[UtxRequestActor], application.settings.broadcastSettings))
  }

  def processUtx(txs: Set[String]): Unit = {
    val completed = unconfirmed.foldLeft(Seq.empty[String]) {case (a, (taskId, ss)) =>
      if ((ss -- txs).isEmpty) a
      else a :+ taskId
    }
    unconfirmed --= completed
    if (completed.nonEmpty) {
      val tasks2Txs = remaining.groupBy(_._1)
      completed
        .filter(id => tasks2Txs.getOrElse(id, Seq()).isEmpty)
        .foreach(id => application.tasksActor ! TaskCompleted(id))
    }
  }
}

object TransactionBroadcaster {
  case class Broadcast(task: Task)
  case class UtxPool(txs: Set[String])
  case class BroadcastSent(txs: Set[String])
}