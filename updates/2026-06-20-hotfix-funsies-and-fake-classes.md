# 2026-06-20 Hotfix: Fake Classes, Bloat Reduction, and Funsies

## Summary

- Fixed fake classes so they no longer land in one predictable package by default.
- Added configurable fake-class placement and naming modes.
- Reduced Strong preset bloat by moving anti-debug guard logic into a shared helper class and skipping heavy flow on tiny methods.
- Added the new Funsies category with `inject-banner` and `chinese-mode`.
- Improved randomness defaults: seed `0` now means fresh randomness per run for affected passes.
- Added explicit transformer priorities so fake generators run before normal obfuscation and Chinese/Funsies display passes run last.
- Added `emoji-hell`, `copypasta-injector`, and `fake-application`.
- Added Gradle smoke tasks: `createSmokeJar` and `runSmokeJar`.
- Fixed Chinese Mode string literals to be Unicode-safe and added large banners, quotes, metadata, and final-stage injection controls.
- Upgraded fake classes with interfaces, enum-like classes, inner-class metadata, static blocks, annotations, constants, arrays, switch/try-catch/loop/synchronization bytecode.
- Reworked fake-code generation so no-pass output is plain functional bytecode, not pre-obfuscated bytecode: generated classes and methods no longer get synthetic/deprecated fingerprints from the generator itself.
- Added larger realistic method/field pools for generic fake classes, with cache/session/config-style names and varied method signatures instead of one repeated junk method body.
- Updated fake application profiles so generated classes use domain-specific method and field names, such as player/economy methods for Minecraft-style classes and controller/repository methods for Spring-style classes.
- Added `chinese-mode.package-mode` with `random`, `global`, `existing`, and `none`; the default now uses random Chinese package paths instead of a fixed package.
- Changed GUI choice controls to dropdowns and converted fake-class priority into a dropdown choice.

## Verification

- `.\gradlew.bat compileJava --no-daemon`
- `.\gradlew.bat build --no-daemon`
- `.\gradlew.bat createSmokeJar runSmokeJar --no-daemon`
- Smoke-tested fake classes with `placement: "existing"` and confirmed decoys land in the app package, not `frost/junk`.
- Smoke-tested shared anti-debug helper and confirmed the protected smoke app still runs.
- Smoke-tested `inject-banner`, `emoji-hell`, `copypasta-injector`, `fake-application`, and `chinese-mode`.
- Confirmed `java -jar build/hotfix-smoke/chinese-output.jar` runs and that both `Main-Class` and `plugin.yml` point at the remapped Chinese main class.
- Smoke-tested fake-only generation with no normal obfuscation passes enabled and confirmed generated classes have zero mappings, no `ACC_SYNTHETIC` markers, and realistic members such as `loadPlayerData`, `calculateEconomyBalance`, `loadCache`, and `resolveContext`.
- Verified Chinese Mode random package output no longer depends on the fixed `冰霜/混淆器` package prefix.
