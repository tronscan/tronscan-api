import sbt._

object Dependencies {

  val circeVersion = "0.9.3"
  val slickPgVersion = "0.16.1"
  val monixVersion = "2.3.0"
  val akkaVersion = "2.5.14"
  val grpcVersion = "1.9.0"
  val scaleCubeVersion = "1.0.7"
  val catsVersion = "1.3.1"

  val akkaStreamsContribDeps = Seq(
    "com.typesafe.akka" %% "akka-stream-contrib" % "0.9"
  )

  val circeDependencies = Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-generic-extras",
    "io.circe" %% "circe-parser",
//    "io.circe" %% "circe-scalajs_sjs0.6"
  ).map(_ % circeVersion)

  val akkaDeps = Seq(
    "com.typesafe.akka" %% "akka-actor",
    "com.typesafe.akka" %% "akka-stream",
    "com.typesafe.akka" %% "akka-cluster",
    "com.typesafe.akka" %% "akka-cluster-tools",
    "com.typesafe.akka" %% "akka-testkit",
    "com.typesafe.akka" %% "akka-stream-testkit"
  ).map(_ % akkaVersion)

  val macroParadise = addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)

  val scalaAsync = Seq(
    "org.scala-lang.modules" %% "scala-async" % "0.9.7"
  )

  val grpcDeps = Seq(
    "io.grpc" % "grpc-protobuf" % scalapb.compiler.Version.grpcJavaVersion,
    "io.grpc" % "grpc-stub" % scalapb.compiler.Version.grpcJavaVersion,
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
  )

  val scaleCubeDeps = Seq(
    "io.scalecube" % "scalecube-services",
    "io.scalecube" % "scalecube-cluster",
    "io.scalecube" % "scalecube-transport"
  ).map(_ % scaleCubeVersion)

  val catsDeps = Seq(
    "org.typelevel" %% "cats-core"
  ).map(_ % catsVersion) ++ Seq(
    "org.typelevel" %% "cats-effect" % "1.0.0"
  )
}
