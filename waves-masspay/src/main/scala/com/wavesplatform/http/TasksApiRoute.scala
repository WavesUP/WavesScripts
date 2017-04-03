package com.wavesplatform.http

import javax.ws.rs.Path

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.Multipart.BodyPart
import akka.http.scaladsl.model.{HttpResponse, Multipart, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.{ByteString, Timeout}
import com.wavesplatform.masspay.DistributorActor.Distribution
import com.wavesplatform.masspay.TaskHistory.{GetTaskStatus, GetTaskStatusByDay, GetTaskStatusByName}
import com.wavesplatform.masspay.TasksActor.{CancelTask, GetPendingTasks}
import com.wavesplatform.masspay._
import io.swagger.annotations._
import play.api.libs.functional.syntax.unlift
import play.api.libs.json.{Format, Json, __}
import scorex.app.RunnableApplication
import scorex.utils.ScorexLogging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

@Path("/tasks")
@Api(value = "tasks")
case class TasksApiRoute(application: RunnableApplication)(implicit system: ActorSystem) extends ApiRoute with CommonApiFunctions with ScorexLogging {

  implicit val executor: ExecutionContextExecutor = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val settings = application.settings.restAPISettings
  val broadcastSettings = application.settings.broadcastSettings

  override lazy val route: Route = pathPrefix("tasks") {
    address ~ status ~ statusByName ~ statusByTimestamp ~ broadcastSigned ~ broadcastUnsigned ~
      distribution ~ pendingStatus ~ cancelPending

  }

  @Path("/address")
  @ApiOperation(value = "Address", notes = "Get account address of the Task Manager", httpMethod = "GET")
  def address: Route = (get & path("address")) {
    complete(Json.arr(application.wallet.getAccount.address))
  }

  @Path("/id/{taskId}")
  @ApiOperation(value = "Status", notes = "Get status of the task by id", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "taskId", value = "Task Id", required = true, dataType = "string", paramType = "path")
  ))
  def status: Route = (get & path("id" / Segment)) { taskId =>
    implicit val timeout = Timeout(5.seconds)

    complete((application.taskHistory ? GetTaskStatus(taskId))
      .mapTo[Seq[TaskData]]
    )
  }

  @Path("/cancel/{taskId}")
  @ApiOperation(value = "Status", notes = "Get status of the task by id", httpMethod = "POST")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "taskId", value = "Task Id", required = true, dataType = "string", paramType = "path")
  ))
  def cancelPending: Route = (post & withAuth & path("cancel" / Segment)) { taskId =>
    implicit val timeout = Timeout(5.seconds)

    complete((application.tasksActor ? CancelTask(taskId))
      .mapTo[Seq[TaskData]]
    )
  }


  @Path("/name/{taskName}")
  @ApiOperation(value = "Status by name", notes = "Get status of all tasks by name", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "taskName", value = "Task Name", required = true, dataType = "string", paramType = "path")
  ))
  def statusByName: Route = (get & path("name" / Segment)) { taskName =>
    implicit val timeout = Timeout(5.seconds)

    complete((application.taskHistory ? GetTaskStatusByName(taskName))
      .mapTo[Seq[TaskData]]
    )
  }

  @Path("/timestamp/{timestamp}")
  @ApiOperation(value = "Status by timestamp", notes = "Get status of all tasks by timestamp", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "timestamp", value = "Timestamp", required = true, dataType = "string", paramType = "path")
  ))
  def statusByTimestamp: Route = (get & path("timestamp" / LongNumber)) { timestamp: Long =>
    implicit val timeout = Timeout(5.seconds)

    complete((application.taskHistory ? GetTaskStatusByDay(timestamp))
      .mapTo[Seq[TaskData]]
    )
  }

  @Path("/pending")
  @ApiOperation(value = "Pending Tasks Status", notes = "Get status of all pending tasks", httpMethod = "GET")
  def pendingStatus: Route = (get & path("pending") & withAuth) {
    implicit val timeout = Timeout(5.seconds)

    complete((application.tasksActor ? GetPendingTasks)
      .mapTo[Seq[BroadcastUnsignedTask]].map(t => t.map(_.toJson))
    )
  }

  @Path("/broadcast/signed")
  @ApiOperation(value = "Submit signed task",
    notes = "Submit task with signed transactions. " +
      "Csv format: senderPubKey;assetId;recipient;amount;fee;timestamp;attachment;signature",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "multipart/form-data")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "name",
      value = "Name of task",
      required = true,
      paramType = "query",
      dataType = "string"
    ),
    new ApiImplicitParam(
      name = "file",
      value = "Csv file with transactions",
      required = true,
      paramType = "formData",
      dataType = "file"
    )))
  def broadcastSigned: Route = path("broadcast" / "signed") {
    (post & withAuth & entity(as[Multipart.FormData]) ) { formData =>
      parameters('name) { name =>
        complete {
          val extractedData: Future[ByteString] = formData.parts.mapAsync[ByteString](1) {

            case file: BodyPart if file.name == "file" =>
              file.entity.dataBytes.runFold(ByteString(""))(_ ++ _)

            case data: BodyPart => data.toStrict(2.seconds).map(strict => strict.entity.data)
          }.runFold(ByteString(""))(_ ++ _)

          extractedData.flatMap { data: ByteString =>
            Tasks.createTaskFromSignedTransactions(name, data.utf8String) match {
              case Success(task) => Future.successful(task)
              case Failure(ex) => Future.failed(ex)
            }
          }.map { task: Task =>
            application.tasksActor ! task
            HttpResponse(StatusCodes.OK, entity = Json.stringify(Json.obj("taskId" -> task.id, "createdAt" -> task.timestamp)))
          }.recover {
            case ex: Exception =>
              log.warn("Error during parsing csv", ex)
              HttpResponse(StatusCodes.InternalServerError, entity = s"Error in processing csv task data due to ${ex.getMessage}")
          }
        }
      }
    }
  }

  @Path("/broadcast/unsigned")
  @ApiOperation(value = "Submit unsigned task",
    notes = "Submit task with distribution transactions and waits for money appears on the broadcaster's account. " +
      "Csv format: assetId;recipient;amount;attachment",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "multipart/form-data")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "name",
      value = "Name of task",
      required = true,
      paramType = "query",
      dataType = "string"
    ),
    new ApiImplicitParam(
      name = "file",
      value = "Json with data",
      required = true,
      paramType = "formData",
      dataType = "file"
    )))
  def broadcastUnsigned: Route = path("broadcast" / "unsigned") {
    (post & withAuth & entity(as[Multipart.FormData]) ) { formData =>
      parameters('name) { name =>
        complete {
          val extractedData: Future[ByteString] = formData.parts.mapAsync[ByteString](1) {

            case file: BodyPart if file.name == "file" =>
              file.entity.dataBytes.runFold(ByteString(""))(_ ++ _)

            case data: BodyPart => data.toStrict(2.seconds).map(strict => strict.entity.data)
          }.runFold(ByteString(""))(_ ++ _)

          extractedData.flatMap { data: ByteString =>
            val pubKey = application.wallet.getAccount
            Tasks.createTaskFromUnsignedTransactions(name, data.utf8String, broadcastSettings.txFee, pubKey) match {
              case Success(task) => Future.successful(task)
              case Failure(ex) => Future.failed(ex)
            }
          }.map { task: Task =>
            application.tasksActor ! task
            HttpResponse(StatusCodes.OK, entity = Json.stringify(Json.obj("taskId" -> task.id, "createdAt" -> task.timestamp)))
          }.recover {
            case ex: Exception =>
              log.warn("Error during parsing csv", ex)
              HttpResponse(StatusCodes.InternalServerError, entity = s"Error in processing csv task data due to ${ex.getMessage}")
          }
        }
      }
    }
  }

  @Path("/distribution")
  @ApiOperation(value = "Submit distribution task",
    notes = "Submit task to distribute one asset based on the balance of another one",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "multipart/form-data")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "name",
      value = "Name of task",
      required = true,
      paramType = "query",
      dataType = "string"
    ),
    new ApiImplicitParam(
      name = "assetId",
      value = "Asset Id to distribute",
      required = true,
      paramType = "query",
      dataType = "string"
    ),
    new ApiImplicitParam(
      name = "amount",
      value = "Amount to distribute",
      required = true,
      paramType = "query",
      dataType = "long"
    ),
    new ApiImplicitParam(
      name = "baseAssetId",
      value = "Asset Id, based on which distribution is calculated",
      required = true,
      paramType = "query",
      dataType = "string"
    )))
  def distribution: Route = (path("distribution") & post & withAuth) {
      parameters('name, 'assetId, 'amount.as[Long], 'baseAssetId) { (name, assetId, amount, baseAssetId) =>
        implicit val timeout = Timeout(20.seconds)
        complete {
          val distr = Distribution(name, if (assetId == "WAVES") None else Some(assetId), amount, baseAssetId )
          (application.distributorActor ? distr).mapTo[BroadcastUnsignedTask].map { task =>
            application.tasksActor ! task
            HttpResponse(StatusCodes.OK, entity = Json.prettyPrint(task.toJson))
          }.recover {
            case ex: Exception =>
              log.warn(s"Error during creating distrinution: $distr", ex)
              HttpResponse(StatusCodes.InternalServerError, entity = s"Error during creating distrinution: $distr due to ${ex.getMessage}")
          }
        }
      }
  }
}
