# ELite

A **dynamic, functional, and extensible** language for the JVM.
Seamlessly integrates with Java, supports gradual typing,
domain-specific languages, pattern matching, lazy evaluation, and more.

## Installation

```bash
git clone https://github.com/hongun/ELite.git
cd ELite
mvn package
```

## Running

```bash
cd target/elite-1.0-bin/elite-1.0
bin/elite.sh
```

Or run a script directly:

```bash
bin/elite.sh sample/hello.xel
```

## Documentation

- **[Language Specification](docs/ELite-Language-Specification.md)** — Complete language reference (17 chapters with formal grammar)
- **[Architecture Analysis](docs/ANALYSIS.md)** — In-depth analysis of the ELite implementation

---

## Quick Overview

**Hello World (5 styles):**
```elite
System.out.println("Hello, World!");   // Java style
stdout << "Hello, World!" << endl;      // C++ style
print("Hello, World!");                 // Functional style
"Hello, World!".print();               // Object style
"Hello, World!" -> print;              // Message-passing style
```

**Key Features:**
- Multi-paradigm: imperative, functional, OOP, declarative
- Gradual typing with type inference and HM-style unification
- Pattern matching and algebraic data types
- Lazy sequences and infinite data structures
- User-extensible syntax via `grammar` declarations
- Seamless Java interop via `javax.script` (JSR 223)
- Tail-call optimization

**Quick Start:**
```bash
mvn package
cd target/elite-1.0-bin/elite-1.0
./bin/elite.sh sample/hello.xel
```

**Embed in Java:**
```java
ScriptEngine eng = new ScriptEngineManager().getEngineByName("ELite");
eng.eval("define add(x, y) => x + y");
eng.eval("add(1, 2)");  // 3
```

---

*ELite — Write what you mean, in whatever style you prefer.*
