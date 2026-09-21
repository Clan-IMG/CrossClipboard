plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Compiled against the plain WorldEdit API on purpose: FAWE implements the same API, so one jar runs on both.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.18")
    implementation("redis.clients:jedis:7.5.3")

    // Only for YamlConfiguration in the config and language-file tests.
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        // Gradle would otherwise write plugin.yml in the platform charset, which Paper rejects if it is not UTF-8.
        filteringCharset = "UTF-8"
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
        // Without this the build cache replays an earlier run in which the Redis integration tests were skipped.
        inputs.property("redisIntegrationTarget",
            providers.environmentVariable("CROSSCLIPBOARD_TEST_REDIS").orElse(""))
    }

    shadowJar {
        archiveClassifier.set("")
        val libs = "net.clanimg.crossClipboard.libs"
        relocate("redis.clients", "$libs.redis")
        relocate("org.apache.commons.pool2", "$libs.pool2")
        relocate("com.google.gson", "$libs.gson")
        relocate("org.json", "$libs.json")
        // Paper ships slf4j; bundling a second copy would clash with the server's.
        dependencies {
            exclude(dependency("org.slf4j:.*"))
        }
    }

    build {
        dependsOn(shadowJar)
    }
}
