# ELite

[![Version](https://img.shields.io/badge/version-2.0.0-blue)](https://github.com/redhill42/ELite/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)]()

A **dynamic, multi-paradigm** JVM language with an optimizing IR pipeline,
user-extensible syntax, and gradual typing — the convenience of a scripting
language with the performance of compiled Java.

## Contents

- [Why ELite?](#why-elite)
- [Feature Tour](#feature-tour)
- [Performance](#performance)
- [Quick Start](#quick-start)
- [Embed in Java](#embed-in-java)
- [Documentation](#documentation)

## Why ELite?

**For language enthusiasts** — a dynamic, multi-paradigm language that
compiles to JVM bytecode:

- **Pattern matching** — constants, guards, type checks, destructuring
- **Lazy sequences** — persistent `Seq` with list comprehensions
- **Extensible syntax** — new operators and grammars, defined at runtime
- **Exact arithmetic** — rationals, high-precision decimals, units of measure
- **Gradual typing** — annotate where you want safety, stay dynamic elsewhere

**For Java developers** — a drop-in scripting engine for any Java 17+ app:

- Standard **JSR 223** API — no custom integration code
- **`Compilable` / `Invocable`** — compile hot scripts to bytecode, invoke functions directly
- **MULTITHREADED** engine — safe for concurrent use
- **Two execution engines** — AST interpreter for debugging, bytecode executor (JIT / AOT) for performance

## Feature Tour

Every snippet below runs as-is.

### Pattern matching

Multi-clause functions with pattern arguments — constants, type
annotations, destructuring:

```elite
define fib(n) {
    | 0 => 0
    | 1 => 1
    | n => fib(n - 1) + fib(n - 2)
}

define classify(x) {
    | n::Number => "number: " ~ n
    | [x:xs] => "list: " ~ x ~ ", ..."
    | default => "something else"
}
```

### Lazy sequences

Infinite sequences stay lazy through the whole pipeline:

```elite
define from(n) => [n : &from(n+1)]
define naturals = from(1)

naturals
    .filter(\x => x % 2 == 0)
    .map(\x => x * x)
    .take(5)                        // [4, 16, 36, 64, 100]

[x * x | x <- [1..10], x % 2 == 0]  // [4, 16, 36, 64, 100]
```
See [seq.xel](src/sample/seq.xel) and [list.xel](src/sample/list.xel) for more examples of sequence.

### Extensible grammar

Define grammars at runtime — new syntax with the `grammar` keyword:

```elite
grammar
{
goal
    : 'send' #message 'to' #someone
      -> print("Hello, $someone! $message.")
    | 'please' #action 'the' #what 'of' #n
      -> action(what(n))
}

send 'Welcome to earth' to 'Uncle Martin';
please print the Math.sqrt of 100;
```
The [C.xel](src/sample/C.xel) has a complete K&R C language grammar specification.

The [scheme.xel](src/sample/scheme.xel) implemented a small subset of Scheme programming language.

### Closures & tail calls

Closures capture by reference; self-tail-calls run in constant stack space:

```elite
define fib(n, a=0, b=1) => n < 2 ? b : fib(n-1, b, a+b) // tail call
fib(12)   // 144

define makeAccumulator(total)(x) => total += x; // curried function
define acc = makeAccumulator(0)
acc(5)    // 5
acc(3)    // 8
```

### Java interop

Call Java directly, implement interfaces, and extend Java classes from
Java via `@Expando`:

```elite
Math.max(10, 20)                     // 20
define list = java.util.ArrayList()
list.add("hello"); list.size()       // 1

class MyRunnable implements java.lang.Runnable {
    run() => System.out.println("Hello from ELite!")
}
```

```java
@Expando
public static String shout(String receiver) {
    return receiver.toUpperCase() + "!";
}
```

```elite
"hello".shout()    // "HELLO!"
```

### Exact arithmetic & units

Rationals, exact decimals, and units of measure:

```elite
require "rational"
define x = 1/3                 // Rational(1, 3)
x + 2/3                        // 1
Decimal("0.1") + Decimal("0.2")    // 0.3

require "measure"
5[m] / 2[s]                    // 2.5 m/s
25[CELSIUS] -> FAHRENHEIT      // 77 °F
```

### Five ways to say hello

```elite
System.out.println("Hello, World!");
stdout << "Hello, World!" << endl;
print("Hello, World!");
"Hello, World!".print();
"Hello, World!" -> print;
```

## Performance

Two interchangeable execution engines — one property switches between them:

| Engine | Strategy | Description |
|:--:|------|------|
| AST interpreter | Tree-walking | Correctness baseline and debugging |
| Bytecode executor | JVM bytecode | Maximum performance — the default |

The bytecode executor runs in two modes:

- **JIT** — scripts are compiled to JVM bytecode on the fly, then executed
- **AOT** — compile ahead of time with `xelc`, then run the generated class directly with `java`

The bytecode compiler makes heavy use of `invokedynamic` for dynamic
dispatch — operators, coercions, and calls are linked by runtime bootstrap
methods instead of reflection — with performance comparable to statically
compiled Java.

```bash
bin/xel -Delite.opt.level=0            # REPL on the AST interpreter
bin/xelc hello.xel                     # AOT: compile to elite/program/hello.class
java -cp "lib/*:." elite.program.hello # run the compiled class with plain java
```

## Quick Start

```bash
# Requires Java 17+
git clone https://github.com/redhill42/ELite
cd ELite
mvn package -DskipTests
cd target/elite-2.0.0-bin/elite-2.0.0
bin/xel
```

```elite
> 40 + 2
42
> "Hello, " ~ "World!"
Hello, World!
```

## Embed in Java

```java
import javax.script.*;
ScriptEngine engine = new ScriptEngineManager().getEngineByName("ELite");
engine.eval("define add(x, y) => x + y");
Object result = engine.eval("add(40, 2)");  // 42L

// Compilable and Invocable
Compilable comp = (Compilable) engine;
Invocable inv = (Invocable) engine;
inv.invokeFunction("add", 10, 20);           // 30L
```

## Documentation

| Document | Description |
|----------|------------|
| [Getting Started](docs/spec/getting-started.md) | Installation, basic syntax, REPL |
| [Language Specification](docs/spec/ELite-Language-Specification.md) | Complete language reference |
| [Java Interop Guide](docs/spec/java-interop-guide.md) | Java methods, properties, imports |
| [Standard Library](docs/spec/standard-library.md) | Seq, Rational, Timestamp, math, io, etc. |
| [DSL & Grammar](docs/spec/dsl-grammar-guide.md) | User-extensible syntax |
| [Pattern Matching](docs/spec/pattern-matching-guide.md) | match/case, guards, destructuring |
| [Closure & Lazy Eval](docs/spec/closure-guide.md) | Closures, lazy sequences, TCO |

## License

Apache License 2.0. Copyright 2006–2026 Daniel Yuan.

---

*ELite — Write what you mean, in whatever style you prefer.*
