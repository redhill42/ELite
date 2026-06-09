# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test

```bash
mvn package                          # full build + distribution assembly
mvn test                             # run all tests
mvn test -Dtest=ELEngineTest         # run a single test class
mvn test -Dtest=ELEngineTest#testArithmetic  # run a single test method
mvn compile                          # compile only
```

The build produces both a shaded JAR (with cglib/asm relocated) and a distribution zip under `target/elite-1.0-bin/`.

Java 17 is required. Tests use JUnit 4 (`@Test`/`@Before`).

## Running

```bash
cd target/elite-1.0-bin/elite-1.0
bin/elite.sh                  # interactive REPL
bin/elite.sh sample/hello.xel # run a script file
```

## Architecture

ELite is a dynamic, functional JVM language implemented as a **`javax.script` engine** (JSR 223). The codebase has two top-level package namespaces:

- **`elite.*`** — Language runtime types visible to ELite programs: `Seq`, `Symbol`, `Rational`, `Decimal`, `Range`, `Closure`, `Timestamp`, `TimeSpan`, etc. Also the AST node types under `elite.ast.*`.
- **`org.operamasks.el.*`** — The engine implementation: parser, evaluator, type system, resolvers, script engine integration, and shell.

### Pipeline: Source → AST → ELNode → Evaluation

1. **Parsing** (`org.operamasks.el.parser`): `Parser` extends `Scanner` and implements an **operator-precedence parser** that produces `ELNode` trees. `ELNode` is a parse tree node that also implements `javax.el.ValueExpression` — it can be evaluated directly.

2. **Extensible grammar** (`Grammar`, `GrammarParser`, `ParserCombinator`): The language supports **user-defined DSL syntax** via a built-in LALR(1) grammar system. `Grammar` is a serializable parse table; `GrammarParser` compiles grammar definitions into parse tables. `ParserCombinator` wraps a `Grammar` for use as a standalone parser. Built-in grammar-defined syntax libraries live in `src/main/resources/META-INF/script/elite/*.xel` (loaded at engine init).

3. **AST** (`elite.ast.*`): `Expression` is the abstract base with ~20 concrete subtypes (`ApplyExpression`, `InfixExpression`, `LambdaExpression`, `ListExpression`, `MapExpression`, `ConditionalExpression`, etc.). `ExpressionTransformer` provides visitor-pattern traversal. `ELNode.getExpression()` converts the parse tree into a typed AST `Expression`.

4. **Type system** (`org.operamasks.el.types`): **Gradual typing** — statically infer types where possible, falling back to `DynamicType` for unanalyzable code. `Type` is the abstract base; concrete types are `PrimitiveType`, `ClassType`, `FunctionType`, `VarType` (type variables), `TopType`, `BottomType`, and `DynamicType`. `TypeInferrer` performs bidirectional inference; `TypeChecker` runs as a pass between parse and eval phases, checking and persisting type bindings in the `ELContext`.

5. **Evaluation** (`org.operamasks.el.eval`):
   - `ELEngine` — static entry point; holds the global `ExpressionFactoryImpl`, resolver chain, and ELContext listener registry
   - `ELProgram` — compiled program: holds definitions (`List<ELNode>`), expressions, imports, and module references
   - `EvaluationContext` — per-expression evaluation environment; extends `AbstractClosure` and implements `PropertyDelegate`; manages `VariableMapper`/`FunctionMapper` chain and namespace declarations
   - `Frame` — stack frame holding local variable bindings
   - `Control` — `break`, `continue`, `return`, and `escape` are implemented as exceptions (for performance, `fillInStackTrace()` is a no-op)
   - `Coercion` / `TypeCoercion` — type conversion utilities between ELite and Java types

6. **Closures** (`org.operamasks.el.eval.closure`): Rich closure hierarchy for different callable things — `ThisObject` (base for objects with `this`), `AbstractClosure` (base for callables), `MethodClosure`, `FieldClosure`, `LiteralClosure`, `DataClass`, `ClassDefinition`, `Procedure`, `DelayClosure`/`DelayEvalClosure` (lazy evaluation), `DelegatingClosure`, etc.

7. **Resolvers** (`org.operamasks.el.resolver`): `javax.el.ELResolver` chain that resolves property access, method calls, and type coercion for Java interop. Includes `ClassResolver` (static Java members), `MethodResolver` (overload resolution, multi-methods), `BeanPropertyELResolver`, plus resolvers for arrays, lists, maps, sequences, strings, and units of measure.

8. **Sequences** (`org.operamasks.el.eval.seq`): Lazy/persistent sequence library — `EmptySeq`, `Cons`, `DelayCons`/`DelaySeq`, `ArraySeq`, `ListSeq`, `FilteredSeq`, `MappedSeq`, `MappendSeq`, `Map2Seq`, `IteratorSeq`, `PArraySeq` (persistent array). This implements ELite's list/seq semantics including lazy list comprehensions.

### Script Engine Integration

`ELiteScriptEngineFactory` registers ELite as a `javax.script.ScriptEngine` via `META-INF/services/javax.script.ScriptEngineFactory`. `ELiteScriptEngine` implements `Invocable` and `Compilable`. `ELiteCompiledScript` caches compiled `ELProgram` instances.

### Shell / REPL

`org.operamasks.el.shell.Main` is the entry point. `ShellContext` manages interactive state. `ConsoleReader` wraps JLine for line editing with TAB completion (`ELiteCompletor`). Shell commands are extensible via `CommandProvider` / `Command` interfaces.

### XML Support

`elite.xml.*` provides XML literal support with a virtual DOM: `VirtualNode` (abstract base), `RealNode` (wraps a W3C DOM node), and various virtual node types (`FilterVirtualNode`, `IndexedVirtualNode`, `ContainerVirtualNode`, `DescendantVirtualNode`). `XMLLib` provides XPath-like query operations. XML literals in ELite source are parsed by `XMLParser`.

### Utility Classes

`org.operamasks.util` has `BeanUtils`/`BeanProperty` (JavaBean reflection), `SimpleCache` (generic object cache), `DOMWriter`/`XmlWriter` (XML serialization), and `Utils` (miscellaneous helpers).

## Key Design Decisions

- **Control flow as exceptions**: `break`, `continue`, `return`, and `escape` use Java exceptions with `fillInStackTrace()` suppressed for performance. Don't catch `Control` (or its subclasses) in code that evaluates user expressions.
- **ELNode is both parse tree and value**: `ELNode` extends `javax.el.ValueExpression` — it can be evaluated directly without a separate compilation step. `ELProgram` packages multiple ELNodes with imports/definitions for multi-statement programs.
- **Grammar is serializable**: `Grammar` implements `Serializable` so parse tables can be cached/restored. The grammar system (`GrammarParser`) allows ELite programs to define new syntax at runtime.
- **Two POM files**: `pom.xml` is the standalone build. `cloudway-elite.pom.xml` is for embedding ELite as a child module of the Cloudway server project — it uses different dependency versions and a parent POM.
- **Type bindings persist across eval calls**: `TypeInferrer` stores/resumes type bindings in the `ELContext` so the REPL and multi-statement scripts benefit from accumulated type information.
