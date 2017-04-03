package com.wavesplatform.masspay

import akka.actor.Actor
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, _}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.wavesplatform.masspay.BlockObserver.{CheckBlock, NewBlock}
import com.wavesplatform.settings.BroadcastSettings
import play.api.libs.json.Json
import scorex.utils.ScorexLogging

import scala.concurrent.ExecutionContext.Implicits.global

class BlockObserver(settings: BroadcastSettings) extends Actor with ScorexLogging {
  import akka.pattern.pipe

  context.system.scheduler.schedule(settings.checkBlockInterval, settings.checkBlockInterval, self, CheckBlock)
  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  private var prevBlock = Option.empty[Block]

  override def receive: Receive = {
    case CheckBlock =>
      requestLastBlock()
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      entity.dataBytes.runFold(ByteString(""))(_ ++ _).map { body =>
        val b = Json.parse(body.utf8String).as[Block]
        self ! NewBlock(b)
      }
    case resp @ HttpResponse(code, _, _, _) =>
      log.error("Request failed, response code: " + code)
      resp.discardEntityBytes()
    case NewBlock(block) =>
      if (prevBlock.forall(_.signature != block.signature)) {
        context.system.eventStream.publish(NewBlock(block))
        log.debug(s"New block: ${block.signature} received")
        prevBlock = Some(block)
      }
  }

  def requestLastBlock(): Unit = {
    val uri = settings.knownNodes.head + "/blocks/last"
    http.singleRequest(HttpRequest(uri = uri)).pipeTo(self)
  }
}

object BlockObserver {
  object CheckBlock
  case class NewBlock(block: Block)
}
