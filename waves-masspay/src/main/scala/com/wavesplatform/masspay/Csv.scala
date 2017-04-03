package com.wavesplatform.masspay

import scala.collection.mutable.ArrayBuffer

case class SignedAssetTransferRow(senderPubKey: String,
                                  assetId: String,
                                  recipient: String,
                                  amount: String,
                                  fee: String,
                                  timestamp: String,
                                  attachment: String,
                                  signature: String)

case class UnsignedAssetTransferRow(assetId: String,
                                  recipient: String,
                                  amount: String,
                                  attachment: String)

object Csv {
  /*val pow = Math.pow(10, Config.decimals).toLong
  val df = new DecimalFormat("0." + "0" * Config.decimals)

  def formatDecimals(b: BigDecimal): String = df.format(b/pow)

  def saveCsv(fileName: String, res: Seq[(String, Long, Long)]): Unit = {
    import java.io._

    //res.grouped(Config.sizeOfFile).zipWithIndex.foreach {case (s, i) =>

    val pw = new PrintWriter(new File(fileName))

    //res.foreach {case (s, i) =>
    res.foreach { case(a, b, c) =>
      val amount = BigDecimal.valueOf(b)
      pw.write(a + ";" + formatDecimals(amount) + ";" + c + "\n")
    }
    pw.close()
  }*/

  def readCsvTransfer(src: String): Seq[SignedAssetTransferRow] = {
    val res = ArrayBuffer[SignedAssetTransferRow]()
    for (line <- src.lines) {
      val cols = line.split(";", -1).map(_.trim)
      if (cols.length == 8) {
        res += SignedAssetTransferRow(cols(0), cols(1), cols(2), cols(3), cols(4), cols(5), cols(6), cols(7))
      }
    }
    res
  }

  def readCsvUnsignedTransfer(src: String): Seq[UnsignedAssetTransferRow] = {
    val res = ArrayBuffer[UnsignedAssetTransferRow]()
    for (line <- src.lines) {
      val cols = line.split(";", -1).map(_.trim)
      if (cols.length == 4) {
        res += UnsignedAssetTransferRow(cols(0), cols(1), cols(2), cols(3))
      }
    }
    res
  }
}