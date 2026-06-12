# ELite Java Interoperability Guide

## 1. Overview

ELite runs on the JVM and provides seamless two-way interoperability with Java:

- **Call Java methods** from ELite code
- **Access Java fields and properties**
- **Create Java objects** with `new` or factory methods
- **Import Java packages** for unqualified access
- **Implement Java interfaces** from ELite classes
- **Extend Java classes** with `@Expando` extension methods

ELite resolves Java interop through an `ELResolver` chain that is searched in priority order.

## 2. Calling Java Methods

### Static Methods

```elite
Math.max(10, 20)               // 20
Math.sqrt(2.0)                 // 1.4142135623730951
System.currentTimeMillis()     // current time in ms
Integer.parseInt("42")          // 42
```

### Instance Methods

```elite
define list = java.util.ArrayList()
list.add("hello")
list.add("world")
list.size()                     // 2
list.get(0)                     // "hello"

"hello".toUpperCase()           // "HELLO"
"hello".length()                // 5
```

### Method Overload Resolution

When multiple overloads of a method exist, ELite selects the best match based on argument types at runtime:

```elite
Math.abs(-5)        // calls abs(int) → 5
Math.abs(-5.5)      // calls abs(double) → 5.5
Math.abs(-5L)       // calls abs(long) → 5
```

### Multi-Methods

ELite supports multi-methods — the same function name can dispatch to different Java methods based on argument types. This is handled automatically by `MethodResolver`.

## 3. Properties and Fields

### JavaBean Properties

ELite uses standard JavaBean conventions to resolve properties:

```elite
define p = Person("Alice", 30)
p.name              // calls getName() → "Alice"
p.age               // calls getAge() → 30
p.name = "Bob"      // calls setName("Bob")
```

Resolution priority for **get** operation:
1. JavaBean getter (`getXxx()` or `isXxx()` for boolean)
2. Public field (`obj.xxx`)
3. ELResolver chain (map/collection/array access)

Resolution priority for **set** operation:
1. JavaBean setter (`setXxx(type)`)
2. Public field (`obj.xxx = value`)
3. ELResolver chain

### Public Fields

Direct field access is supported for known Java types:

```elite
define p = java.awt.Point(10, 20)
p.x                 // 10 (direct field access, no getter)
p.y = 30            // sets field directly
```

### Map and List Access

ELite transparently supports dot-notation access for Maps and bracket-notation for both Maps and Lists:

```elite
define m = {name: "Alice", age: 30}
m.name              // "Alice" (map key access)
m["name"]           // "Alice" (same)

define list = [10, 20, 30]
list[0]             // 10
list[1]             // 20
```

## 4. Creating Java Objects

```elite
// Constructor call
define list = java.util.ArrayList()
define map = java.util.HashMap()
define point = java.awt.Point(10, 20)

// Factory method
define now = java.time.LocalDateTime.now()

// Import for convenience
import java.util.*
define set = HashSet()
define deque = ArrayDeque()
```

## 5. Import and Packages

### Package Import

```elite
import java.util.*
import java.io.*
import java.time.*

// Now use unqualified names
define list = ArrayList()
define file = File("example.txt")
define now = LocalDateTime.now()
```

### Single-Class Import

```elite
import java.util.regex.Pattern
define p = Pattern.compile("[a-z]+")
```

### Module Import (require)

```elite
require java.sql
define conn = DriverManager.getConnection("jdbc:...")
```

## 6. Type Coercion

ELite automatically converts between ELite types and Java types:

| ELite type | Java type | Notes |
|-----------|-----------|-------|
| Integer literal (`42`) | `java.lang.Long` | Default integer type |
| Float literal (`3.14`) | `java.lang.Double` | Default float type |
| String (`"hello"`) | `java.lang.String` | Direct mapping |
| Boolean (`true`) | `java.lang.Boolean` | Auto-boxed |
| List (`[1,2,3]`) | `java.util.List` | Immutable list |
| Map (`{a:1}`) | `java.util.LinkedHashMap` | Mutable map |
| Null (`null`) | `null` | Works for any reference type |

When a Java method expects a specific numeric type (`int`, `long`, `double`), ELite performs automatic coercion via Java's `Number` conversion methods.

## 7. Exception Handling

Java exceptions propagate to ELite as standard exceptions. You can catch them with `try`/`catch`:

```elite
try {
    define result = riskyJavaMethod()
} catch (java.io.IOException e) {
    System.err.println("IO error: " ~ e.getMessage())
} catch (Exception e) {
    System.err.println("Error: " ~ e.getMessage())
}
```

## 8. @Expando Extension Methods

The `@Expando` annotation allows adding methods to existing Java classes at runtime (similar to C# extension methods or Kotlin extension functions):

```java
// In a Java class registered as an expando provider:
@Expando
public static String shout(String receiver) {
    return receiver.toUpperCase() + "!";
}
```

```elite
"hello".shout()    // "HELLO!"
```

Expando methods are resolved through the `MethodResolver` and cached for performance.

## 9. Implementing Java Interfaces

ELite classes can implement Java interfaces:

```elite
class MyRunnable : java.lang.Runnable {
    define MyRunnable() => super()
    define run() => System.out.println("Hello from ELite!")
}

define runner = MyRunnable()
define thread = java.lang.Thread(runner)
thread.start()
```

## 10. Performance Considerations

### Optimization Level Impact

| Level | Java Interop Mechanism |
|:--:|------|
| 0-2 | Reflective `Method.invoke()` via ELResolver chain |
| 3 | Direct `invokevirtual`/`invokeinterface` bytecode with CHECKCAST |

At `-O3`, the bytecode compiler emits direct JVM method calls for known Java types instead of reflection. This provides performance comparable to compiled Java code.

### Method Resolution Caching

Java method lookups are cached per class via `SimpleCache` in `MethodResolver`. Repeated calls to the same method on the same type avoid re-resolution overhead.

### Best Practices

- Use type annotations to help the compiler generate direct method calls
- Prefer JavaBean-style getter/setter for properties (enables `INVOKE_GETTER`/`INVOKE_SETTER` optimization)
- Avoid unnecessary boxing by matching Java method parameter types
- Use package imports for cleaner, more readable code
