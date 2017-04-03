package com.wavesplatform.masspay

import akka.actor.Props
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer, SnapshotSelectionCriteria}
import com.wavesplatform.masspay.TaskHistory.TaskDataUpdate
import com.wavesplatform.masspay.TasksActor.{BalanceReceived, CancelTask, GetPendingTasks, SaveSnapshot, Snapshot, TaskCompleted, TransactionsConfirmed}
import play.api.libs.json.Json
import scorex.app.RunnableApplication
import scorex.utils.ScorexLogging

import scala.collection.mutable

class TasksActor(application: RunnableApplication) extends PersistentActor  with ScorexLogging {
  import context.dispatcher

  val tasks = mutable.Map.empty[String, BroadcastSignedTask]
  val pendingTasks = mutable.Map.empty[String, BroadcastUnsignedTask]
  val taskToConfirmed = mutable.Map.empty[String, Set[String]]

  val settings = application.settings.broadcastSettings
  context.system.scheduler.schedule(settings.snapshotsInterval, settings.snapshotsInterval, self, SaveSnapshot)

  def persistTask(t: BroadcastSignedTask): Unit = {
    log.info(s"New task: ${t.id}, number of transactions: ${t.transactions.size}")
  }

  def startTask(t: BroadcastSignedTask): Unit = {
    log.info(s"BroadcastSignedTask: ${t.id} started")
    val confirmed = taskToConfirmed.getOrElse(t.id, Set())
    val task = t.copy(transactions = t.transactions.filterNot(tx => confirmed.contains(tx.idStr)))
    application.transactionBroadcaster ! task
  }

  def startUnsignedTask(t: BroadcastUnsignedTask): Unit = {
    log.info(s"BroadcastUnsignedTask: ${t.id} started")
    val needed = t.getNeededBalances
    if (needed.size == 1 && needed.head.assetId.isEmpty) {
      context.actorOf(Props(classOf[WavesBalanceObserver], t.id, application.wallet.getAccount.address,
        needed.head.balance, application.settings.broadcastSettings))
    } else {
      context.actorOf(Props(classOf[BalanceObserver], t.id, application.wallet.getAccount.address,
        t.getNeededBalances, application.settings.broadcastSettings))
    }
  }

  override def receiveRecover: Receive = {
    case t: BroadcastSignedTask =>
      processNewSigned(t)
    case t: BroadcastUnsignedTask =>
      processNewUnsigned(t)
    case t: TransactionsConfirmed =>
      processConfirmed(t)
    case t: TaskCompleted =>
      processCompleted(t)
    case t: CancelTask =>
      processCanceled(t)
    case t: BalanceReceived =>
      processBalanceReceived(t)
    case SnapshotOffer(_, snapshot: Snapshot) =>
      tasks ++= snapshot.tasks
      pendingTasks ++= snapshot.pending
      taskToConfirmed ++= snapshot.confirmed
      log.info(s"Recovering OrderBook from snapshot: $snapshot for $persistenceId")
    case RecoveryCompleted =>
      restartRemainingTasks()
      log.info("TasksActor - Recovery completed!")
  }

  def restartRemainingTasks(): Unit = {
    tasks.foreach {case(_, t) => startTask(t)}
    pendingTasks.foreach {case(_, t) => startUnsignedTask(t)}
  }

  def processNewSigned(t: BroadcastSignedTask): Unit = {
    tasks += t.id -> t
    application.taskHistory ! TaskData(t.id, t.name, t.timestamp, 0, TaskData.InProgress, t.transactionCount, 0, 0, Set())
  }

  def processNewUnsigned(t: BroadcastUnsignedTask): Unit = {
    pendingTasks += t.id -> t
    application.taskHistory ! TaskData(t.id, t.name, t.timestamp, 0, TaskData.Pending, t.transactionCount, 0, 0, Set())
  }

  def processConfirmed(t: TransactionsConfirmed): Unit = {
    taskToConfirmed += t.taskId -> (taskToConfirmed.getOrElse(t.taskId, Set()) ++ t.txs)
    tasks.get(t.taskId).foreach { task =>
      application.taskHistory ! TaskDataUpdate(task.id, t.txs.size)
    }
  }

