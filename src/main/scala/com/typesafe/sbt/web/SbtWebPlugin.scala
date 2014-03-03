package com.typesafe.sbt.web

import sbt._
import sbt.Keys._
import akka.actor.{ActorSystem, ActorRefFactory}
import scala.tools.nsc.util.ScalaClassLoader.URLClassLoader
import org.webjars.{WebJarExtractor, FileSystemCache}
import com.typesafe.sbt.web.pipeline.Pipeline
import com.typesafe.sbt.web.incremental.OpSuccess

/**
 * Adds settings concerning themselves with web things to SBT. Here is the directory structure supported by this plugin
 * showing relevant sbt settings:
 *
 * {{{
 *   + src
 *   --+ main
 *   ----+ assets .....(sourceDirectory in Assets)
 *   ------+ js
 *   ----+ public .....(resourceDirectory in Assets)
 *   ------+ css
 *   ------+ images
 *   ------+ js
 *   --+ test
 *   ----+ assets .....(sourceDirectory in TestAssets)
 *   ------+ js
 *   ----+ public .....(resourceDirectory in TestAssets)
 *   ------+ css
 *   ------+ images
 *   ------+ js
 *
 *   + target
 *   --+ public .......(public in Assets)
 *   ----+ css
 *   ----+ images
 *   ----+ js
 *   --+ public-test ..(public in TestAssets)
 *   ----+ css
 *   ----+ images
 *   ----+ js
 * }}}
 *
 * The plugin introduces the notion of "assets" to sbt. Assets are public resources that are intended for client-side
 * consumption e.g. by a browser. This is also distinct from sbt's existing notion of "resources" as
 * project resources are generally not made public by a web server. The name "assets" heralds from Rails.
 *
 * "public" denotes a type of asset that does not require processing i.e. these resources are static in nature.
 *
 * In sbt, asset source files are considered the source for plugins that process them. When they are processed any resultant
 * files become public. For example a coffeescript plugin would use files from "unmanagedSources in Assets" and produce them to
 * "public in Assets".
 *
 * All assets be them subject to processing or static in nature, will be copied to the public destinations.
 *
 * How files are organised within "assets" or "public" is subject to the taste of the developer, their team and
 * conventions at large.
 */

object SbtWebPlugin extends sbt.Plugin {

  object WebKeys {

    import sbt.KeyRanks._

    val Assets = config("web-assets")
    val TestAssets = config("web-assets-test")
    val Plugin = config("web-plugin")

    val public = SettingKey[File]("web-public", "The location of files intended for publishing to the web.")

    val jsFilter = SettingKey[FileFilter]("web-js-filter", "The file extension of js files.")
    val reporter = TaskKey[LoggerReporter]("web-reporter", "The reporter to use for conveying processing results.")

    val nodeModulesPath = SettingKey[File]("web-extract-node-modules-path", "The path to extract WebJar node modules to", ASetting)
    val nodeModules = TaskKey[File]("web-node-modules", "The location of extracted node modules for all related webjars.")
    val webJarsPath = SettingKey[File]("web-extract-web-jars-path", "The path to extract WebJars to", ASetting)
    val webJarsPathLib = SettingKey[String]("web-extract-web-jars-path-sub", "The sub folder of the path to extract WebJars to", ASetting)
    val webJarsCache = SettingKey[File]("web-extract-web-jars-cache", "The path for the webjars extraction cache file", CSetting)
    val webJarsClassLoader = TaskKey[ClassLoader]("web-extract-web-jars-classloader", "The classloader to extract WebJars from", CTask)
    val extractWebJars = TaskKey[File]("web-jars-extract", "Extract webjars.")
    val webJars = TaskKey[Seq[PathMapping]]("web-jars", "Extracted webjars.")

    val assets = TaskKey[File]("web-all-assets", "All of the web assets.")

    val stages = SettingKey[Seq[Task[Pipeline.Stage]]]("web-stages", "Sequence of tasks for the asset pipeline stages.")
    val allStages = TaskKey[Pipeline.Stage]("web-all-stages", "All asset pipeline stages chained together.")
    val pipeline = TaskKey[Seq[PathMapping]]("web-pipeline", "Run all stages of the asset pipeline.")
  }

  import WebKeys._

  override def globalSettings: Seq[Setting[_]] = super.globalSettings ++ Seq(
    onLoad in Global := (onLoad in Global).value andThen load,
    onUnload in Global := (onUnload in Global).value andThen unload
  )

