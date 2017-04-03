package com.wavesplatform.masspay

import akka.actor.{Actor, PoisonPill}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.wavesplatform.masspay.DistributorActor.AssetDistribution
import com.wavesplatform.settings.BroadcastSettings
import play.api.libs.json.Json
import scorex.utils.ScorexLogging

class DistributionRequestActor(baseAssetId: String, settings: BroadcastSettings) extends Actor with ScorexLogging {
  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  override def preStart(): Unit = {
    val uri = settings.knownNodes.head + s"/assets/$baseAssetId/distribution"
    http.singleRequest(HttpRequest(uri = uri))
      .pipeTo(self)
  }

  override def receive: Receive = {
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      entity.dataBytes.runFold(ByteString(""))(_ ++ _).map { body =>
        log.info(s"Received distribution by $baseAssetId")
        val d = Json.parse(body.utf8String).asOpt[Map[String, Long]].getOrElse(Map())
        context.parent ! AssetDistribution(baseAssetId, d)
        self ! PoisonPill
      }
    case resp @ HttpResponse(code, _, _, _) =>
      log.info("Request failed, response code: " + code)
      resp.discardEntityBytes()
      self ! PoisonPill
  }
}
