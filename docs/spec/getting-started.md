# ELite Getting Started Guide

## 1. Installation

### Prerequisites

- **Java 17** or later (`java -version`)
- **Maven** (for building from source)

### Building from Source

```bash
git clone <repository-url>
cd ELite
mvn package -DskipTests
```

The build produces:
- `target/elite-1.0-bin/elite-1.0/` — distribution directory
- `target/elite-1.0.jar` — shaded JAR (self-contained)

### Running

```bash
cd target/elite-1.0-bin/elite-1.0
bin/xel                  # interactive REPL
bin/xel sample/hello.xel # run a script file
```

Or use the JAR directly:
```bash
java -jar target/elite-1.0.jar
```

### Embedded Use (Java)

ELite implements `javax.script.ScriptEngine` (JSR 223). Add `elite-1.0.jar` to your classpath and:

```java
import javax.script.*;
ScriptEngine engine = new ScriptEngineManager().getEngineByName("ELite");
Object result = engine.eval("40 + 2");  // returns 42L
```

### Optimization Levels

ELite provides four execution tiers via the `elite.opt.level` system property:

| Level | Strategy | Use case |
|:--:|------|------|
| 0 | AST tree-walking interpreter | Parser/AST validation, debugging |
| 1 | IR interpreter (no optimizations) | Comparing raw IR vs optimized IR |
| 2 | IR interpreter (optimized) | **Default.** Production use |
| 3 | JVM bytecode compilation | Maximum performance |

```bash
mvn test -Delite.opt.level=3        # run tests at max optimization
bin/xel -Delite.opt.level=0         # REPL at AST level
```

## 2. Hello, World

```elite
// Java interop style
System.out.println("Hello, World!")

// Stream style (C++-like)
stdout << "Hello, World!" << endl
```

Semicolons are optional. The result of the last expression is returned.

## 3. Values and Types

### Primitive Literals

```elite
42              // Long (default integer type)
3.14            // Double
true, false     // Boolean
"hello"         // String
'c'             // Character
null            // Null
true            // Boolean
```

### Compound Literals

```elite
// List (comma-delimited in brackets)
[1, 2, 3]

// Map (key-value pairs in braces)
{name: "Alice", age: 30}

// Range (inclusive .., exclusive ..<)
1..5            // 1, 2, 3, 4, 5
1..<5           // 1, 2, 3, 4

// Tuple
(10, "hello", true)
```

## 4. Variables

```elite
// Immutable binding (preferred)
define x = 42
define name = "ELite"

// Mutable binding
let y = 10
y = y + 1       // y is now 11
```

`define` creates an immutable binding; `let` creates a mutable one. Both store the value in the current scope and make it accessible to subsequent expressions in the REPL.

## 5. Arithmetic

```elite
1 + 2           // 3
5 * 3           // 15
10 - 3          // 7
15 / 4          // 3.75 (exact division)
15 % 4          // 3
2 ** 10         // 1024 (power)

// Comparison
1 < 2           // true
3 >= 3          // true
1 == 1          // true
1 != 2          // true
```

ELite uses **exact division**: `15 / 4` returns `3.75` (Double), not truncated integer division. For integer division use `div` or `15 // 4`.

## 6. Strings

```elite
"hello" ~ " " ~ "world"     // "hello world" (concatenation)
"value = " ~ (40 + 2)        // "value = 42" (auto-conversion)

// Interpolation (currently via concatenation)
"The answer is " ~ (40 + 2)
```

## 7. Conditionals

```elite
// Ternary
x > 0 ? "positive" : "non-positive"

// Coalesce (null check)
maybeNull ?? "default"
```

## 8. Functions

```elite
// Anonymous lambda
(\x => x * 2)(7)            // 14
(\x, y => x + y)(10, 20)    // 30

// Named function
define add(a, b) => a + b
add(3, 4)                   // 7

// Multi-statement function body
define greet(name) => {
    define greeting = "Hello, " ~ name
    greeting
}

// Closure
define makeAdder(x) => \y => x + y
define add5 = makeAdder(5)
add5(3)                     // 8
```

## 9. Control Flow

```elite
// If expression (returns a value)
define result = if x > 0 {
    "positive"
} else {
    "non-positive"
}

// While loop
let i = 0
while i < 5 {
    System.out.println(i)
    i = i + 1
}
```

## 10. Pattern Matching

```elite
match x {
    case 0 => "zero"
    case 1 => "one"
    case n if n > 1 => "greater than one"
    default => "negative"
}
```

## 11. Java Interoperability

```elite
// Call static methods
System.currentTimeMillis()
Math.max(10, 20)
Math.sqrt(2.0)

// Create Java objects
define list = java.util.ArrayList()
list.add("hello")
list.add("world")
list.size()                 // 2

// Access fields
define p = java.awt.Point(10, 20)
p.x                         // 10

// Import packages
import java.util.*
define map = HashMap()      // java.util.HashMap
```

## 12. Built-in Types

ELite provides several built-in types in the `elite.lang` package:

| Type | Description |
|------|------------|
| `Seq` | Lazy/persistent sequence |
| `Symbol` | Interned identifier (`'foo`) |
| `Rational` | Exact rational number (`1/3`) |
| `Decimal` | High-precision decimal |
| `Timestamp` | Date/time value |
| `TimeSpan` | Duration value |
| `Range` | Numeric range with step |

## 13. The REPL

The interactive REPL (`bin/elite.sh`) supports:

- **Expression evaluation**: type an expression and press Enter
- **History**: Up/Down arrows to navigate previous inputs
- **TAB completion**: variable names, function names, class names
- **Multi-line input**: open braces/parens continue on the next line

### Shell Commands

```
:help         show available commands
:quit         exit the REPL
:load <file>  load and execute a script file
:type <expr>  show the inferred type of an expression
```

## 14. Next Steps

- [Java Interop Guide](java-interop-guide.md) — deep dive into Java integration
- [Pattern Matching Guide](pattern-matching-guide.md) — advanced pattern matching
- [Closure & Lazy Evaluation Guide](closure-guide.md) — functional programming with closures and lazy sequences
- [Language Specification](ELite-Language-Specification.md) — complete language reference