  def webSettings: Seq[Setting[_]] = Seq(
    reporter := new LoggerReporter(5, streams.value.log),

    sourceDirectory in Assets := (sourceDirectory in Compile).value / "assets",
    sourceDirectory in TestAssets := (sourceDirectory in Test).value / "assets",
    sourceManaged in Assets := target.value / "assets-managed",
    sourceManaged in TestAssets := target.value / "test-assets-managed",

    jsFilter in Assets := GlobFilter("*.js"),
    jsFilter in TestAssets := GlobFilter("*Test.js") | GlobFilter("*Spec.js"),

    resourceDirectory in Assets := (sourceDirectory in Compile).value / "public",
    resourceDirectory in TestAssets := (sourceDirectory in Test).value / "public",
    resourceManaged in Assets := target.value / "public-managed",
    resourceManaged in TestAssets := target.value / "public-test-managed",

    public in Assets := target.value / "public",
    public in TestAssets := target.value / "public-test",

    nodeModulesPath in Assets := target.value / "node-modules",
    nodeModulesPath in TestAssets := target.value / "node-modules-test",
    nodeModulesPath in Plugin := (target in Plugin).value / "node-modules",

    webJarsPathLib := "lib",
    webJarsCache in Assets := target.value / "webjars.cache",
    webJarsCache in TestAssets := target.value / "webjars-test.cache",
    webJarsCache in Plugin := (target in Plugin).value / "webjars-plugin.cache",
    webJarsClassLoader in Assets <<= (dependencyClasspath in Compile).map {
      modules =>
        new URLClassLoader(modules.map(_.data.toURI.toURL), null)
    },
    webJarsClassLoader in TestAssets <<= (dependencyClasspath in Test).map {
      modules =>
        new URLClassLoader(modules.map(_.data.toURI.toURL), null)
    },
    webJarsClassLoader in Plugin := SbtWebPlugin.getClass.getClassLoader,

    assets in Assets := {
      syncMappings(
        streams.value.cacheDirectory,
        (mappings in Assets).value,
        (public in Assets).value
      )
    },
    assets in TestAssets := {
      syncMappings(
        streams.value.cacheDirectory,
        (mappings in Assets).value ++ (mappings in TestAssets).value,
        (public in TestAssets).value
      )
    },

    compile in Assets := inc.Analysis.Empty,
    compile in TestAssets := inc.Analysis.Empty,
    compile in TestAssets <<= (compile in TestAssets).dependsOn(compile in Assets),

    test in TestAssets :=(),
    test in TestAssets <<= (test in TestAssets).dependsOn(compile in TestAssets),

    compile in Compile <<= (compile in Compile).dependsOn(compile in Assets),
    compile in Test <<= (compile in Test).dependsOn(compile in TestAssets),
    test in Test <<= (test in Test).dependsOn(test in TestAssets),

    watchSources <++= unmanagedSources in Assets,
    watchSources <++= unmanagedSources in TestAssets,
    watchSources <++= unmanagedResources in Assets,
    watchSources <++= unmanagedResources in TestAssets,

    stages := Seq.empty,
    allStages <<= Pipeline.chain(stages),
    pipeline := allStages.value((mappings in Assets).value),

    baseDirectory in Plugin := (baseDirectory in LocalRootProject).value / "project",
    target in Plugin := (baseDirectory in Plugin).value / "target",
    crossTarget in Plugin := Defaults.makeCrossTarget((target in Plugin).value, scalaBinaryVersion.value, sbtBinaryVersion.value, plugin = true, crossPaths.value)

  ) ++
    inConfig(Assets)(unscopedAssetSettings) ++ inConfig(Assets)(nodeModulesSettings) ++
    inConfig(TestAssets)(unscopedAssetSettings) ++ inConfig(TestAssets)(nodeModulesSettings) ++
    inConfig(Plugin)(nodeModulesSettings)


