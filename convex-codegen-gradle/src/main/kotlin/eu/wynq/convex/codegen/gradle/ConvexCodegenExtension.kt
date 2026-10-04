/*
 * Copyright 2026 convex-kt contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.wynq.convex.codegen.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the `eu.wynq.convex.codegen` plugin.
 *
 * By default the plugin captures the backend's `apiSpec` itself, by running the
 * Convex CLI in [convexProjectDirectory], then generates typed descriptors from
 * it on every build. Set [spec] to a JSON file to skip the CLI and read a
 * committed or otherwise prepared spec instead.
 *
 * Only [packageName] has to be set; every other property has a default that
 * matches a conventional repository layout.
 */
public interface ConvexCodegenExtension {
    /**
     * A manual `apiSpec` JSON file.
     *
     * When set, the plugin reads this file and never invokes the Convex CLI, so
     * a build can run offline or against a spec that is committed and reviewed.
     * When unset (the default), the plugin runs [functionSpecCommand] instead.
     */
    public val spec: RegularFileProperty

    /** The Kotlin package the generated file declares. Required. */
    public val packageName: Property<String>

    /**
     * The name of the generated top-level object that holds the modules.
     *
     * Defaults to `Api`, so a call site reads `Api.Messages.list`.
     */
    public val objectName: Property<String>

    /**
     * The Kotlin source set the generated file is added to.
     *
     * Defaults to `commonMain` in a Kotlin Multiplatform build and `main` in a
     * Kotlin JVM build.
     */
    public val sourceSet: Property<String>

    /** The directory the generated Kotlin is written to. Defaults to `build/generated/convex`. */
    public val outputDirectory: DirectoryProperty

    /**
     * The directory the Convex CLI runs in, which must contain the project's
     * `convex/` functions folder and its configuration.
     *
     * Defaults to the root project directory, matching the usual layout of a
     * Gradle build next to a top-level `convex/` folder.
     */
    public val convexProjectDirectory: DirectoryProperty

    /**
     * The command that writes the `apiSpec` JSON to stdout.
     *
     * Defaults to `npx convex function-spec`, run in [convexProjectDirectory].
     */
    public val functionSpecCommand: ListProperty<String>

    /**
     * An optional command run before [functionSpecCommand], for example to push
     * the backend first: `listOf("npx", "convex", "dev", "--once")`.
     *
     * Unset by default. Set it to keep a deployment and the generated Kotlin in
     * step within a single Gradle build; leave it unset when a separate
     * `npx convex dev` watcher owns the deployment.
     */
    public val prepareCommand: ListProperty<String>
}
