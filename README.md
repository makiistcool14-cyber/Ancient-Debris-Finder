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
