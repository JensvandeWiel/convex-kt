import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project

/**
 * Configures Maven Central publication for a convex-kt module.
 *
 * Kept in one place so the multiplatform library convention and the JVM
 * build-tool convention cannot drift: the POM metadata, the Central target, and
 * the signing policy are identical for every published module. Signing is
 * enabled only when a key is configured, so `publishToMavenLocal` runs need no
 * GPG credentials; CI supplies the key through the `ORG_GRADLE_PROJECT_*`
 * secrets in `.github/workflows/publish.yml`.
 *
 * @param project the module being configured, used to read the signing key.
 * @param artifactName the POM `name`, usually the Gradle project name.
 * @param artifactDescription the POM `description`.
 */
internal fun MavenPublishBaseExtension.configureConvexPublishing(
    project: Project,
    artifactName: String,
    artifactDescription: String,
) {
    publishToMavenCentral(automaticRelease = true)
    val signingKeyPresent: Boolean =
        project.findProperty("signingInMemoryKey") != null ||
            System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    if (signingKeyPresent) {
        signAllPublications()
    }
    pom {
        name.set(artifactName)
        description.set(artifactDescription)
        inceptionYear.set("2026")
        url.set("https://github.com/JensvandeWiel/convex-kt/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("JensvandeWiel")
                name.set("Jens van de Wiel")
                url.set("https://github.com/JensvandeWiel/")
            }
        }
        scm {
            url.set("https://github.com/JensvandeWiel/convex-kt/")
            connection.set("scm:git:https://github.com/JensvandeWiel/convex-kt.git")
            developerConnection.set("scm:git:ssh://git@github.com/JensvandeWiel/convex-kt.git")
        }
    }
}
