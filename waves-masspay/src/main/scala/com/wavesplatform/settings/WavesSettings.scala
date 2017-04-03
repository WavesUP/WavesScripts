package com.wavesplatform.settings

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.EnumerationReader._

object LogLevel extends Enumeration {
  val DEBUG = Value("DEBUG")
  val INFO = Value("INFO")
  val WARN = Value("WARN")
  val ERROR = Value("ERROR")
}

case class WavesSettings(directory: String,
                         loggingLevel: LogLevel.Value,
                         broadcastSettings: BroadcastSettings,
                         restAPISettings: RestAPISettings,
                         walletSettings: WalletSettings)

object WavesSettings {
  val configPath: String = "waves"
  def fromConfig(config: Config): WavesSettings = {
    val directory = config.as[String](s"$configPath.directory")
    val loggingLevel = config.as[LogLevel.Value](s"$configPath.logging-level")

    val broadcastSettings = BroadcastSettings.fromConfig(config)
    val walletSettings = WalletSettings.fromConfig(config)
    val restAPISettings = RestAPISettings.fromConfig(config)

    WavesSettings(directory, loggingLevel, broadcastSettings, restAPISettings, walletSettings)
  }
}
