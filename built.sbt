name         := "counters"
organization := "fr.janalyse"
description  := "REST API to count stuff"

licenses += "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")

scalaVersion := "3.9.0"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-feature", "-no-indent")

lazy val versions = new {
  // client side dependencies
  val bootstrap = "5.3.8"
  val jquery    = "3.7.1"
  val awesome   = "6.7.2"

  // server side dependencies
  val pureConfig      = "0.17.10"
  val pekko           = "1.7.0"
  val pekkoHttp       = "1.4.0"
  val tapir           = "1.13.32"
  val jsoniter        = "2.41.2"
  val logback         = "1.6.5"
  val slf4j           = "2.0.20"
  val scalatest       = "3.2.20"
  val commonsio       = "2.22.0"
  val webjarsLocator  = "0.52"
}

// client side dependencies
libraryDependencies ++= Seq(
  "org.webjars" % "bootstrap"    % versions.bootstrap,
  "org.webjars" % "jquery"       % versions.jquery,
  "org.webjars" % "font-awesome" % versions.awesome
)

// server side dependencies
libraryDependencies ++= Seq(
  "com.github.pureconfig"                 %% "pureconfig-core"           % versions.pureConfig,
  "org.apache.pekko"                      %% "pekko-actor-typed"         % versions.pekko,
  "org.apache.pekko"                      %% "pekko-http"                % versions.pekkoHttp,
  "org.apache.pekko"                      %% "pekko-stream"              % versions.pekko,
  "org.apache.pekko"                      %% "pekko-slf4j"               % versions.pekko,
  "org.apache.pekko"                      %% "pekko-testkit"             % versions.pekko     % Test,
  "org.apache.pekko"                      %% "pekko-stream-testkit"      % versions.pekko     % Test,
  "org.apache.pekko"                      %% "pekko-actor-testkit-typed" % versions.pekko     % Test,
  "org.apache.pekko"                      %% "pekko-http-testkit"        % versions.pekkoHttp % Test,
  "com.softwaremill.sttp.tapir"           %% "tapir-pekko-http-server"   % versions.tapir,
  "com.softwaremill.sttp.tapir"           %% "tapir-jsoniter-scala"      % versions.tapir,
  "com.softwaremill.sttp.tapir"           %% "tapir-swagger-ui-bundle"   % versions.tapir,
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"       % versions.jsoniter,
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros"     % versions.jsoniter  % "compile-internal",
  "org.slf4j"                              % "slf4j-api"                 % versions.slf4j,
  "ch.qos.logback"                         % "logback-classic"           % versions.logback,
  "commons-io"                             % "commons-io"                % versions.commonsio,
  "org.scalatest"                         %% "scalatest"                 % versions.scalatest % Test,
  "org.webjars"                            % "webjars-locator"           % versions.webjarsLocator
)

Compile / mainClass    := Some("counters.Main")
packageBin / mainClass := Some("counters.Main")

Test / testOptions += {
  val rel = scalaVersion.value.split("[.]").take(2).mkString(".")
  Tests.Argument(
    "-oDF", // -oW to remove colors
    "-u",
    s"target/junitresults/scala-$rel/"
  )
}

enablePlugins(JavaServerAppPackaging)
enablePlugins(SbtTwirl)

homepage := Some(url("https://github.com/dacr/counters"))
scmInfo  := Some(ScmInfo(url(s"https://github.com/dacr/counters.git"), s"git@github.com:dacr/counters.git"))

developers := List(
  Developer(
    id = "dacr",
    name = "David Crosson",
    email = "crosson.david@gmail.com",
    url = url("https://github.com/dacr")
  )
)

Universal / topLevelDirectory := None
Universal / packageName       := s"${name.value}"
