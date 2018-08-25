import Dependencies._

name := "tronscan"
organization := "org.tronscan"

version := "latest"


scalaVersion := "2.12.4"

dependencyOverrides ++= Seq(
  "com.fasterxml.jackson.module" % "jackson-module-scala_2.12" % "2.9.2"
)


// Library Dependencies
libraryDependencies ++= Seq(

  guice,

  "com.google.protobuf" % "protobuf-java" % "3.4.0" % "protobuf",
  "com.google.api.grpc" % "googleapis-common-protos" % "0.0.3" % "protobuf",
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",

  "org.scala-lang.modules" %% "scala-async" % "0.9.6",

  // Tron
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "org.slf4j" % "jcl-over-slf4j" % "1.7.25",
  "log4j" % "log4j" % "1.2.17",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "commons-codec" % "commons-codec" % "1.11",
  "com.madgag.spongycastle" % "core" % "1.53.0.0",
  "com.madgag.spongycastle" % "prov" % "1.53.0.0",
  "org.iq80.leveldb" % "leveldb" % "0.10",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "org.apache.commons" % "commons-collections4" % "4.0",
  "com.typesafe" % "config" % "1.3.2",
  "com.cedarsoftware" % "java-util" % "1.8.0",
  "org.apache.commons" % "commons-lang3" % "3.4",
  "org.apache.commons" % "commons-collections4" % "4.0",
  "com.beust" % "jcommander" % "1.72",
  "joda-time" % "joda-time" % "2.3",

  // Data Access
  "com.typesafe.play" %% "play-slick" % "3.0.1",
  "com.typesafe.play" %% "play-json-joda" % "2.6.9",

  "com.github.tminglei" %% "slick-pg" % "0.16.2",
  "com.github.tminglei" %% "slick-pg_play-json" % "0.16.2",
  "com.github.tminglei" %% "slick-pg_circe-json" % "0.16.2",
  "com.github.tminglei" %% "slick-pg_joda-time" % "0.16.2",

  "org.postgresql" %  "postgresql" % "42.2.2",
  "com.maxmind.geoip2" % "geoip2" % "2.10.0",

  "io.monix" %% "monix" % "3.0.0-RC1",

  "com.lightbend.play" %% "play-socket-io" % "1.0.0-beta-2",

  "org.jsoup" % "jsoup" % "1.11.3",

  "io.swagger" %% "swagger-play2" % "1.6.0",

  "io.lemonlabs" %% "scala-uri" % "1.1.1",

  ws,

  specs2,

  play.sbt.PlayImport.cacheApi,
  "com.github.karelcemus" %% "play-redis" % "2.1.1",

  ehcache,
  "com.beachape.metascraper" %% "metascraper" % "0.4.0",

  "com.pauldijou" %% "jwt-play-json" % "0.16.0",
  "com.pauldijou" %% "jwt-play" % "0.16.0",

  "org.ocpsoft.prettytime" % "prettytime" % "4.0.1.Final",

  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test,
  "com.fasterxml.jackson.module" % "jackson-module-scala_2.12" % "2.9.2"

) ++ grpcDeps ++ akkaDeps ++ circeDependencies ++ akkaStreamsContribDeps

// Disable API Documentation
sources in (Compile, doc) := Seq.empty
publishArtifact in (Compile, packageDoc) := false

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    PB.protoSources in Compile := Seq(file("app/protobuf"), file("target/protobuf_external/google/api")),
    PB.includePaths in Compile := Seq(file("app/protobuf"), file("target/protobuf_external")),
    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    ),
  )
