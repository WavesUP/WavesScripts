package com.wavesplatform.masspay

import akka.actor.{Actor, PoisonPill}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.wavesplatform.masspay.TransactionBroadcaster.{BroadcastSent, UtxPool}
import com.wavesplatform.settings.BroadcastSettings
import play.api.libs.json.{JsArray, JsObject, Json}
import scorex.utils.ScorexLogging

class BroadcastRequestActor(settings: BroadcastSettings, txs: Seq[AssetTransferRequest]) extends Actor with ScorexLogging{
  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  override def preStart(): Unit = {
    val uri = settings.knownNodes.head + "/assets/broadcast/batch-transfer"
    val body = Json.stringify(JsArray(txs.map(_.toJson)))
    log.info(s"Sending batch-transfer: ${txs.size} transactions...")
    http.singleRequest(HttpRequest(uri = uri,
      method = HttpMethods.POST,
      entity = HttpEntity(ContentTypes.`application/json`, body)))
      .pipeTo(self)
  }

  override def receive: Receive = {
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      entity.dataBytes.runFold(ByteString(""))(_ ++ _).map { body =>
        log.info("Batch transactions successfully broadcasted")
        val txs = Json.parse(body.utf8String).as[Seq[JsObject]].map(_.value("id").as[String]).toSet
        context.parent ! BroadcastSent(txs)
        self ! PoisonPill
      }
    case resp @ HttpResponse(code, _, _, _) =>
      log.info("Request failed, response code: " + code)
      resp.discardEntityBytes()
      self ! PoisonPill
  }
}
