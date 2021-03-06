package org.elm.workspace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.indexing.LightDirectoryIndex
import org.elm.workspace.ElmToolchain.Companion.ELM_INTELLIJ_JSON
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths


private val objectMapper = ObjectMapper()

/**
 * The logical representation of an Elm project. An Elm project can be an application
 * or a package, and it specifies its dependencies.
 *
 * @param manifestPath The location of the manifest file (e.g. `elm.json`). Uniquely identifies a project.
 * @param dependencies Additional Elm packages that this project depends on
 * @param testDependencies Additional Elm packages that this project's **tests** depends on
 * @param sourceDirectories The relative paths to one-or-more directories containing Elm source files belonging to this project.
 * @param testsRelativeDirPath The path to the directory containing unit tests, relative to the [projectDirPath].
 * Typically this will be "tests": see [testsDirPath] for more info.
 */
sealed class ElmProject(
        val manifestPath: Path,
        val dependencies: Dependencies,
        val testDependencies: Dependencies,
        val sourceDirectories: List<Path>,
        testsRelativeDirPath: String
) : UserDataHolderBase() {
    data class Dependencies(val direct: List<ElmPackageProject>, val indirect: List<ElmPackageProject>) {
        companion object {
            val EMPTY = Dependencies(emptyList(), emptyList())
        }
        val all get() = direct + indirect
    }

    /**
     * The path to the directory containing the Elm project JSON file.
     */
    val projectDirPath: Path = manifestPath.parent

    /**
     * The path to the directory containing unit tests, relative to the [projectDirPath]. Typically this will be "tests":
     * see [testsDirPath] for more info.
     *
     * Note that this path is normalized (see [Path.normalize]) so can safely be compared to [DEFAULT_TESTS_DIR_NAME].
     * For example, if the user specifies a value such as `"./foo/"` in some config file, this property will return `"foo"`.
     */
    val testsRelativeDirPath = Paths.get(testsRelativeDirPath).normalize().toString()

    /**
     * The path to the directory containing unit tests.
     *
     * For packages this will be a directory called "tests", as elm-test requires packages to have tests in a top-level
     * "tests" directory. For applications the default behaviour is the same as for packages, but optionally tests can
     * be put in some other directory, as long as when elm-test is called, the path to those tests is specified as a
     * cmd-line argument.
     */
    val testsDirPath: Path = projectDirPath.resolve(this.testsRelativeDirPath)

    /**
     * A flag indicating whether this project use a custom folder for unit tests (i.e. where the tests are any folder other
     * than the default `"tests"`).
     */
    val isCustomTestsDir = this.testsRelativeDirPath != DEFAULT_TESTS_DIR_NAME

    /**
     * A name which can be shown in the UI. Note that while Elm packages have user-assigned
     * names, applications do not. Thus, in order to cover both cases, we use the name
     * of the parent directory.
     */
    val presentableName: String =
            projectDirPath.fileName?.toString() ?: "UNKNOWN"

    /**
     * Returns the absolute paths for each source directory.
     *
     * @see sourceDirectories
     */
    val absoluteSourceDirectories: List<Path> =
            sourceDirectories.map { projectDirPath.resolve(it).normalize() }

    /**
     * Returns all the source directories, i.e. the [absoluteSourceDirectories] and the [testsDirPath].
     */
    val allSourceDirs: Sequence<Path> =
            absoluteSourceDirectories.asSequence() + sequenceOf(testsDirPath)

    /**
     * Returns all packages which this project depends on directly, whether it be for normal,
     * production code or for tests.
     */
    val allResolvedDependencies: Sequence<ElmPackageProject> =
            sequenceOf(dependencies.direct, testDependencies.direct).flatten()

    /**
     * Returns true if this project is compatible with Elm compiler [version].
     *
     * This is a looser form of a version check that allows for Elm compiler versions that include
     * alpha/beta/rc suffixes. e.g. "0.19.1-alpha-4"
     */
    fun isCompatibleWith(version: Version) =
            when (this) {
                is ElmApplicationProject -> elmVersion.xyz == version.xyz
                is ElmPackageProject -> elmVersion.contains(version.xyz)
            }

    /**
     * Return `true` iff this package is the core package for the current version of Elm.
     */
    open fun isCore(): Boolean = false

    companion object {

        fun parse(manifestPath: Path, repo: ElmPackageRepository, ignoreTestDeps: Boolean = false): ElmProject {
            val manifestStream = LocalFileSystem.getInstance().refreshAndFindFileByPath(manifestPath.toString())?.inputStream
                    ?: throw ProjectLoadException("Could not find file $manifestPath. Is the package installed?")
            val sidecarManifestStream = LocalFileSystem.getInstance().refreshAndFindFileByPath(
                    manifestPath.resolveSibling(ELM_INTELLIJ_JSON).toString())?.inputStream
            return parse(manifestStream, manifestPath, repo, ignoreTestDeps, sidecarManifestStream)
        }

        /**
         * Attempts to parse an `elm.json` file and, if it exists, the sibling `elm.intellij.json` file (see
         * [ElmToolchain.ELM_INTELLIJ_JSON]).
         *
         * @param sidecarManifestStream The stream to the `elm.intellij.json` file, if one exists. This is only used for
         * Elm 19+ projects. Currently it is only read for projects which are marked in `elm.json` as an _application_
         * (i.e. not for _packages_) as it is only applications which allow a custom test directory to be set (and that's
         * the only thing we currently store in `elm.intellij.json`). In future this maybe change if more data is added
         * into the sidecar manifest.
         * @throws ProjectLoadException if the JSON cannot be parsed
         */
        fun parse(manifestStream: InputStream,
                  manifestPath: Path,
                  repo: ElmPackageRepository,
                  ignoreTestDeps: Boolean = false,
                  sidecarManifestStream: InputStream? = null
        ): ElmProject {
            val node = try {
                objectMapper.readTree(manifestStream)
            } catch (e: JsonProcessingException) {
                throw ProjectLoadException("Bad JSON: ${e.message}")
            }

            val type = node.get("type")?.textValue()
            return when (type) {
                "application" -> {
                    val manifestDto = try {
                        objectMapper.treeToValue(node, ElmApplicationProjectDTO::class.java)
                    } catch (e: JsonProcessingException) {
                        throw ProjectLoadException("Invalid elm.json: ${e.message}")
                    }

                    // If specified, read the custom manfiest (elm.intellij.json)
                    val sidecarManifestDto = sidecarManifestStream?.let {
                        try {
                            objectMapper.readValue(it, ElmSidecarManifestDTO::class.java)
                        } catch (e: JsonProcessingException) {
                            throw ProjectLoadException("Invalid elm.intellij.json: ${e.message}")
                        }
                    }

                    ElmApplicationProject(
                            manifestPath = manifestPath,
                            elmVersion = manifestDto.elmVersion,
                            dependencies = manifestDto.dependencies.depsToPackages(repo),
                            testDependencies = if (ignoreTestDeps) Dependencies.EMPTY else manifestDto.testDependencies.depsToPackages(repo),
                            sourceDirectories = manifestDto.sourceDirectories,
                            testsRelativeDirPath = sidecarManifestDto?.testDirectory ?: DEFAULT_TESTS_DIR_NAME
                    )
                }
                "package" -> {
                    val dto = try {
                        objectMapper.treeToValue(node, ElmPackageProjectDTO::class.java)
                    } catch (e: JsonProcessingException) {
                        throw ProjectLoadException("Invalid elm.json: ${e.message}")
                    }
                    // TODO [kl] resolve dependency constraints to determine package version numbers
                    // [x] use whichever version number is available in the Elm package cache (~/.elm)
                    // [ ] include transitive dependencies
                    // [ ] resolve versions such that all constraints are satisfied
                    //     (necessary for correctness sake, but low priority)
                    ElmPackageProject(
                            manifestPath = manifestPath,
                            elmVersion = dto.elmVersion,
                            dependencies = dto.dependencies.constraintDepsToPackages(repo),
                            testDependencies = if (ignoreTestDeps) emptyList() else dto.testDependencies.constraintDepsToPackages(repo),
                            sourceDirectories = listOf(Paths.get("src")),
                            name = dto.name,
                            version = dto.version,
                            exposedModules = dto.exposedModulesNode.toExposedModuleMap())
                }
                else -> throw ProjectLoadException("The 'type' field is '$type', "
                        + "but expected either 'application' or 'package'")
            }
        }
    }
}


