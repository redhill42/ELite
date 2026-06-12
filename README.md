# ELite

A **dynamic, multi-paradigm** JVM language with an optimizing IR pipeline,
user-extensible syntax, and gradual typing. Implemented as a `javax.script`
engine (JSR 223) for seamless Java embedding.

## Quick Start

```bash
# Requires Java 17+
git clone https://github.com/hongun/ELite.git
cd ELite
mvn package -DskipTests
cd target/elite-1.0-bin/elite-1.0
bin/elite.sh
```

```elite
> 40 + 2
42
> "Hello, " ~ "World!"
Hello, World!
> define fib = { case 0 => 0; case 1 => 1; case n => fib(n-1) + fib(n-2) }
> fib(10)
55
```

## Features

- **Multi-paradigm**: imperative, functional, object-oriented, declarative
- **Four-tier execution**: AST → conservative IR → optimized IR → JVM bytecode
- **Gradual typing**: optional type annotations with HM-style bidirectional inference
- **Pattern matching**: `match`/`case` with constants, guards, destructuring
- **Lazy sequences**: persistent, lazy `Seq` type with list comprehensions
- **User-extensible grammar**: `grammar` keyword for LALR(1) DSL definitions
- **Closures**: lexical capture with native JVM bytecode compilation
- **Tail-call optimization**: self-recursive calls in constant stack space
- **Java interop**: call Java methods/fields, implement interfaces, `@Expando` extensions
- **Exact arithmetic**: rational numbers, high-precision decimals, units of measure

## Optimization Pipeline

| Level | Strategy | Description |
|:--:|------|------|
| 0 | AST interpreter | Parser validation and debugging |
| 1 | IR interpreter (raw) | Conservative — no optimization passes |
| 2 | IR interpreter (optimized) | **Default.** Constant folding + type specialization |
| 3 | JVM bytecode | Maximum performance — direct `invokevirtual` calls |

```bash
mvn test -Delite.opt.level=3    # run tests at max optimization
bin/elite.sh -Delite.opt.level=0   # REPL at AST level
```

## Build & Test

```bash
mvn package                        # full build + distribution
mvn test                           # run all tests (567 tests)
mvn test -Dtest=ELEngineTest       # single test class
mvn test -Dtest=ELEngineTest#testArithmetic  # single method

# At each optimization level:
mvn test -Delite.opt.level=0       # AST
mvn test -Delite.opt.level=1       # IR (conservative)
mvn test -Delite.opt.level=2       # IR (optimized, default)
mvn test -Delite.opt.level=3       # Bytecode
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
| [Architecture Analysis](docs/design/ANALYSIS.md) | Internal architecture and code quality |
| [IR Opcode Plan](docs/design/ir-opcode-implementation-plan.md) | IR instruction implementation status |
| [Bytecode Gaps](docs/design/bytecode-compiler-gaps.md) | -O3 compiler known gaps |

## Hello World (5 styles)

```elite
System.out.println("Hello, World!")    // Java interop
stdout << "Hello, World!" << endl      // C++ stream style
print("Hello, World!")                 // functional
"Hello, World!".print()                // method chaining
"Hello, World!" -> print               // message-passing
```

## License

Apache License 2.0. Copyright 2006–2026 Daniel Yuan.

---

*ELite — Write what you mean, in whatever style you prefer.*
