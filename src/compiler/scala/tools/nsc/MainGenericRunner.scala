/* NSC -- new Scala compiler
 * Copyright 2006-2010 LAMP/EPFL
 * @author  Lex Spoon
 */

// $Id$

package scala.tools.nsc

import java.io.{ File, IOException }
import java.lang.{ClassNotFoundException, NoSuchMethodException}
import java.lang.reflect.InvocationTargetException
import java.net.{ URL, MalformedURLException }
import scala.tools.util.PathResolver

import util.{ ClassPath, ScalaClassLoader }
import File.pathSeparator
import Properties.{ versionString, copyrightString }

/** An object that runs Scala code.  It has three possible
  * sources for the code to run: pre-compiled code, a script file,
  * or interactive entry.
  */
object MainGenericRunner {
  def main(args: Array[String]) {
    def errorFn(str: String) = Console println str

    val command = new GenericRunnerCommand(args.toList, errorFn)
    val settings = command.settings
    def sampleCompiler = new Global(settings)

    if (!command.ok)
      return errorFn("%s\n%s".format(command.usageMsg, sampleCompiler.pluginOptionsHelp))

    // append the jars in ${scala.home}/lib to the classpath, as well as "." if none was given.
    val needDot = settings.classpath.value == ""
    settings appendToClasspath PathResolver.genericRunnerClassPath
    if (needDot)
      settings appendToClasspath "."

    settings.defines.applyToCurrentJVM

    if (settings.version.value)
      return errorFn("Scala code runner %s -- %s".format(versionString, copyrightString))

    if (command.shouldStopWithInfo)
      return errorFn(command getInfoMessage sampleCompiler)

    def exitSuccess: Nothing = exit(0)
    def exitFailure(msg: Any = null): Nothing = {
      if (msg != null) errorFn(msg.toString)
      exit(1)
    }
    def exitCond(b: Boolean): Nothing =
      if (b) exitSuccess else exitFailure(null)

    def fileToURL(f: File): Option[URL] =
      try { Some(f.toURI.toURL) }
      catch { case e => Console.println(e); None }

    def paths(str: String): List[URL] =
      for (
        file <- ClassPath.expandPath(str) map (new File(_)) if file.exists;
        val url = fileToURL(file); if !url.isEmpty
      ) yield url.get

    def jars(dirs: String): List[URL] =
      for (
        libdir <- ClassPath.expandPath(dirs) map (new File(_)) if libdir.isDirectory;
        jarfile <- libdir.listFiles if jarfile.isFile && jarfile.getName.endsWith(".jar");
        val url = fileToURL(jarfile); if !url.isEmpty
      ) yield url.get

    def specToURL(spec: String): Option[URL] =
      try   { Some(new URL(spec)) }
      catch { case e: MalformedURLException => Console.println(e); None }

    def urls(specs: String): List[URL] =
      if (specs == null || specs.length == 0) Nil
      else for (
        spec <- specs.split(" ").toList;
        val url = specToURL(spec); if !url.isEmpty
      ) yield url.get

    val classpath: List[URL] = (
      paths(settings.bootclasspath.value) :::
      paths(settings.classpath.value) :::
      jars(settings.extdirs.value) :::
      urls(settings.Xcodebase.value)
    ).distinct

    def createLoop(): InterpreterLoop = {
      val loop = new InterpreterLoop
      loop main settings
      loop
    }

    def dashe = settings.execute.value
    def dashi = settings.loadfiles.value
    def slurp = dashi map (file => io.File(file).slurp()) mkString "\n"

    /** Was code given in a -e argument? */
    if (!settings.execute.isDefault) {
      /** If a -i argument was also given, we want to execute the code after the
       *  files have been included, so they are read into strings and prepended to
       *  the code given in -e.  The -i option is documented to only make sense
       *  interactively so this is a pretty reasonable assumption.
       *
       *  This all needs a rewrite though.
       */
      val fullArgs = command.thingToRun.toList ::: command.arguments
      val code =
        if (settings.loadfiles.isDefault) dashe
        else slurp + "\n" + dashe

      exitCond(ScriptRunner.runCommand(settings, code, fullArgs))
    }
    else command.thingToRun match {
      case None             => createLoop()
      case Some(thingToRun) =>
        val isObjectName =
          settings.howtorun.value match {
            case "object" => true
            case "script" => false
            case "guess"  => ScalaClassLoader.classExists(classpath, thingToRun)
          }

        if (isObjectName)
          try ObjectRunner.run(classpath, thingToRun, command.arguments)
          catch {
            case e @ (_: ClassNotFoundException | _: NoSuchMethodException) => exitFailure(e)
            case e: InvocationTargetException =>
              e.getCause.printStackTrace
              exitFailure()
          }
        else
          try exitCond(ScriptRunner.runScript(settings, thingToRun, command.arguments))
          catch {
            case e: IOException       => exitFailure(e.getMessage)
            case e: SecurityException => exitFailure(e)
          }
    }
  }
}
