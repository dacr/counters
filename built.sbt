name := "counters"
organization :="fr.janalyse"
homepage := Some(new URL("https://github.com/dacr/counters"))
licenses += "Apache 2" -> url(s"http://www.apache.org/licenses/LICENSE-2.0.txt")
scmInfo := Some(ScmInfo(url(s"https://github.com/dacr/counters.git"), s"git@github.com:dacr/counters.git"))

Compile / mainClass := Some("counters.Main")
packageBin / mainClass := Some("counters.Main")

versionScheme := Some("semver-spec")

scalaVersion := "2.13.6"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-feature")

Test / testOptions += {
  val rel = scalaVersion.value.split("[.]").take(2).mkString(".")
  Tests.Argument(
    "-oDF", // -oW to remove colors
    "-u", s"target/junitresults/scala-$rel/"
  )
}

lazy val versions = new {
  // client side dependencies
  val swaggerui        = "3.44.0"
  val bootstrap        = "4.6.0"
  val jquery           = "3.5.1"
  val awesome          = "5.15.2"

  // server side dependencies
  val pureConfig       = "0.16.0"
  val akka             = "2.6.15"
  val akkaHttp         = "10.2.4"
  val akkaHttpJson4s   = "1.36.0"
  val json4s           = "3.6.11"
  val logback          = "1.2.3"
  val slf4j            = "1.7.31"
  val scalatest        = "3.2.9"
  val commonsio        = "2.10.0"
  val webjarsLocator   = "0.41"
}

// client side dependencies
libraryDependencies ++= Seq(
  "org.webjars" % "swagger-ui"   % versions.swaggerui,
  "org.webjars" % "bootstrap"    % versions.bootstrap,
  "org.webjars" % "jquery"       % versions.jquery,
  "org.webjars" % "font-awesome" % versions.awesome
)

// server side dependencies
libraryDependencies ++= Seq(
  "com.github.pureconfig"  %% "pureconfig"          % versions.pureConfig,
  "org.json4s"             %% "json4s-jackson"      % versions.json4s,
  "org.json4s"             %% "json4s-ext"          % versions.json4s,
  "com.typesafe.akka"      %% "akka-actor-typed"    % versions.akka,
  "com.typesafe.akka"      %% "akka-http"           % versions.akkaHttp,
  "com.typesafe.akka"      %% "akka-http-caching"   % versions.akkaHttp,
  "com.typesafe.akka"      %% "akka-stream"         % versions.akka,
  "com.typesafe.akka"      %% "akka-slf4j"          % versions.akka,
  "com.typesafe.akka"      %% "akka-testkit"        % versions.akka % Test,
  "com.typesafe.akka"      %% "akka-stream-testkit" % versions.akka % Test,
  "com.typesafe.akka"      %% "akka-actor-testkit-typed" % versions.akka % Test,
  "com.typesafe.akka"      %% "akka-http-testkit"   % versions.akkaHttp % Test,
  "de.heikoseeberger"      %% "akka-http-json4s"    % versions.akkaHttpJson4s,
  "org.slf4j"              %  "slf4j-api"           % versions.slf4j,
  "ch.qos.logback"         %  "logback-classic"     % versions.logback,
  "commons-io"             %  "commons-io"          % versions.commonsio,
  "org.scalatest"          %% "scalatest"           % versions.scalatest % Test,
  "org.webjars"            %  "webjars-locator"     % versions.webjarsLocator,
)

enablePlugins(JavaServerAppPackaging)

enablePlugins(SbtTwirl)

// TODO - to remove when twirl will be available for scala3
libraryDependencies := libraryDependencies.value.map {
  case module if module.name == "twirl-api" => module.cross(CrossVersion.for3Use2_13)
  case module                               => module
}
