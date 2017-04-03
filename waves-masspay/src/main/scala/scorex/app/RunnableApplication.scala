package scorex.app

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.wavesplatform.masspay._
import com.wavesplatform.http.{ApiRoute, CompositeHttpService}
import com.wavesplatform.settings.WavesSettings
import scorex.crypto.encode.Base58
import scorex.utils.ScorexLogging
import scorex.wallet.Wallet

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.reflect.runtime.universe.Type
import scala.util.Try

trait RunnableApplication extends ScorexLogging {

  def settings: WavesSettings

  protected val apiRoutes: Seq[ApiRoute]
  protected val apiTypes: Seq[Type]

  protected implicit val actorSystem: ActorSystem

  // wallet, needs strict evaluation
  lazy val wallet: Wallet = {
    val maybeWalletFilename = Option(settings.walletSettings.file).filter(_.trim.nonEmpty)
    val seed = Base58.decode(settings.walletSettings.seed).toOption
    new Wallet(maybeWalletFilename, settings.walletSettings.password, seed)
  }

  lazy val tasksActor = actorSystem.actorOf(Props(classOf[TasksActor], this))

  lazy val transactionBroadcaster = actorSystem.actorOf(Props(classOf[TransactionBroadcaster], this))
  lazy val taskHistory = actorSystem.actorOf(Props(classOf[TaskHistory], this.settings.broadcastSettings))
  lazy val distributorActor = actorSystem.actorOf(Props(classOf[DistributorActor], wallet.getAccount, settings.broadcastSettings))

  @volatile private var shutdownInProgress = false
  @volatile var serverBinding: ServerBinding = _

  def run() {
    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")

    implicit val materializer = ActorMaterializer()

    if (settings.restAPISettings.enable) {
      val combinedRoute: Route = CompositeHttpService(actorSystem, apiTypes, apiRoutes, settings.restAPISettings).compositeRoute
      val httpFuture = Http().bindAndHandle(combinedRoute, settings.restAPISettings.bindAddress, settings.restAPISettings.port)
      serverBinding = Await.result(httpFuture, 10.seconds)
      log.info(s"REST API was bound on ${settings.restAPISettings.bindAddress}:${settings.restAPISettings.port}")
    }

    actorSystem.actorOf(Props(classOf[BlockObserver], settings.broadcastSettings), "BlockObserver")

    Seq(tasksActor) foreach {
      _ =>
    }

    //on unexpected shutdown
    sys.addShutdownHook {
      shutdown()
    }
  }

  def shutdown(): Unit = {
    if (!shutdownInProgress) {
      log.info("Stopping network services")
      shutdownInProgress = true
      if (settings.restAPISettings.enable) {
        Try(Await.ready(serverBinding.unbind(), 60.seconds)).failed.map(e => log.error("Failed to unbind REST API port: " + e.getMessage))
      }

      implicit val askTimeout = Timeout(60.seconds)
      Try(Await.result(actorSystem.terminate(), 60.seconds))
        .failed.map(e => log.error("Failed to terminate actor system: " + e.getMessage))
      log.debug("Closing wallet")
      wallet.close()
      log.info("Shutdown complete")
    }
  }
}
