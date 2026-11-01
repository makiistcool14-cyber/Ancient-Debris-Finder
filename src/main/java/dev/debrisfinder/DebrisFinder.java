org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
org.gradle.configuration-cache=false

# Versions taken from public Fabric 1.21.11 projects; if one is not found,
# copy the current numbers from https://fabricmc.net/develop
minecraft_version=1.21.11
loader_version=0.18.4
loom_version=1.14-SNAPSHOT
fabric_version=0.141.3+1.21.11

mod_version=1.0.0
maven_group=dev.debrisfinder
archives_base_name=debris-finder

pluginManagement {
    repositories {
        maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = 'debris-finder'

# Ancient Debris Finder (Fabric 1.21.11 addon for your Baritone JAR)

Separate mod. Your Baritone JAR stays untouched in `mods/` and keeps working as before.

## Build
1. Install JDK 21 and Gradle 9.2+ (or copy `gradlew`, `gradlew.bat` and `gradle/wrapper` from the Fabric example mod).
2. In this folder: `gradle build`
3. Take `build/libs/debris-finder-1.0.0.jar` (NOT the -sources jar) and put it in `mods/`
   next to Fabric API and your `baritone-meteor-1_21_11` jar.

If Gradle says a version does not exist, copy current numbers from https://fabricmc.net/develop into gradle.properties.

## Use (in the Nether)
- `/debris gui`   opens the window (Start / Stop, seed, targets, status)
- `/debris start` / `/debris stop` / `/debris list`

## Behaviour
- Seed: read from the integrated server (singleplayer / LAN host). On other servers it shows "unavailable".
- Predicted: seed-derived Ancient Debris vein centers (real worldgen code, blocks lie within a few blocks, and
  air-exposed ones are discarded by the game), shown only for chunks your client has not loaded.
- Confirmed: real Ancient Debris blocks in loaded chunks.
- Mining: Baritone `mine ancient_debris`, started only while confirmed debris is loaded. Stops on lava (near you
  or next to the target), fire, damage taken, low health, falling, nearby hostile mobs.
  Requires a diamond or netherite pickaxe in the inventory.

plugins {
    id 'fabric-loom' version "${loom_version}"
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group
base { archivesName = project.archives_base_name }

repositories {
    maven { url = 'https://maven.fabricmc.net/' }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    // Your existing Baritone JAR (Fabric, 1.21.11). Loom remaps it for compilation.
    // It is NOT bundled into the output; it stays a separate mod in your mods folder.
    modImplementation files("libs/baritone-meteor-1_21_11.jar")
}

processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") { expand "version": project.version }
}

tasks.withType(JavaCompile).configureEach { options.release = 21 }

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