/**
 * Represents an Elm application
 */
class ElmApplicationProject(
        manifestPath: Path,
        val elmVersion: Version,
        dependencies: Dependencies,
        testDependencies: Dependencies,
        sourceDirectories: List<Path>,
        testsRelativeDirPath: String = DEFAULT_TESTS_DIR_NAME
) : ElmProject(manifestPath, dependencies, testDependencies, sourceDirectories, testsRelativeDirPath)


/**
 * Represents an Elm package/library
 */
class ElmPackageProject(
        manifestPath: Path,
        val elmVersion: Constraint,
        dependencies: List<ElmPackageProject>,
        testDependencies: List<ElmPackageProject>,
        sourceDirectories: List<Path>,
        val name: String,
        val version: Version,
        val exposedModules: List<String>
) : ElmProject(
        manifestPath = manifestPath,
        dependencies = Dependencies(dependencies, emptyList()),
        testDependencies = Dependencies(testDependencies, emptyList()),
        sourceDirectories = sourceDirectories,
        testsRelativeDirPath = DEFAULT_TESTS_DIR_NAME
) {
    override fun isCore(): Boolean =
            name == "elm/core"
}


private fun ExactDependenciesDTO.depsToPackages(repo: ElmPackageRepository) =
        ElmProject.Dependencies(direct.depsToPackages(repo), indirect.depsToPackages(repo))


