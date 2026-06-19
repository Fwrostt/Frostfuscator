# Transformers Reference

Transformers are the heart of Frostfuscator. Each one performs a unique mutation on your bytecode.

## 🧹 Cleanup Transformers

These transformers prepare the bytecode by removing metadata that decompilers rely heavily upon.

*   **`RemoveDebugTransformer`**: Strips out LocalVariableTables, LineNumberTables, and source file attributes. This makes decompiled variables show as `var1`, `var2` instead of their original names, and removes line numbers from stack traces.
*   **`AccessModifierTransformer`**: Modifies the visibility of classes, fields, and methods (e.g., changing private to public). This breaks the standard encapsulation visible in decompilers and confuses structure analysis.
*   **`MetadataNoiseTransformer`**: Injects fake or malformed annotations and attributes into `.class` files, causing many automated decompilers (like CFR or Procyon) to crash or throw parsing exceptions.

## 🔒 Encryption Transformers

*   **`StringEncryptionTransformer`**: Finds all string literals, encrypts them using an algorithmic cipher, and inserts a decryption method into the class. At runtime, the string is decrypted on the fly.
*   **`NumberObfuscationTransformer`**: Replaces simple numeric constants (e.g., `int x = 5;`) with bitwise math, XOR operations, or calculated expressions that evaluate to `5` at runtime.
*   **`ParameterEncryptionTransformer`**: Encrypts method signatures and parameters to hide data types from static analysis.

## 🔀 Control Flow Obfuscation

*   **`FlowObfuscationTransformer`**: Our primary control-flow flattener. It takes standard linear instructions, divides them into blocks, and places them inside a giant `switch` statement wrapped in a `while` loop. The execution order is determined dynamically by a state variable.
*   **`FlowExceptionTransformer`**: Replaces standard branching (`if/else`, `goto`) with `try-catch` blocks and synthetic Exceptions, completely confusing decompiler control-flow graphs.
*   **`FlowOutlinerTransformer`**: Extracts random sections of code from large methods into static helper methods, fracturing the logic across multiple locations.
*   **`FlowConditionTransformer` & `FlowRangeTransformer` & `FlowSwitchTransformer`**: Mutates boolean logic, injects opaque predicates, and converts `if` statements into `switch` statements (and vice-versa).
*   **`StackManipulationTransformer`**: Pushes dummy variables onto the JVM operand stack and pops them off, generating junk bytecode that executes harmlessly but breaks stack-reconstruction in decompilers.

## 🪞 Indirection

*   **`InvokeDynamicTransformer`**: Replaces standard `invokestatic` and `invokevirtual` calls with Java 7's `invokedynamic` instruction, deferring method resolution to a hidden bootstrap method at runtime.
*   **`ReferenceHidingTransformer`**: Replaces direct field access (`getfield`/`putfield`) with dynamically generated getter/setter proxies or Java Reflection.

## 🏷️ Renaming Transformers

These transformers systematically rename the components of your code using your selected `dictionary`.

*   **`ClassRenameTransformer`**: Renames class names.
*   **`FieldRenameTransformer`**: Renames field names.
*   **`MethodRenameTransformer`**: Renames method names.
*   **`LocalVariableRenameTransformer`**: Renames local variables within method bodies.

> [!WARNING]
> Renaming methods and fields dynamically accessed via Reflection or JNI may break your application. Ensure you configure your `exclusions` properly for API endpoints or reflective targets.
