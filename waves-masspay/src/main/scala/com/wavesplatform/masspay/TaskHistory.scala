package com.wavesplatform.masspay

import java.io.File

import akka.actor.Actor
import com.wavesplatform.masspay.TaskHistory.{GetTaskStatus, GetTaskStatusByDay, GetTaskStatusByName, TaskDataUpdate}
import com.wavesplatform.settings.BroadcastSettings
import org.h2.mvstore.{MVMap, MVStore}
import play.api.libs.json.Json
import TaskData._

class TaskHistory(settings: BroadcastSettings) extends Actor {
  def createMVStore(): MVStore = {
    if (settings.taskHistoryFile.nonEmpty) {
      val file = new File(settings.taskHistoryFile)
      file.getParentFile.mkdirs().ensuring(file.getParentFile.exists())
      new MVStore.Builder().fileName(settings.taskHistoryFile).compress().open()
    } else {
      new MVStore.Builder().open()
    }
  }

  private val db: MVStore = createMVStore()

  private val tasksNames: MVMap[String, Array[String]] = db.openMap("TasksNames")

  private val tasksStatus: MVMap[String, String] = db.openMap("TasksStatus")

  private val taksDays: MVMap[Long, Array[String]] = db.openMap("TaksDays")

  def calcStartDay(t: Long): Long = {
    val ts = t / 1000
    ts - ts % (24 * 60 * 60)
  }

  override def receive: Receive = {
    case d: TaskData =>
      processTaskData(d)
    case d:TaskDataUpdate =>
      processTaskUpdate(d)
    case GetTaskStatus(id) =>
      sender() ! Option(tasksStatus.get(id)).flatMap(Json.parse(_).validate[TaskData](TaskData.taskDataFormat).asOpt).toSeq
    case GetTaskStatusByName(name) =>
      sender() ! findTasksByName(name)
    case GetTaskStatusByDay(ts) =>
      sender() ! findTasksByTs(ts)
  }

  def findTasksByName(name: String): Seq[TaskData] = {
    for {
      ids <- Option(tasksNames.get(name)).toSeq
      id <- ids
      taskStr <- Option(tasksStatus.get(id)).toSeq
      task <- Json.parse(taskStr).validate[TaskData](TaskData.taskDataFormat).asOpt.toSeq
    } yield task
  }

  def findTasksByTs(ts: Long): Seq[TaskData] = {
    for {
      ids <- Option(taksDays.get(calcStartDay(ts))).toSeq
      id <- ids
      taskStr <- Option(tasksStatus.get(id)).toSeq
      task <- Json.parse(taskStr).validate[TaskData](TaskData.taskDataFormat).asOpt.toSeq
    } yield task
  }

  def processTaskData(d: TaskData): Unit = {
    import TaskData.taskDataFormat
    val prevNames = Option(tasksNames.get(d.name)).getOrElse(Array.empty[String])
    if (!prevNames.contains(d.id)) {
      tasksNames.put(d.name, prevNames :+ d.id)
    }

    val day = calcStartDay(d.start)
    val prevDays = Option(taksDays.get(day)).getOrElse(Array.empty[String])
    if (!prevDays.contains(d.id)) {
      taksDays.put(day, prevDays :+ d.id)
    }

    tasksStatus.put(d.id, Json.stringify(Json.toJson(d)(taskDataFormat)))
    db.commit()
  }

  def processTaskUpdate(d: TaskDataUpdate): Unit = {
    for {
      s <- Option(tasksStatus.get(d.id))
      prevData <- Json.parse(s).validate[TaskData].asOpt
      newData <- Some(prevData.copy(successCount = d.successCount))
    } yield tasksStatus.put(d.id, Json.stringify(Json.toJson(newData)))
  }
}

case class TaskData(id: String, name: String, start: Long, finished: Long, status: String,
                    totalCount: Int, successCount: Int, failedCount: Int,
                    failedTransactions: Set[String])
object TaskData {
  //Statuses
  val Pending = "Pending"
  val InProgress = "InProgress"
  val Completed = "Completed"
  val Canceled = "Canceled"

  import play.api.libs.json._
  implicit val taskDataFormat = Json.format[TaskData]
}

object TaskHistory {
  case class GetTaskStatus(id: String)
  case class GetTaskStatusByName(name: String)
  case class GetTaskStatusByDay(ts: Long)

  case class TaskDataUpdate(id: String, successCount: Int)
}
