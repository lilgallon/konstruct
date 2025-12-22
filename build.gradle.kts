import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.gradle.nexus.publish)
    `maven-publish`
    signing
}

group = "dev.gallon"
version = System.getenv("VERSION")?.substringAfter("R-") ?: "1.0.0-rc1"

repositories {
    mavenCentral()
}

dependencies {
    // Runtime dependencies for users of the lib
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    // Processor dependencies
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.compile.testing.ksp)
    kspTest(project(":"))
}

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                artifactId = "konstruct"
                name.set("konstruct")
                description.set("KSP based serialization and deserialization generator for kotlinx.serialization")
                url.set("https://github.com/lilgallon/konstruct")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("http://www.opensource.org/licenses/mit-license.php")
                    }
                }

                developers {
                    developer {
                        id.set("lilgallon")
                        name.set("Lilian Gallon")
                        email.set("lilian.gallon@gmail.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/lilgallon/konstruct.git")
                    developerConnection.set("scm:git:ssh://github.com/lilgallon/konstruct.git")
                    url.set("https://github.com/lilgallon/konstruct")
                }
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}

signing {
    val gpgKey = System.getenv("GPG_PRIVATE_KEY")
    val gpgPassphrase = System.getenv("GPG_PASSPHRASE")
    if (gpgKey != null && gpgPassphrase != null) {
        val decodedKey = try {
            Base64.getDecoder().decode(gpgKey.trim()).decodeToString()
        } catch (e: Exception) {
            gpgKey
        }
        useInMemoryPgpKeys(decodedKey, gpgPassphrase)
        sign(publishing.publications["maven"])
    }
    isRequired = gpgKey != null && gpgPassphrase != null
}
