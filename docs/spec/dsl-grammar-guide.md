# ELite DSL & Grammar Extension Guide

## 1. Overview

ELite's most distinctive feature is its **user-extensible syntax**. Unlike other JVM languages where the grammar is fixed by the compiler, ELite allows defining new syntactic forms within the language itself, using a built-in LALR(1) grammar system.

This is powered by:
- `GrammarParser` — compiles BNF-like grammar definitions into LALR(1) parse tables
- `Grammar` — serializable parse table (can be cached and restored)
- `ParserCombinator` — wraps a `Grammar` for use as a standalone parser
- Dynamic operator registration — new operators can be added at runtime

## 2. Defining New Operators

ELite supports three operator positions:

### Prefix Operators

```elite
prefix 160 ++     // define prefix ++ with precedence 160
define ++x => x + 1

++5               // 6
```

### Infix Operators

```elite
infix 120 ^^^     // define infix ^^^ with precedence 120
define x ^^^ y => x ** y

2 ^^^ 10          // 1024
```

### Postfix Operators

```elite
postfix 180 --    // define postfix -- with precedence 180
define x-- => x - 1

10--              // 9
```

### Precedence Levels

ELite uses 19 precedence levels (from `THEN_PREC=0` to `NO_PREC=500`):

| Level | Operators |
|:--:|------|
| 0 | `;` (then) |
| 20 | `=` `+=` `-=` etc. (assignment) |
| 30 | `??` (coalesce) |
| 40 | `\|\|` (or) |
| 50 | `&&` (and) |
| 70 | `==` `!=` `<` `>` `<=` `>=` |
| 80 | `in` `not in` `instanceof` |
| 90 | `..` `..<` (range) |
| 100 | `+` `-` (binary) |
| 110 | `*` `/` `%` `div` |
| 120 | `**` (power) |
| 130 | `~` (concat) |
| 140 | unary `+` `-` `!` `not` |
| 160 | `::` (cons) |
| 500 | literal/tuple/primary |

## 3. Grammar Extension (`grammar` keyword)

For more complex syntactic forms, ELite provides the `grammar` keyword, which allows defining entirely new language constructs.

### Basic Syntax

```elite
grammar MyDSL {
    // Terminal definitions
    token NUMBER = "[0-9]+"
    token IDENT  = "[a-zA-Z_][a-zA-Z0-9_]*"
    token STRING = "\"[^\"]*\""
    
    // Production rules (BNF-like)
    expr ::= NUMBER
           | STRING
           | IDENT
           | expr '+' expr   %prec PLUS
           | expr '*' expr   %prec MUL
           | '(' expr ')'
}
```

### Using a Grammar

```elite
define parser = MyDSL()
define result = parser.parse("1 + 2 * 3")
```

### Grammar Semantics

Each production rule can have an associated action (semantic rule):

```elite
grammar Calculator {
    expr ::= NUMBER              { parseInt($1) }
           | expr '+' expr       { $1 + $3 }
           | expr '*' expr       { $1 * $3 }
           | '(' expr ')'        { $2 }
}
```

### How It Works Internally

1. `GrammarParser` takes the grammar definition and compiles it into an LALR(1) parse table
2. The parse table is stored in a `Grammar` object (which is `Serializable` — can be cached)
3. `ParserCombinator` wraps the `Grammar` for convenient use
4. At parse time, the LALR(1) parser uses the table to parse input

### LALR(1) Limitations

Since the grammar system is LALR(1):

- **No left recursion**: `expr ::= expr '+' expr` is ambiguous. Use precedence declarations (`%prec`) or restructure
- **No context-sensitive parsing**: The grammar must be context-free
- **One token lookahead**: Ambiguities must be resolvable with a single token of lookahead

## 4. Built-in Grammar-Defined Modules

ELite ships with several modules that use the grammar extension system:

| Module | Purpose |
|--------|---------|
| `syntax.xel` | Core syntax extension definitions |
| `function.xel` | Functional programming syntax |
| `xml.xel` | XML literal syntax |
| `complex.xel` | Complex number syntax |
| `rational.xel` | Rational number syntax |
| `measure.xel` | Units of measure syntax |
| `matrix.xel` | Matrix syntax |
| `io.xel` | I/O syntax (stream operators) |

These are loaded at engine initialization from `src/main/resources/META-INF/script/elite/*.xel`.

## 5. Dynamic Operators

The `DefaultLexer` uses a **finite state machine (FSM)** for operator recognition. Operators can be dynamically added and removed:

```java
// Java API
DefaultLexer lexer = ...;
lexer.addOperator("<=>", 80);
lexer.removeOperator("<=>");
```

The FSM state table switches from a list to an array index when the number of transitions exceeds a threshold, maintaining O(1) per-character performance.

## 6. Best Practices

- Use operator precedence values consistent with standard mathematical conventions
- For complex DSLs, prefer `grammar` definitions over ad-hoc operators
- Cache `Grammar` objects (they are `Serializable`) to avoid recompilation
- Test DSL grammars with edge cases — LALR(1) conflicts can be subtle
- Use `%prec` declarations to resolve operator precedence ambiguities
