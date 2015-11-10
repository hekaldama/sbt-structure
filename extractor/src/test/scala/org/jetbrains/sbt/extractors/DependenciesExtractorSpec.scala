package org.jetbrains.sbt
package extractors

import org.jetbrains.sbt.Utilities._
import org.jetbrains.sbt.structure._
import org.jetbrains.sbt.{structure => jb}
import org.specs2.mutable._
import sbt._

class DependenciesExtractorSpec extends Specification {

  "DependenciesExtractor" should {
    "always extract build dependencies" in {
      val actual = new DependenciesExtractor(
        stubProject1, Some(toBuildDependencies(projectDependencies)), emptyClasspath, emptyClasspath, Nil, Nil
      ).extract
      val expected = DependencyData(toProjectDependencyData(projectDependencies), Nil, Nil)
      actual must beEqualTo(expected)
    }

    "always extract unmanaged dependencies" in {
      val actual = new DependenciesExtractor(
        stubProject1, None, toUnmanagedClasspath(unmanagedDependencies), emptyClasspath, Seq(sbt.Compile, sbt.Test), Seq(sbt.Test)
      ).extract
      val expected = DependencyData(Nil, Nil, toJarDependencyData(unmanagedDependencies))
      actual must beEqualTo(expected)
    }

    "extract managed dependencies when supplied" in {
      val actual = new DependenciesExtractor(
        stubProject1, None, emptyClasspath, toExternalDepenedncyClasspath(moduleDependencies), Seq(sbt.Compile, sbt.Test), Seq(sbt.Test)
      ).extract
      val expected = DependencyData(Nil, toModuleDependencyData(moduleDependencies), Nil)
      actual.modules must containTheSameElementsAs(expected.modules) // TODO: investigate why actual data have different order from time to time
    }

    "merge configurations in unmanaged and managed dependencies when necessary" in {
      val actual = new DependenciesExtractor(
        stubProject1, None,
        toUnmanagedClasspath(unmanagedDependenciesWithCustomConf),
        toExternalDepenedncyClasspath(moduleDependenciesWithCustomConf),
        Seq(sbt.Test, sbt.Compile, CustomConf), Seq(sbt.Test, CustomConf)
      ).extract
      val expected = DependencyData(Nil, toModuleDependencyData(moduleDependenciesWithCustomConf),
        toJarDependencyData(unmanagedDependenciesWithCustomConf))
      actual.jars must containTheSameElementsAs(expected.jars)
      actual.modules must containTheSameElementsAs(expected.modules)
    }

    "correctly extract managed dependencies with classifiers" in {
      val moduleId = ModuleID("com.example", "foo", "SNAPSHOT")
      val externalDependencyClasspath = Map(
        sbt.Compile -> Seq(
          Attributed(file("foo.jar"))(AttributeMap.empty.put(sbt.Keys.moduleID.key, moduleId).put(sbt.Keys.artifact.key, Artifact("foo"))),
          Attributed(file("foo-tests.jar"))(AttributeMap.empty.put(sbt.Keys.moduleID.key, moduleId).put(sbt.Keys.artifact.key, Artifact("foo", "tests")))
        )
      )
      val actual = new DependenciesExtractor(stubProject1, None, emptyClasspath, externalDependencyClasspath.apply, Seq(sbt.Compile), Seq.empty).extract
      val expectedModules = Seq(
        ModuleDependencyData(ModuleIdentifier(moduleId.organization, moduleId.name, moduleId.revision, Artifact.DefaultType, ""), Seq(jb.Configuration.Compile)),
        ModuleDependencyData(ModuleIdentifier(moduleId.organization, moduleId.name, moduleId.revision, Artifact.DefaultType, "tests"), Seq(jb.Configuration.Compile))
      )
      actual.modules must containTheSameElementsAs(expectedModules)
    }
  }

  val stubProject1 = ProjectRef(file("/tmp/test-project"), "project-1")
  val stubProject2 = ProjectRef(file("/tmp/test-project"), "project-2")
  val emptyClasspath: sbt.Configuration => Keys.Classpath = _ => Nil

  val CustomConf = config("custom-conf").extend(sbt.Test)

  val projectDependencies = Seq(stubProject2 -> sbt.Compile)

  val unmanagedDependencies = Seq(
    existingFile("foo.jar") -> sbt.Compile,
    existingFile("bar.jar") -> sbt.Test
  )

  val moduleDependencies = Seq(
    ModuleIdentifier("com.example", "foo", "SNAPSHOT", Artifact.DefaultType, "") -> sbt.Compile,
    ModuleIdentifier("com.example", "bar", "SNAPSHOT", Artifact.DefaultType, "") -> sbt.Test
  )

  val unmanagedDependenciesWithCustomConf =
    unmanagedDependencies :+ (existingFile("baz.jar") -> CustomConf)

  val moduleDependenciesWithCustomConf =
    moduleDependencies :+ (ModuleIdentifier("com.example", "baz", "SNAPSHOT", Artifact.DefaultType, "") -> CustomConf)

  private def existingFile(path: String): File = new File(path) {
    override def isFile: Boolean = true
  }

  private def toBuildDependencies(deps: Seq[(ProjectRef, sbt.Configuration)]): BuildDependencies = {
    val asClasspthDep = deps.map { case (ref, conf) => ResolvedClasspathDependency(ref, Some(conf.name)) }
    BuildDependencies(Map(stubProject1 -> asClasspthDep), Map.empty)
  }

  private def toUnmanagedClasspath(deps: Seq[(File, sbt.Configuration)])(conf: sbt.Configuration): Keys.Classpath =
    deps.filter(_._2 == conf).map(_._1).map(Attributed(_)(AttributeMap.empty))

  private def toExternalDepenedncyClasspath(deps: Seq[(ModuleIdentifier, sbt.Configuration)])(conf: sbt.Configuration): Keys.Classpath = {
    val modules = deps.filter(_._2 == conf).map(_._1)
    val moduleIds = modules.map(id => ModuleID(id.organization, id.name, id.revision))
    val artifacts = modules.map(id => Artifact(id.name, id.classifier))
    moduleIds.zip(artifacts).map { case (id, artifact) =>
      Attributed(file("test.jar"))(AttributeMap.empty.put(sbt.Keys.moduleID.key, id).put(sbt.Keys.artifact.key, artifact))
    }
  }

  private def toProjectDependencyData(deps: Seq[(ProjectRef, sbt.Configuration)]): Seq[ProjectDependencyData] =
    deps.map { case (ref, conf) => ProjectDependencyData(ref.id, jb.Configuration.fromString(conf.name)) }

  private def toJarDependencyData(deps: Seq[(File, sbt.Configuration)]): Seq[JarDependencyData] =
    deps.map { case (file, conf) => JarDependencyData(file, fixTestConfigurations(jb.Configuration.fromString(conf.name))) }

  private def toModuleDependencyData(deps: Seq[(ModuleIdentifier, sbt.Configuration)]): Seq[ModuleDependencyData] =
    deps.map { case (id, conf) => ModuleDependencyData(id, fixTestConfigurations(jb.Configuration.fromString(conf.name))) }

  private def fixTestConfigurations(confs: Seq[jb.Configuration]): Seq[jb.Configuration] =
    confs.map(c => if (c.name == CustomConf.name) jb.Configuration.Test else c)
}