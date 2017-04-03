package com.wavesplatform

import java.io.File

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import com.wavesplatform.actor.RootActorSystem
import com.wavesplatform.http.TasksApiRoute
import com.wavesplatform.settings._
import scorex.account.AddressScheme
import scorex.utils.ScorexLogging

import scala.reflect.runtime.universe._

class Application(as: ActorSystem, wavesSettings: WavesSettings) extends {
  val restAPISettings: RestAPISettings = wavesSettings.restAPISettings
  override implicit val settings = wavesSettings
  override implicit val actorSystem = as
} with scorex.app.RunnableApplication {

  override lazy val apiRoutes = Seq(
    TasksApiRoute(this)
  )

  override lazy val apiTypes = Seq(
    typeOf[TasksApiRoute]
  )

  override def run(): Unit = {
    super.run()
  }
}

object Application extends ScorexLogging {
  def main(args: Array[String]): Unit = {
    log.info("Starting...")

    val maybeConfigFile = for {
      maybeFilename <- args.headOption
      file = new File(maybeFilename)
      if file.exists
    } yield file

    if (maybeConfigFile.isEmpty) log.warn("NO CONFIGURATION FILE WAS PROVIDED. STARTING WITH DEFAULT SETTINGS FOR TESTNET!")

    val maybeUserConfig = maybeConfigFile collect {
      case file => ConfigFactory.parseFile(file)
    }

    val config = maybeUserConfig match {
      // if no user config is supplied, the library will handle overrides/application/reference automatically
      case None => ConfigFactory.load()
      // application config needs to be resolved wrt both system properties *and* user-supplied config.
      case Some(cfg) =>
        ConfigFactory.defaultOverrides()
          .withFallback(cfg)
          .withFallback(ConfigFactory.defaultApplication())
          .withFallback(ConfigFactory.defaultReference())
          .resolve()

    }

    val settings = WavesSettings.fromConfig(config)

    RootActorSystem.start("wavesplatform", settings.broadcastSettings) { actorSystem =>
      configureLogging(settings)

      // Initialize global var with actual address scheme
      AddressScheme.current = new AddressScheme {
        override val chainId: Byte = settings.broadcastSettings.addressScheme.head.toByte
      }

      val application = new Application(actorSystem, settings)
      application.run()

      if (application.wallet.privateKeyAccounts().isEmpty)
        application.wallet.generateNewAccounts(1)
    }
  }

  /**
    * Configure logback logging level according to settings
    */
  private def configureLogging(settings: WavesSettings) = {
    import ch.qos.logback.classic.{Level, LoggerContext}
    import org.slf4j._

    val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME)
    settings.loggingLevel match {
      case LogLevel.DEBUG => rootLogger.setLevel(Level.DEBUG)
      case LogLevel.INFO => rootLogger.setLevel(Level.INFO)
      case LogLevel.WARN => rootLogger.setLevel(Level.WARN)
      case LogLevel.ERROR => rootLogger.setLevel(Level.ERROR)
    }
  }
}