  def processCompleted(t: TaskCompleted): Unit = {
    tasks.get(t.taskId).map { b =>
      val confirmed = taskToConfirmed.getOrElse(t.taskId, Set())
      val unconfirmed = b.transactions.map(_.idStr).toSet -- confirmed
      application.taskHistory ! TaskData(t.taskId, b.name, b.timestamp, System.currentTimeMillis(), TaskData.Completed,
        b.transactionCount, confirmed.size, unconfirmed.size, unconfirmed)
      tasks -= t.taskId
    }
  }

  def processCanceled(t: CancelTask): TaskData = {
    val d = pendingTasks.get(t.taskId).map { unsigned =>
      val d = TaskData(unsigned.id, unsigned.name, unsigned.timestamp, System.currentTimeMillis(),
        TaskData.Canceled, 0, 0, 0, Set())
      application.taskHistory ! d
      d
    }
    pendingTasks -= t.taskId
    d.getOrElse(TaskData("", "", 0, 0, TaskData.Canceled, 0, 0, 0, Set()))
  }


  override def receiveCommand: Receive = {
    case t: BroadcastSignedTask =>
      persist(t) { t =>
        log.info(s"Received new BroadcastSignedTask: ${t.id}, name: ${t.name}")
        processNewSigned(t)
        startTask(t)
      }
    case t: BroadcastUnsignedTask =>
      persist(t) { t =>
        log.info(s"Received new BroadcastUnsignedTask: ${t.id}, name: ${t.name}")
        processNewUnsigned(t)
        startUnsignedTask(t)
      }
    case b: BalanceReceived =>
      persist(b) {b =>
        log.info(s"Received new BalanceReceived: ${b.taskId}")
        startPending(b)
        processBalanceReceived(b)
      }
    case t: TransactionsConfirmed =>
      persist(t) { t =>
        log.info(s"Task: ${t.taskId} - ${t.txs.size} transactions confirmed")
        processConfirmed(t)
      }
    case t: TaskCompleted =>
      persist(t) { t =>
        log.info(s"Task: ${t.taskId} completed")
        processCompleted(t)
      }
    case t: CancelTask =>
      val s = sender()
      persist(t) { t =>
        log.info(s"Task: ${t.taskId} canceled")
        val d = processCanceled(t)
        s ! Seq(d)
      }
    case GetPendingTasks =>
      sender() ! pendingTasks.values.toSeq
    case SaveSnapshot =>
      deleteSnapshots(SnapshotSelectionCriteria.Latest)
      saveSnapshot(Snapshot(tasks.toMap, pendingTasks.toMap, taskToConfirmed.toMap))
  }

  def processBalanceReceived(b: BalanceReceived): Unit = {
    pendingTasks -= b.taskId
  }

  def startPending(b: BalanceReceived): Unit = {
    val signedTask = for {
      acc <- application.wallet.privateKeyAccounts().headOption
      task <- pendingTasks.get(b.taskId)
    } yield BroadcastSignedTask.signTransactions(acc, task)

    signedTask.foreach(self ! _)
  }

  override def persistenceId: String = "TasksActor"
}

object TasksActor {
  case class TransactionsConfirmed(taskId: String, txs: Set[String])
  case class TaskCompleted(taskId: String)
  case class BalanceReceived(taskId: String)
  case class CancelTask(taskId: String)

  case object SaveSnapshot
  case class Snapshot(tasks: Map[String, BroadcastSignedTask], pending: Map[String, BroadcastUnsignedTask],
                      confirmed: Map[String, Set[String]])

  case object GetPendingTasks

  implicit val transactionsConfirmedFormat = Json.format[TransactionsConfirmed]
  implicit val taskCompletedFormat = Json.format[TaskCompleted]
  implicit val cancelTaskFormat = Json.format[CancelTask]
  implicit val balanceReceivedFormat = Json.format[BalanceReceived]
}