  val unscopedAssetSettings: Seq[Setting[_]] = Seq(
    includeFilter := GlobFilter("*"),

    sourceGenerators := Nil,
    sourceGenerators <+= Def.task {
      webJars.value.map(_._1)
    },
    managedSourceDirectories := Seq(webJarsPath.value),
    managedSources := sourceGenerators(_.join).map(_.flatten).value,
    unmanagedSourceDirectories := Seq(sourceDirectory.value),
    unmanagedSources := (unmanagedSourceDirectories.value ** (includeFilter.value -- excludeFilter.value)).get,
    sourceDirectories := managedSourceDirectories.value ++ unmanagedSourceDirectories.value,
    sources := managedSources.value ++ unmanagedSources.value,

    resourceGenerators := Nil,
    resourceGenerators <+= Def.task {
      webJars.value.map(_._1)
    },
    managedResourceDirectories := Seq(webJarsPath.value),
    managedResources := resourceGenerators(_.join).map(_.flatten).value,
    unmanagedResourceDirectories := Seq(resourceDirectory.value),
    unmanagedResources := (unmanagedResourceDirectories.value ** (includeFilter.value -- excludeFilter.value)).get,
    resourceDirectories := managedResourceDirectories.value ++ unmanagedResourceDirectories.value,
    resources := managedResources.value ++ unmanagedResources.value,

    webJarsPath := sourceManaged.value / "webjars",
    extractWebJars := {
      withWebJarExtractor(webJarsPath.value / webJarsPathLib.value, webJarsCache.value, webJarsClassLoader.value) {
        (e, to) =>
          e.extractAllWebJarsTo(to)
      }
      webJarsPath.value
    },
    webJars := extractWebJars.value.*** pair(relativeTo(extractWebJars.value), errorIfNone = false),

    mappings := {
      val files = (sources.value ++ resources.value) --- (sourceDirectories.value ++ resourceDirectories.value)
      files pair relativeTo(sourceDirectories.value ++ resourceDirectories.value) | flat
    },

    compile := Def.task(inc.Analysis.Empty).dependsOn(assets).value
  )

  val nodeModulesSettings = Seq(
    nodeModules := {
      withWebJarExtractor(nodeModulesPath.value, webJarsCache.value, webJarsClassLoader.value) {
        (e, to) =>
          e.extractAllNodeModulesTo(to)
      }
    }
  )

  private def syncMappings(cacheDir: File, mappings: Seq[PathMapping], target: File): File = {
    val cache = cacheDir / "sync-mappings"
    val copies = mappings map {
      case (file, path) => file -> (target / path)
    }
    Sync(cache)(copies)
    target
  }

  private def withWebJarExtractor(to: File, cacheFile: File, classLoader: ClassLoader)
                                 (block: (WebJarExtractor, File) => Unit): File = {
    val cache = new FileSystemCache(cacheFile)
    val extractor = new WebJarExtractor(cache, classLoader)
    block(extractor, to)
    cache.save()
    to
  }

  // Resource extract API

  /**
   * Copy a resource to a target folder.
   *
   * The resource won't be copied if the new file is older.
   *
   * @param to the target folder.
   * @param name the name of the resource.
   * @param classLoader the class loader to use.
   * @param cacheDir the dir to cache whether the file was read or not.
   * @return the copied file.
   */
  def copyResourceTo(to: File, name: String, classLoader: ClassLoader, cacheDir: File): File = {
    val url = classLoader.getResource(name)
    if (url == null) {
      throw new IllegalArgumentException("Couldn't find " + name)
    }
    val toFile = to / name

    incremental.runIncremental(cacheDir, Seq(url)) {
      urls =>
        (urls.map {
          u =>

          // Find out which file will actually be read
            val filesRead = if (u.getProtocol == "file") {
              Set(new File(u.toURI))
            } else if (u.getProtocol == "jar") {
              Set(new File(u.getFile.split('!')(0)))
            } else {
              Set.empty[File]
            }

            val is = u.openStream()
            try {
              toFile.getParentFile.mkdirs()
              IO.transfer(is, toFile)
              u -> OpSuccess(filesRead, Set(toFile))
            } finally {
              is.close()
            }
        }.toMap, toFile)
    }
  }

  // Actor system management and API

  private val webActorSystemAttrKey = AttributeKey[ActorSystem]("web-actor-system")

  private def load(state: State): State = {
    state.get(webActorSystemAttrKey).map(as => state).getOrElse {
      val webActorSystem = withActorClassloader(ActorSystem("sbt-web"))
      state.put(webActorSystemAttrKey, webActorSystem)
    }
  }

  private def unload(state: State): State = {
    state.get(webActorSystemAttrKey).map {
      as =>
        as.shutdown()
        state.remove(webActorSystemAttrKey)
    }.getOrElse(state)
  }

  /**
   * Perform actor related activity with sbt-web's actor system.
   * @param state The project build state available to the task invoking this.
   * @param namespace A means by which actors can be namespaced.
   * @param block The block of code to execute.
   * @tparam T The expected return type of the block.
   * @return The return value of the block.
   */
  def withActorRefFactory[T](state: State, namespace: String)(block: (ActorRefFactory) => T): T = {
    // We will get an exception if there is no known actor system - which is a good thing because
    // there absolutely has to be at this point.
    block(state.get(webActorSystemAttrKey).get)
  }

  private def withActorClassloader[A](f: => A): A = {
    val newLoader = ActorSystem.getClass.getClassLoader
    val thread = Thread.currentThread
    val oldLoader = thread.getContextClassLoader

    thread.setContextClassLoader(newLoader)
    try {
      f
    } finally {
      thread.setContextClassLoader(oldLoader)
    }
  }

}