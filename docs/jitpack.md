# Publishing & Using `frost-api` on JitPack

Frostfuscator's public extension API (`frost-api`) is configured for seamless deployment and dependency resolution via **[JitPack](https://jitpack.io)**.

---

## 🛠️ Repository Configuration (`jitpack.yml`)

The root directory includes a `jitpack.yml` file configuring JitPack to build using Java 21:

```yaml
jdk:
  - openjdk21

before_install:
  - chmod +x gradlew

install:
  - ./gradlew :frost-api:publishToMavenLocal -Dorg.gradle.daemon=false
```

---

## 📦 How to Include `frost-api` in Your Plugin Project

Replace `TAG_OR_COMMIT` with a released Git tag (e.g. `1.3.0`), branch name (e.g. `main-SNAPSHOT`), or specific commit hash.

### Gradle (Groovy DSL) — `build.gradle`

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    // Frostfuscator Plugin API
    compileOnly 'com.github.Fwrostt.Frostfuscator:frost-api:1.3.0'
}
```

### Gradle (Kotlin DSL) — `build.gradle.kts`

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.Fwrostt.Frostfuscator:frost-api:1.3.0")
}
```

### Maven — `pom.xml`

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.Fwrostt.Frostfuscator</groupId>
        <artifactId>frost-api</artifactId>
        <version>1.3.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

---

## 🚀 How to Publish a Release on JitPack

1. Push your latest code to your GitHub repository (`https://github.com/Fwrostt/Frostfuscator`).
2. Create a release tag on GitHub:
   ```bash
   git tag -a v1.3.0 -m "Release v1.3.0 with frost-api support"
   git push origin v1.3.0
   ```
3. Visit `https://jitpack.io/#Fwrostt/Frostfuscator` and click **Get it** to trigger the automated JitPack build.
