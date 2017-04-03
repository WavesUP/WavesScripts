package com.wavesplatform.masspay

import java.util.UUID

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import com.wavesplatform.masspay.DistributorActor.{AssetDistribution, Distribution}
import com.wavesplatform.settings.BroadcastSettings
import scorex.account.PublicKeyAccount
import scorex.utils.ScorexLogging

import scala.collection.mutable
import scala.math.BigDecimal.RoundingMode.HALF_DOWN

class DistributorActor(publicKeyAccount: PublicKeyAccount, settings: BroadcastSettings) extends Actor with ScorexLogging {

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  val pendingRequest = mutable.Map.empty[String, Seq[(Distribution, ActorRef)]]

  def sendRequestt(baseAssetId: String): Unit = {
    context.actorOf(Props(classOf[DistributionRequestActor], baseAssetId, settings))
  }

  override def receive: Receive = {
    case d: Distribution =>
      sendRequestt(d.baseAssetId)
      pendingRequest += d.baseAssetId -> (pendingRequest.getOrElse(d.baseAssetId, Seq()) :+ (d, sender()))
    case AssetDistribution(baseAssetId, bal) =>
      pendingRequest.getOrElse(baseAssetId, Seq()).foreach { d =>
        d._2 ! createTask(d._1, bal)
      }
  }

  def createTask(d: Distribution, assetBal: Map[String, Long]): BroadcastUnsignedTask = {
    def round(b: BigDecimal): Long = b.setScale(0, HALF_DOWN).toLong
    val totalSrcTokens = assetBal.foldLeft(0L) { case (b, (adr, avg)) => b + avg }
    val distrRes: Map[String, Long] = assetBal.mapValues(b =>
      round(BigDecimal(b) * d.amount / totalSrcTokens))
    val res: Seq[(String, Long)] = distrRes.filter(_._2 > 0).toSeq.sortWith(_._2 > _._2)

    val ts =  System.currentTimeMillis()
    val txs = res.map { row => AssetTransferRequest(
      publicKeyAccount,
      d.assetId,
      row._1,
      row._2,
      settings.txFee,
      None,
      ts,
      None,
      Array())
    }
    BroadcastUnsignedTask(UUID.randomUUID().toString, d.name, ts, txs)
  }
}

object DistributorActor {
  case class Distribution(name: String, assetId: Option[String], amount: Long, baseAssetId: String)
  case class AssetDistribution(baseAssetId: String, bal: Map[String, Long])
}
