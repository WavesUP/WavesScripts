package com.wavesplatform.masspay

import akka.actor.{Actor, PoisonPill}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.wavesplatform.masspay.TransactionBroadcaster.UtxPool
import com.wavesplatform.settings.BroadcastSettings
import play.api.libs.json.{JsObject, Json}
import scorex.utils.ScorexLogging

class UtxRequestActor(settings: BroadcastSettings) extends Actor with ScorexLogging {
  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  override def preStart(): Unit = {
    val uri = settings.knownNodes.head + "/transactions/unconfirmed"
    http.singleRequest(HttpRequest(uri = uri)).pipeTo(self)
  }

  override def receive: Receive = {
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      entity.dataBytes.runFold(ByteString(""))(_ ++ _).map { body =>
        val txs = Json.parse(body.utf8String).as[Seq[JsObject]].map(_.value("id").as[String]).toSet
        context.parent ! UtxPool(txs)
        self ! PoisonPill
      }
    case resp @ HttpResponse(code, _, _, _) =>
      log.info("Request failed, response code: " + code)
      resp.discardEntityBytes()
      self ! PoisonPill
  }
}
