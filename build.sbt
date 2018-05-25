name := "akka-rescheduler"

organization := "com.snapswap"

version := "1.0.6"

scalaVersion := "2.11.11"

scalacOptions := Seq(
  "-feature",
  "-unchecked",
  "-deprecation",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-Xfatal-warnings",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused-import"
)

libraryDependencies ++= {
  val akkaV = "2.5.4"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV % "provided",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test"
  )
}
