package com.wavesplatform.settings

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration.FiniteDuration

case class BroadcastSettings(knownNodes: List[String],
                             checkBlockInterval: FiniteDuration,
                             addressScheme: String,
                             journalDataDir: String,
                             snapshotsDataDir: String,
                             snapshotsInterval: FiniteDuration,
                             taskHistoryFile: String,
                             batchSize: Int,
                             utxSize: Int,
                             txFee: Long) {

}

object BroadcastSettings {
  val configPath: String = "waves.broadcast"

  def fromConfig(config: Config): BroadcastSettings = {
    val knownNodes: List[String] = config.as[List[String]](s"$configPath.known-nodes")
    val checkBlockInterval = config.as[FiniteDuration](s"$configPath.check-block-interval")
    val addressScheme = config.as[String](s"$configPath.address-scheme")
    val journalDirectory = config.as[String](s"$configPath.journal-directory")
    val snapshotsDirectory = config.as[String](s"$configPath.snapshots-directory")
    val snapshotsInterval = config.as[FiniteDuration](s"$configPath.snapshots-interval")
    val taskHistory = config.as[String](s"$configPath.task-history")
    val batchSize = config.as[Int](s"$configPath.batch-size")
    val utxSize = config.as[Int](s"$configPath.utx-size")
    val txFee = config.as[Long](s"$configPath.tx-fee")

    BroadcastSettings(knownNodes, checkBlockInterval, addressScheme, journalDirectory, snapshotsDirectory,
      snapshotsInterval, taskHistory, batchSize, utxSize, txFee)
  }
}
