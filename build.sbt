name := "akka-rescheduler"

organization := "com.snapswap"

version := "1.0.4.1"

scalaVersion := "2.11.11"

libraryDependencies ++= {
  val akkaV = "2.5.4"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV % "provided",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test"
  )
}
