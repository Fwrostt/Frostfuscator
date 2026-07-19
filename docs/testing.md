# Testing

Frostfuscator uses three test layers:

- Unit-style transformer tests in `frost-core/src/test/java/dev/frost/obfuscator/transformer`.
- A generated multi-class fixture source tree in `frost-core/src/test/fixtures/multiclass`.
- Full pass integration tests in `frost-core/src/test/java/dev/frost/obfuscator/integration`.

## Fixture JAR

Build the decompiler/manual-analysis input JAR with:

```powershell
.\gradlew.bat testInputJar
```

The JAR is written to:

```text
frost-core/build/test-jars/frostfuscator-test-input.jar
```

The fixture contains a manifest main class, a plugin descriptor, resources, a larger dummy database resource, annotations, lambdas, interface dispatch, branches, switches, exception paths, reflection-sensitive members, native declarations, and explicit stack-trace printing. It is meant to exercise every pass family with one realistic input artifact.

## Full Pass Matrix

Run the complete integration matrix with:

```powershell
.\gradlew.bat test --tests dev.frost.obfuscator.integration.ComprehensivePassIntegrationTest
```

`ComprehensivePassIntegrationTest` runs every registered transformer against a fresh fixture JAR and also checks that the coverage list exactly matches `TransformerRegistry.getAllNames()`. Adding a new transformer without adding an integration case fails the suite.

## Decompiler Tools

Keep bulky external GUI decompilers outside the repository under:

```text
tools/decompilers/
```

Suggested local layout:

```text
tools/decompilers/jd-gui/
tools/decompilers/bytecode-viewer/
```

Those tools should inspect `frost-core/build/test-jars/frostfuscator-test-input.jar` and any protected outputs produced during manual runs. Do not commit downloaded decompiler binaries or generated output JARs.
