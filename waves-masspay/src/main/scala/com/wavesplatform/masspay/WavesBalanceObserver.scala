package com.wavesplatform.masspay

import akka.actor.{Actor, PoisonPill}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.wavesplatform.masspay.BlockObserver.NewBlock
import com.wavesplatform.masspay.TasksActor.BalanceReceived
import com.wavesplatform.settings.BroadcastSettings
import play.api.libs.json.{JsObject, Json}
import scorex.utils.ScorexLogging

class WavesBalanceObserver(taskId: String, accountAddress: String, reqBalance: Long, settings: BroadcastSettings) extends Actor with ScorexLogging {
  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  context.system.eventStream.subscribe(self, classOf[NewBlock])

  override def preStart(): Unit = {
    requestBalances()
  }

  def requestBalances(): Unit = {

    val uri = settings.knownNodes.head + "/addresses/balance/" + accountAddress
    http.singleRequest(HttpRequest(uri = uri)).pipeTo(self)
  }

  override def receive: Receive = {
    case NewBlock(_) =>
      requestBalances()
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      entity.dataBytes.runFold(ByteString(""))(_ ++ _).map { body =>
        val balance: Long = (for {
          res <- Json.parse(body.utf8String).asOpt[JsObject]
          jsBal <- res.value.get("balance")
          bal <- jsBal.asOpt[Long]
        } yield bal).getOrElse(0)

        if (reqBalance <= balance) {
          context.parent ! BalanceReceived(taskId)
          self ! PoisonPill
        }
      }
    case resp @ HttpResponse(code, _, _, _) =>
      log.info("Request failed, response code: " + code)
      resp.discardEntityBytes()
  }

}