private fun Map<String, Version>.depsToPackages(repo: ElmPackageRepository) =
        map { (name, version) ->
            loadDependency(repo, name, version)
        }

private fun Map<String, Constraint>.constraintDepsToPackages(repo: ElmPackageRepository) =
        map { (name, constraint) ->
            val version = repo.availableVersionsForPackage(name)
                    .filter { constraint.contains(it) }
                    .min()
                    ?: throw ProjectLoadException("Could not load $name ($constraint). Is it installed?")

            loadDependency(repo, name, version)
        }

private fun loadDependency(repo: ElmPackageRepository, name: String, version: Version): ElmPackageProject {
    val manifestPath = repo.findPackageManifest(name, version)
            ?: throw ProjectLoadException("Could not load $name ($version): manifest not found")
    // TODO [kl] guard against circular dependencies
    // NOTE: we ignore the test dependencies of our dependencies because it is highly unlikely
    // that they have been installed by Elm in the local package cache (the user would have
    // to actually run the package's tests from within the package cache, which no one is going to do).
    val elmProject = ElmProject.parse(manifestPath, repo, ignoreTestDeps = true) as? ElmPackageProject
            ?: throw ProjectLoadException("Could not load $name ($version): expected a package!")

    return elmProject
}

/**
 * A dummy sentinel value because [LightDirectoryIndex] needs it.
 */
val noProjectSentinel = ElmApplicationProject(
        manifestPath = Paths.get("/elm.json"),
        elmVersion = Version(0, 0, 0),
        dependencies = ElmProject.Dependencies.EMPTY,
        testDependencies = ElmProject.Dependencies.EMPTY,
        sourceDirectories = emptyList()
)


// JSON Decoding


@JsonIgnoreProperties(ignoreUnknown = true)
private interface ElmProjectDTO


private class ElmApplicationProjectDTO(
        @JsonProperty("elm-version") val elmVersion: Version,
        @JsonProperty("source-directories") val sourceDirectories: List<Path>,
        @JsonProperty("dependencies") val dependencies: ExactDependenciesDTO,
        @JsonProperty("test-dependencies") val testDependencies: ExactDependenciesDTO
) : ElmProjectDTO


/**
 * DTO used to wrap the data in `elm.intellij.json`.
 *
 * @see [ElmToolchain.ELM_INTELLIJ_JSON]
 */
private class ElmSidecarManifestDTO(
        /**
         * The path to the directory containing the unit tests, relative to the root of the Elm project.
         */
        @JsonProperty("test-directory") val testDirectory: String
)


private class ExactDependenciesDTO(
        @JsonProperty("direct") val direct: Map<String, Version>,
        @JsonProperty("indirect") val indirect: Map<String, Version>
)


private class ElmPackageProjectDTO(
        @JsonProperty("elm-version") val elmVersion: Constraint,
        @JsonProperty("dependencies") val dependencies: Map<String, Constraint>,
        @JsonProperty("test-dependencies") val testDependencies: Map<String, Constraint>,
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: Version,
        @JsonProperty("exposed-modules") val exposedModulesNode: JsonNode
) : ElmProjectDTO


private fun JsonNode.toExposedModuleMap(): List<String> {
    // Normalize the 2 exposed-modules formats into a single format.
    // format 1: a list of strings, where each string is the name of an exposed module
    // format 2: a map where the keys are categories and the values are the names of the modules
    //           exposed in that category. We discard the categories because they are not useful.
    return when (this.nodeType) {
        JsonNodeType.ARRAY -> {
            this.elements().asSequence().map { it.textValue() }.toList()
        }
        JsonNodeType.OBJECT -> {
            this.fields().asSequence().flatMap { (_, nameNodes) ->
                nameNodes.asSequence().map { it.textValue() }
            }.toList()
        }
        else -> {
            throw RuntimeException("exposed-modules JSON must be either an array or an object")
        }
    }
}

/**
 * The default name of the directory which contains unit tests.
 */
const val DEFAULT_TESTS_DIR_NAME = "tests"
