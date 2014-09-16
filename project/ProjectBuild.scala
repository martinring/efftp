import sbt._
import Keys._

object ProjectBuild extends Build {

  // #customScalaVersion
  // val scalaVersionString = "scala-effects"
  // val scalaLibraryModuleString = "org.scala-lang:scala-library:"+ scalaVersionString

  // #customScalaVersion
  // def scalaHomeDir(base: File): Option[File] = Some(base / "lib" / "scala")

  // settings valid for both projects (the plugin and the tests)
  val sharedSettings = Seq (
    scalaVersion := "2.11.2",
    libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _ ),
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
    // #customScalaVersion
    // to be clear we're not using a release
    // scalaVersion := scalaVersionString,

    // #customScalaVersion
    // remove scala-library dependency; otherwise sbt tries to download the scala lib version
    // "scala-effects", which fails.
    // libraryDependencies ~= { (deps: Seq[ModuleID]) =>
    //   deps.filterNot(_.toString.startsWith(scalaLibraryModuleString))
    // },

    // add the jars from the scala distro to the classpath
    // (unmanagedJars in Compile) <<= (unmanagedJars in Compile, scalaHome) map { (jars, homeDir) =>
    //   val scalaJars = homeDir.get / "lib" * "*.jar"
    //   jars ++ scalaJars.get.map(Attributed.blank(_))
    // },

    // scalacOptions += "-feature",

    libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.1" % "test",
    libraryDependencies += "org.springframework" % "spring-core" % "3.2.1.RELEASE" % "test"
  )


  lazy val pluginProject: Project = Project(id = "effects-plugin", base = file(".")) settings (
    name := "effects-plugin"

    // #customScalaVersion
    // scalaHome is usually "None" - if it's Some, it defines the compiler that sbt uses
    // scalaHome <<= baseDirectory { scalaHomeDir }
  ) settings (sharedSettings: _*)


// print trees:
// sbt> set (scalacOptions in testsProject) += "-Xprint:typer"
// sbt> set (scalacOptions in testsProject) += "-Yshow-trees"

  lazy val testsProject = Project(id = "tests", base = file("tests")) settings (
    name := "tests",

    unmanagedBase <<= (unmanagedBase in pluginProject),

    // #customScalaVersion
    // scalaHome <<= (baseDirectory in pluginProject) { scalaHomeDir },

    (test in Test) <<= (test in Test).dependsOn(test in (pluginProject, Test)),

    // scala compiler seems to crash on diff_match_patch.java - a problem of the effects plugin?
    compileOrder in Test := CompileOrder.JavaThenScala,

    // for required for passing "-DeffectsPlugin.jarFile=" to the Scalatest suite
    fork := true,

    javaOptions <++= (packageBin in (pluginProject, Compile)) map { pluginJar => Seq(
      "-DeffectsPlugin.jarFile="+ pluginJar.getAbsolutePath,
      "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5006"
//      "-Defftp.traceAnf"
    )},

   scalacOptions <++= (packageBin in (pluginProject, Compile)) map { pluginJar => Seq(
     "-Xplugin:"+ pluginJar.getAbsolutePath,
     "-P:effects:domains:io"
   )}

  ) settings (sharedSettings: _*) dependsOn (pluginProject)
}
