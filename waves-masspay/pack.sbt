import com.typesafe.sbt.SbtNativePackager.autoImport._
import sbt.Keys._

addCommandAlias("packageAll", "; clean " +
  "; systemd/packageDebianSystemd " +
  "; clean " +
  "; upstart/packageDebianUpstart " +
  "; clean" +
  "; packageJar"
)

val network = Option(System.getProperty("network")).getOrElse("mainnet")

lazy val packageJar = taskKey[File]("creates fat jar")

lazy val packageJarTask = packageJar := {
  val output = baseDirectory.value / "package" / s"waves-masspay${version.value}.jar"
  val jarFile = (assembly in Compile).value
  streams.value.log.info(s"Moving package ${jarFile.getAbsoluteFile} to ${output.getAbsolutePath}")
  IO.move(jarFile, output)
  output
}

val commonSettings: Seq[Setting[_]] = Seq(
  javaOptions in Universal ++= Seq(
    "-J-server",
    // JVM memory tuning for 1g ram
    "-J-Xms128m",
    "-J-Xmx1024m",

    // from https://groups.google.com/d/msg/akka-user/9s4Yl7aEz3E/zfxmdc0cGQAJ
    "-J-XX:+UseG1GC",
    "-J-XX:+UseNUMA",
    "-J-XX:+AlwaysPreTouch",

    // may be can't use with jstack and others tools
    "-J-XX:+PerfDisableSharedMem",
    "-J-XX:+ParallelRefProcEnabled",
    "-J-XX:+UseStringDeduplication",

    "-J-Dsun.net.inetaddr.ttl=60"),
  assemblyMergeStrategy in assembly := {
    case "application.conf" => MergeStrategy.concat
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  },
  mainClass in Compile := Some("com.wavesplatform.Application"),
  packageJarTask
)

import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper._

lazy val root = project.in(file("."))
  .settings(commonSettings)

//assembly settings
assemblyJarName in assembly := "waves-masspay.jar"
test in assembly := {}
