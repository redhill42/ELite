# ELite Language Specification

**Version 2.0 — June 2026**

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Lexical Structure](#2-lexical-structure)
3. [Types](#3-types)
4. [Expressions](#4-expressions)
5. [Statements and Control Flow](#5-statements-and-control-flow)
6. [Functions](#6-functions)
7. [Pattern Matching](#7-pattern-matching)
8. [Data Structures](#8-data-structures)
9. [Classes and Objects](#9-classes-and-objects)
10. [Lazy Evaluation](#10-lazy-evaluation)
11. [Meta-programming and DSL](#11-meta-programming-and-dsl)
12. [Java Interoperability](#12-java-interoperability)
13. [Error Handling](#13-error-handling)
14. [Modules and Imports](#14-modules-and-imports)
15. [Standard Library](#15-standard-library)
16. [REPL and Tooling](#16-repl-and-tooling)
17. [Formal Grammar](#17-formal-grammar)

---

## 1. Introduction

ELite is a dynamic, multi-paradigm programming language running on the Java Virtual
Machine (JVM). It blends functional, object-oriented, and logic programming with a
powerful DSL construction facility driven by a user-extensible LALR(1) grammar.

The language is implemented as a `javax.script` engine (JSR 223), making it
embeddable in any Java application.

### 1.1 Design Philosophy

- **Expressiveness.** Multiple styles — imperative, functional, message-passing,
  and declarative — coexist in a single syntax.
- **Minimal ceremony.** Type annotations are optional. Semicolons are optional.
  Parentheses are omitted where they can be inferred.
- **Extensibility.** New syntactic forms, operators, and data constructors can be
  introduced without modifying the compiler, via grammar extension and operator
  declarations.
- **Interoperability.** Seamlessly call Java classes, implement interfaces, import
  Java packages, and create Java objects.
- **Gradual typing.** Add type annotations incrementally; the type checker verifies
  what is annotated and infers the remainder. Unannotated code remains fully dynamic.

### 1.2 Hello, World

```elite
// Java style
System.out.println("Hello, World!");

// C++ style (stream operator)
stdout << "Hello, World!" << endl;

// Functional style
print("Hello, World!");

// Object style
"Hello, World!".print();

// Message-passing style
"Hello, World!" -> print;
```

All five styles are valid and produce the same output. They illustrate the
multi-paradigm nature of the language: the same operation can be expressed in
whichever style fits the context.

### 1.3 Document Conventions

Throughout this specification:

| Notation | Meaning |
|----------|---------|
| `|` | Separates alternatives |
| `[ X ]` | X is optional |
| `X*` | Zero or more repetitions of X |
| `X+` | One or more repetitions of X |
| `X, ...` | Continuation of the same pattern |
| `<nonterminal>` | Nonterminal in grammar rules |
| `'keyword'` | Literal keyword or punctuation in grammar |

Formal grammar rules use EBNF (Extended Backus-Naur Form). Where the formal grammar
conflicts with prose, the prose takes precedence.

---

## 2. Lexical Structure

### 2.1 Source Files

Source files use UTF-8 encoding. A file contains one or more top-level expressions,
definitions, or declarations. The customary file extension is `.xel`.

Line terminators are `\n` (LF), `\r\n` (CRLF), or `\r` (CR). Line terminators may
serve as statement separators, making semicolons optional in most contexts.

### 2.2 Comments

```
// Single-line comment — extends to end of line

/* Multi-line
   comment — may span multiple lines */

/**
 * Documentation comment.
 * Processed by documentation tools.
 */
```

Comments are treated as whitespace and have no semantic effect.

### 2.3 Identifiers

```
identifier  := letter { letter | digit | '_' | '$' }
letter      := 'A'..'Z' | 'a'..'z' | '_' | '$'
digit       := '0'..'9'
```

Examples: `x`, `foo`, `myVar`, `_temp`, `$internal`, `snake_case`, `camelCase`

Identifiers are case-sensitive. `Foo` and `foo` are distinct names.

### 2.4 Keywords

The following words are reserved and may not be used as identifiers:

```
abstract    break       case        catch       class       continue
default     define      do          else        extends     false
for         grammar     if          import      in          instanceof
let         match       new         null        private     protected
public      require     return      static      switch      this
throw       true        try         type        void        while
yield
```

Additionally, `and`, `or`, `not` are keyword aliases for `&&`, `||`, `!` respectively.
`div` is recognized as the integer division operator. `is` is a type test operator.

### 2.5 Operators and Punctuation

```
Arithmetic:     +   -   *   /   %   ^   div
Comparison:     ==  !=  <   >   <=  >=  ===  !==
Logical:        &&  ||  !   and  or  not
Bitwise:        :|:  :&:  :^:  :!:
Shift:          <<  >>  >>>
String:         ~   <<   >>
Assignment:     =   :=   +=  -=  *=  /=  %=  ~=
Type:           ::  instanceof  is  as
Access:         .   ->  [   ]   (   )
Structure:      {   }   (   )   [   ]   ,   ;   :
Lambda:         \   =>
Other:          @   #   $   ?   ??  |   ...
```

### 2.6 Numeric Literals

```
decimal_literal   := digit+ [ '.' digit+ ] [ ( 'e' | 'E' ) [ '+' | '-' ] digit+ ]
                   |  digit+ ( 'b' | 'B' )    -- BigInteger
                   |  digit+ ( 'r' | 'R' )    -- Rational
hex_literal       := '0x' hex_digit+
hex_digit         := '0'..'9' | 'A'..'F' | 'a'..'f'
```

| Form | Type | Examples |
|------|------|---------|
| `42`, `0`, `-7` | `Integer` | Plain decimal |
| `0xFF` | `Integer` | Hexadecimal |
| `42b` | `BigInteger` | Suffix `b` or `B` |
| `1/3r` | `Rational` | Suffix `r` or `R` |
| `3.14` | `Double` | Decimal point |
| `1.0e-9` | `Double` | Scientific notation |

Underscores (`_`) may appear within numeric literals as separators: `1_000_000`.
They are ignored by the parser.

### 2.7 Character Literals

```
char_literal := "'" ( character | escape_sequence ) "'"
```

ELite uses single quotes for character literals, **not** for strings:

```elite
'a'         // character 'a'  (type: Char)
'\n'        // newline
'A'    // Unicode 'A'
```

### 2.8 String Literals

```
string_literal   := '"' { character | escape_sequence | interpolation } '"'
multiline_string := '"""' ... '"""'
escape_sequence  := '\' ( 'n' | 't' | 'r' | '\\' | '"' | 'u' hex hex hex hex )
```

```elite
"hello"                     // simple string
"line1\nline2"              // with escape sequences
"""This is a
multi-line string."""       // heredoc — preserves newlines
```

### 2.9 String Interpolation

```
interpolation := '${' expression '}'
```

```elite
let name = "World"
"Hello, ${name}!"           // "Hello, World!"
"1 + 2 = ${1 + 2}"          // "1 + 2 = 3"
```

The expression inside `${ }` is evaluated at runtime and its string representation
is inserted into the surrounding string.

### 2.10 Regular Expression Literals

```
regexp_literal := '/' regexp_body '/'
```

```elite
/^[a-z]+$/         // match lowercase letters
/\\d+\\.\\d+/      // match decimal numbers
```

---

## 3. Types

ELite features a **gradual type system** with local type inference. Type annotations
are optional; the type checker verifies annotated code and infers types where possible.

### 3.1 Type Annotations

```
type_annotation := '::' type_name
type_name       := simple_type | parameterized_type
simple_type     := 'Integer' | 'Long' | 'Double' | 'Float' | 'Boolean'
                 | 'String' | 'Char' | 'Number' | 'Object' | 'Void'
                 | java_qualified_name
parameterized_type := simple_type '<' type_name { ',' type_name } '>'
```

```elite
define x::Integer = 42                          // variable annotation
define add(a::Integer, b::Integer) => a + b     // parameter annotations
define add(a, b)::Integer => a + b              // return type annotation
define now::java.util.Date = new java.util.Date()  // Java class type
define m::Map<String, Integer> = {"a": 1}       // parameterized type
```

### 3.2 Built-in Types

| Type | Java Class | Literal Example | Description |
|------|-----------|-----------------|-------------|
| `Integer` | `java.lang.Integer` | `42` | 32-bit signed integer |
| `Long` | `java.lang.Long` | `42b` | Arbitrary precision via BigInteger |
| `Double` | `java.lang.Double` | `3.14` | 64-bit IEEE 754 float |
| `Float` | `java.lang.Float` | — | 32-bit float (from Java) |
| `Boolean` | `java.lang.Boolean` | `true`, `false` | Logical value |
| `Char` | `java.lang.Character` | `'a'` | Single UTF-16 character |
| `String` | `java.lang.String` | `"hello"` | Immutable character sequence |
| `Number` | `java.lang.Number` | — | Supertype of all numeric types |
| `Object` | `java.lang.Object` | — | Supertype of all reference types |
| `Void` | `java.lang.Void` | — | Return type for no-value functions |

### 3.3 Type Hierarchy

```
                  Dynamic  (gradual — compatible with everything)
                     │
                   Top  (any)
                     │
          ┌──────────┼──────────┐
          │          │          │
    PrimitiveType  ClassType  FunctionType
    (Integer,      (List<T>,   ((T1,T2)→R)
     Long,          Map<K,V>,
     Double,        java.util.Date,
     String,        ...)
     Boolean,
     Char)
          │
       Bottom  (Nothing — subtype of all)
```

`Dynamic` sits at the top of the gradual hierarchy: it is compatible with every
type, enabling gradual migration from untyped to typed code.

### 3.4 Subtype Relation

```
Integer <: Long    (by widening: int fits in long)
Integer <: Double  (by widening)
Long <: Double     (by widening)
T <: Number        (for all numeric T)
T <: Object        (for all reference types T)
Bottom <: T        (for all types T)
T <: Top           (for all types T)
T <: Dynamic       (for all types T)
```

For `ClassType`: `A<T1,...,Tn> <: B<U1,...,Un>` iff `A extends/implements B` and
`Ti <: Ui` for each `i` (covariant).

For `FunctionType`: `(A1,...,An) → R <: (B1,...,Bn) → S` iff `Bi <: Ai` for each `i`
(contravariant in parameters) and `R <: S` (covariant in return).

### 3.5 Type Checking Rules

1. **Unknown types are errors.** Using an undeclared type name triggers a
   compile-time error with source location.
2. **Argument checking.** When a function with declared parameter types is called,
   each argument must be a subtype of the corresponding parameter.
3. **Return checking.** When a function declares a return type, the body's inferred
   type must be a subtype of the declared return type.
4. **Gradual rule.** Unannotated parameters use unification-based inference and
   are not strictly checked.

### 3.6 Type Error Messages

Type errors include source location (line:column) and support internationalization:

```
1:13: Undefined type: 'UnKnown'
1:72: argument 1: expected 'Integer' but got 'String'
1:42: return type mismatch: expected 'Integer' but got 'String'
```

---

## 4. Expressions

### 4.1 Primary Expressions

```
primary := literal
        |  identifier
        |  '(' expression ')'
        |  '[' [ list_items ] ']'
        |  '(' [ tuple_items ] ')'
        |  '{' [ record_items ] '}'
        |  '\' [ params ] '=>' expression
```

```elite
42                              // numeric literal
"hello"                         // string literal
true                            // boolean literal
null                            // null literal
x                               // identifier
(x + 1)                         // parenthesized expression
[1, 2, 3]                       // list literal
[]                              // empty list
(1, "hello", true)              // 3-tuple
{a: 1, b: 2}                    // record/map literal
\x => x + 1                     // lambda (1 parameter)
\x, y => x + y                  // lambda (2 parameters)
\ => 42                         // thunk (0-parameter lambda)
```

### 4.2 Postfix Expressions

```
postfix := primary
        |  postfix '.' identifier              -- field access
        |  postfix '->' identifier             -- pipe/message send
        |  postfix '@' identifier              -- postfix application
        |  postfix '[' expression ']'          -- index access
        |  postfix '[' expression '..' expression ']'  -- slice
        |  postfix '(' [ arguments ] ')'       -- function call
        |  postfix '++'                        -- post-increment
        |  postfix '--'                        -- post-decrement
```

### 4.3 Arithmetic Operators

```
additive    := multiplicative { ( '+' | '-' ) multiplicative }
multiplicative := power { ( '*' | '/' | '%' | 'div' ) power }
power       := unary [ '^' power ]
unary       := ( '+' | '-' | '!' | 'not' | ':!:' ) unary
            |  postfix
```

| Operator | Meaning | Associativity | Example |
|----------|---------|---------------|---------|
| `^` | Exponentiation | Right | `2 ^ 10` → 1024 |
| `-x` | Unary negation | — | `-42` |
| `+x` | Unary plus | — | `+42` |
| `*` | Multiplication | Left | `7 * 8` → 56 |
| `/` | Floating-point division | Left | `10 / 3` → 3.333... |
| `div` | Integer division | Left | `10 div 3` → 3 |
| `%` | Modulo (remainder) | Left | `10 % 3` → 1 |
| `+` | Addition | Left | `1 + 2` → 3 |
| `-` | Subtraction | Left | `10 - 3` → 7 |

### 4.4 Comparison and Equality

```
comparison  := additive { ( '<' | '>' | '<=' | '>=' | 'in' | 'not' 'in'
                          | 'instanceof' | 'is' ) additive }
equality    := comparison { ( '==' | '!=' | '===' | '!==' ) comparison }
```

| Operator | Meaning | Example |
|----------|---------|---------|
| `==` | Structural equality | `[1,2] == [1,2]` → true |
| `!=` | Structural inequality | `5 != 6` → true |
| `===` | Identity equality (reference) | `x === y` |
| `!==` | Identity inequality | `x !== y` |
| `<` | Less than | `3 < 5` → true |
| `>` | Greater than | `5 > 3` → true |
| `<=` | Less than or equal | `5 <= 5` → true |
| `>=` | Greater than or equal | `5 >= 3` → true |
| `in` | Membership test | `3 in [1,2,3]` → true |
| `not in` | Exclusion test | `9 not in [1,2,3]` → true |
| `instanceof` | Type test | `x instanceof String` |
| `is` | Type test alias | `x is String` |

Structural equality compares values deeply — lists, tuples, and records are equal
if their elements are structurally equal.

### 4.5 Logical and Bitwise Operators

```
logical_and := equality { ( '&&' | 'and' ) equality }
logical_or  := logical_and { ( '||' | 'or' ) logical_and }
```

| Operator | Meaning | Short-circuit? | Example |
|----------|---------|----------------|---------|
| `!` `not` | Logical NOT | — | `!true` → false |
| `&&` `and` | Logical AND | Yes | `true && false` → false |
| `\|\|` `or` | Logical OR | Yes | `true \|\| false` → true |

Bitwise operators use colon-delimited syntax to distinguish from logical operators:

| Operator | Meaning | Example | Result |
|----------|---------|---------|--------|
| `:\|:` | Bitwise OR | `3 :\|: 4` | 7 |
| `:&:` | Bitwise AND | `5 :&: 3` | 1 |
| `:^:` | Bitwise XOR | `3 :^: 5` | 6 |
| `:!:` | Bitwise NOT | `:!:5` | -6 |
| `<<` | Shift left | `1 << 3` | 8 |
| `>>` | Shift right | `16 >> 3` | 2 |
| `>>>` | Unsigned shift right | `16 >>> 3` | 2 |

### 4.6 String Operators

```
string_op := expression { '~' expression }
           | expression '<<' expression
```

| Operator | Meaning | Example |
|----------|---------|---------|
| `~` | String concatenation | `"Hello, " ~ "World!"` → `"Hello, World!"` |
| `<<` | Stream / append | `buf << "text"` → appends to StringBuilder |

The `~` operator coerces non-string operands to strings automatically:
`"x=" ~ 42` evaluates to `"x=42"`.

### 4.7 Conditional Expression

```
conditional := logical_or [ '?' expression ':' conditional ]
```

```elite
x > 0 ? "positive" : "negative"
```

The condition is evaluated first. If truthy, the true-branch is evaluated and
returned; otherwise the false-branch. Only one branch is evaluated.

### 4.8 Coalescing

```
coalesce := conditional { '??' conditional }
```

```elite
x ?? "default"          // If x is null, use "default"
```

### 4.9 Lambda Expressions

```
lambda := '\' [ param_list ] '=>' expression
param_list := identifier { ',' identifier }
```

Lambdas are **closures** — they capture variables from their enclosing lexical scope.

```elite
\x => x + 1                     // single parameter
\x, y => x + y                  // multiple parameters
\ => 42                         // thunk (no parameters)
\a, b => a * b + 1              // expression body
```

### 4.10 Function Application

```
application := postfix '(' [ argument_list ] ')'
argument_list := expression { ',' expression }
```

```elite
f(x, y)             // standard call
x.f(y)              // method-chain style
x -> f -> g         // message-passing (pipe) style
```

All three forms are equivalent when `f` is a unary function. The pipe operator
`->` is left-associative: `x -> f -> g` is `(x -> f) -> g`, equivalent to `g(f(x))`.

#### 4.10.1 Pipe Operator (`->`) vs Postfix Application (`@`)

Both `->` and `@` enable chaining data through a pipeline of operations,
avoiding the deeply nested function-call style `f(g(h(x)))`. They share
the same left-associative semantics but carry different connotations:

|          | `->` (pipe / message send)                     | `@` (postfix application)                       |
|----------|------------------------------------------------|--------------------------------------------------|
| Concept  | Message passing — **send** data to a processor  | Function application — **apply** an operation    |
| Paradigm | Message-passing, actor-style                    | Applicative, data-first                          |
| Idiom    | `data -> filter -> map -> print`                | `data @filter @map @print`                       |
| Expands to | `print(map(filter(data)))`                    | Same                                             |

```elite
// Both forms are equivalent for unary functions:
"hello" -> greet            // "send this string to greet"
"hello" @greet              // "apply greet to this string"

// Pipeline chaining — both are left-associative:
data -> filter -> map -> reduce    // message-passing style
data @filter @map @reduce          // applicative style

// Both produce the same result:
5 -> inc -> double           // → 12
5 @inc @double               // → 12
```

`->` is often preferred when the emphasis is on **data flow through a
processing pipeline** (data in, result out), while `@` is favored when
the emphasis is on **applying operations to a value** in a functional
style. Choose whichever reads more naturally for the given context.

Both operators can be mixed freely:

```elite
data @filter @map -> result    // style is a choice, not a constraint
```

#### 4.10.2 Postfix Application with Tuples

When the left side of `@` is a tuple, the right-side function receives
the tuple elements as separate arguments:

```elite
(3, 4) @add                    // equivalent to add(3, 4)
(a, b, c) @maxOfThree          // equivalent to maxOfThree(a, b, c)
```

### 4.11 List Comprehensions

```
comprehension := '[' expression '|' generator { ',' qualifier } ']'
generator     := pattern '<-' expression
qualifier     := generator | 'let' identifier '=' expression | expression
```

```elite
// Simple mapping
[x * 2 | x <- [1..5]]                    // [2, 4, 6, 8, 10]

// With filter
[x | x <- [1..10], x % 2 == 0]           // [2, 4, 6, 8, 10]

// Multiple generators
[(a, b) | a <- [1..3], b <- [1..3], a != b]

// With let binding
[y | x <- [1..10], let y = x * x]
```

The comprehension is equivalent to a combination of `map`, `filter`, and `mappend`
operations. The first generator drives the iteration; subsequent qualifiers filter
or bind additional variables.

### 4.12 Operator Precedence

Operators are ordered from highest (tightest binding) to lowest precedence:

| Prec | Class | Operators | Assoc |
|------|-------|-----------|-------|
| 18 | Postfix | `.` `->` `@` `[` `(` `++` `--` | Left |
| 17 | Power | `^` | Right |
| 16 | Prefix | `+x` `-x` `!x` `not x` `:!:x` `++x` `--x` `empty x` | — |
| 15 | Transform | `x -> f`, `x @ f` | Left |
| 14 | Multiplicative | `*` `/` `%` `div` | Left |
| 13 | Additive | `+` `-` | Left |
| 12 | Shift | `<<` `>>` `>>>` | Left |
| 11 | Ordinal | `..` (range) | — |
| 10 | Comparison | `<` `>` `<=` `>=` `in` `not in` `instanceof` `is` | — |
| 9 | Equality | `==` `!=` `===` `!==` | — |
| 8 | Bitwise AND | `:&:` | Left |
| 7 | Bitwise XOR | `:^:` | Left |
| 6 | Bitwise OR | `:\|:` | Left |
| 5 | Logical AND | `&&` `and` | Left |
| 4 | Logical OR | `\|\|` `or` | Left |
| 3 | Coalesce | `??` | Left |
| 2 | Conditional | `? :` | Right |
| 1 | Assignment | `=` `:=` `+=` `-=` `*=` `/=` `%=` `~=` | Right |
| 0 | Sequential | `,` `;` `\n` | Left |

---

## 5. Statements and Control Flow

### 5.1 Block Expression

```
block := '{' statement* expression? '}'
```

A block is a sequence of statements and optionally a final expression. The value of
the block is the value of the last expression; if the block is empty or ends with
a statement, it evaluates to `void`.

```elite
{
    define x = 10
    define y = 20
    x + y                // block evaluates to 30
}
```

Statements are separated by newlines or semicolons. The following are equivalent:

```elite
{ define x = 1; define y = 2; x + y }
{
    define x = 1
    define y = 2
    x + y
}
```

### 5.2 Variable Declaration

```
define_stmt := 'define' identifier [ '::' type ] '=' expression
let_stmt    := 'let' identifier [ '::' type ] '=' expression
```

```elite
define x = 42                // immutable binding
let y = 100                  // block-scoped local binding
define z::Integer = 42       // with type annotation
```

`define` creates a lexical binding visible in the current scope and any nested scopes.
`let` creates a binding visible only within the innermost enclosing block.

### 5.3 Assignment

```
assignment := lvalue '=' expression
           |  lvalue compound_assign_op expression
lvalue     := identifier | lvalue '.' identifier | lvalue '[' expression ']'
           |  '(' lvalue { ',' lvalue } ')'
compound_assign_op := '+=' | '-=' | '*=' | '/=' | '%=' | '~='
```

```elite
x = 42                      // simple assignment
x += 1                      // compound: x = x + 1
point.x = 10                // field assignment
arr[i] = value               // indexed assignment
(a, b) = pair               // tuple destructuring
```

### 5.4 Conditional Statement

```
if_stmt := 'if' '(' expression ')' block [ 'else' ( block | if_stmt ) ]
        |  'if' expression '=>' expression [ 'else' '=>' expression ]
```

```elite
// Block body
if (x > 0) {
    print("positive")
} else if (x < 0) {
    print("negative")
} else {
    print("zero")
}

// Expression body (arrow form)
if x > 0 => print("positive")

// Conditional as expression
define abs(x) => x >= 0 ? x : -x
```

### 5.5 For Loop

```
for_stmt := 'for' '(' pattern 'in' expression [ ',' pattern 'in' expression ]* ')' block
```

```elite
for (i in [1..10]) {
    print(i)
}

// With index
for (x in list, i in [0..]) {
    print("${i}: ${x}")
}

// Iterating over infinite sequence
for (prime in primes) {
    if (prime > 100) break
    print(prime)
}
```

Multiple generators iterate in nested fashion: the rightmost generator varies fastest.

### 5.6 While Loop

```
while_stmt   := 'while' '(' expression ')' block
do_while_stmt := 'do' block 'while' '(' expression ')'
```

```elite
while (i < 10) {
    print(i)
    i = i + 1
}

do {
    x = next(x)
} while (not converged(x))
```

### 5.7 Break and Continue

```elite
break           // exit the innermost loop
continue        // skip to the next iteration of the innermost loop
```

### 5.8 Return Statement

```
return_stmt := 'return' [ expression ]
```

```elite
return              // return void
return x + 1        // return a value
```

A `return` without an expression is equivalent to `return void`.

---

## 6. Functions

### 6.1 Function Definition

```
function_def := 'define' identifier '(' [ param_list ] ')' [ '::' type ]
                ( '=>' expression | block )
param_list   := param { ',' param }
param        := identifier [ '=' expression ] [ '::' type ]
```

```elite
// Expression body
define add(x, y) => x + y

// Block body
define greet(name) {
    let msg = "Hello, ${name}"
    print(msg)
}

// With type annotations
define add(x::Integer, y::Integer)::Integer => x + y
```

### 6.2 Default Parameters

```elite
define greet(name = "World") => "Hello, ${name}"
greet()          // "Hello, World"
greet("Tom")     // "Hello, Tom"
```

Default values are evaluated at call time, not at definition time.

### 6.3 Variadic Functions

```
variadic_param := identifier '...'
```

```elite
define sum(args...) {
    define s = 0
    for (a in args) { s = s + a }
    s
}
sum(1, 2, 3, 4)  // 10
```

The variadic parameter collects all remaining arguments into a list.

### 6.4 Named Arguments

Arguments may be passed by name, allowing them to appear in any order:

```elite
define create(name, age, city) => ...

create(age = 30, city = "Beijing", name = "Alice")
```

### 6.5 Higher-Order Functions

Functions are first-class values: they can be passed as arguments, returned from
functions, and stored in data structures.

```elite
define twice(f, x) => f(f(x))
define addOne(x) => x + 1
twice(addOne, 5)     // 7

// Anonymous lambda
twice(\x => x * 2, 3)  // 12
```

### 6.6 Currying and Partial Application

```elite
define add(x, y) => x + y
define addOne = add.curry(1)
addOne(5)  // 6
```

Currying transforms a function of `n` arguments into a chain of `n` unary functions.

### 6.7 Operator Sections

An operator in parentheses creates a partially-applied function:

```elite
define plusOne = (+1)       // \x => x + 1
define double = (*2)         // \x => x * 2
define half = (/2)           // \x => x / 2
```

### 6.8 Tail-Call Optimization

ELite guarantees tail-call optimization (TCO) for self-recursive calls in tail
position. This means recursive functions can execute in constant stack space:

```elite
define sum(n, acc = 0) =>
    n <= 0 ? acc : sum(n - 1, acc + n)

sum(1000000)  // no stack overflow
```

---

## 7. Pattern Matching

### 7.1 Pattern-Matched Functions

```
pattern_function := 'define' identifier '(' pattern ')' [ guard ]
                    ( '=>' expression | block )
                    { '|' identifier '(' pattern ')' [ guard ]
                    ( '=>' expression | block ) }
guard            := 'if' expression
```

```elite
define fib(0) => 0
     | fib(1) => 1
     | fib(n) => fib(n-1) + fib(n-2)
```

Branches are tried in order. The first matching pattern wins. If no pattern matches,
a runtime error occurs.

With type annotation on the return type:

```elite
define fib(0)::Integer => 0
     | fib(1) => 1
     | fib(n) => fib(n-1) + fib(n-2)
```

### 7.2 Case Expression

```
case_expr := 'case' '(' expression ')' '{' case_branch { case_branch } '}'
case_branch := '|' pattern { ',' pattern } [ 'if' expression ] '=>' expression
```

```elite
define describe(x) {
    case (x) {
        | 0 => "zero"
        | 1 => "one"
        | n => "got: ${n}"
    }
}
```

### 7.3 Pattern Kinds

| Pattern | Syntax | Meaning |
|---------|--------|---------|
| Literal | `42`, `"hello"`, `true` | Match exact value |
| Variable | `x` | Bind to variable |
| Wildcard | `_` | Match anything, discard |
| Typed | `x::Integer` | Match if value is Integer |
| List empty | `[]` | Match empty list |
| List head/tail | `[x:xs]` | Destructure into head and tail |
| List fixed | `[a, b, c]` | Match list of exactly 3 elements |
| Tuple | `(a, b, c)` | Destructure 3-tuple |
| Record | `{name, age}` | Match record fields |
| Constructor | `Node(a, b)` | Match ADT constructor |
| Guarded | `x if x > 0` | Match if condition holds |
| Or | <code>pat1 &#124; pat2</code> | Match either pattern |

### 7.4 Pattern-Matched Methods (Multi-methods)

```elite
class TreeSet {
    private member(t, x) {
        | Empty(), _   => false
        | Node(_, a, y, b), x if x == y  => true
        | Node(_, a, y, b), x if x < y   => member(a, x)
        | default => member(b, x)
    }
}
```

Multi-methods dispatch on the runtime values of multiple arguments. The `default`
branch catches any unmatched case. Patterns are matched in argument order.

---

## 8. Data Structures

### 8.1 Lists

Lists are the primary sequential data structure. They are immutable linked lists with
O(1) cons (prepend) and O(n) random access.

```
list_literal := '[' [ expression { ',' expression } ] ']'
cons_pattern := '[' expression ':' expression ']'
range        := '[' expression '..' expression ']'
```

```elite
[]                              // empty list
[1, 2, 3]                       // list of three elements
[x:xs]                          // cons: prepend x to list xs
[1..10]                         // range: [1, 2, 3, ..., 10]
```

**List operations:**

```elite
lst.size()                      // number of elements
lst[i]                          // element at index i (0-based)
lst[0..3]                       // slice: first 3 elements
lst.first                       // first element
lst.tail                        // all except first
lst.init                        // all except last
lst.last                        // last element
x in lst                        // membership test
x not in lst                    // exclusion test
lst1 ~ lst2                     // concatenation
```

Lists support structural equality: `[1, 2] == [1, 2]` is `true`.

### 8.2 Ranges

```
range := '[' expression '..' expression ']'
```

```elite
[1..10]                         // 1 through 10 (inclusive)
[0..n]                          // 0 through n
[1..]                           // infinite range starting at 1
[1..*10]                        // first 10 elements of infinite range
```

Ranges are lazy: `[1..1000000]` does not allocate a million elements immediately.
Only the elements that are actually accessed are computed.

### 8.3 Records (Maps)

```
record_literal := '{' [ field { ',' field } ] '}'
field          := identifier ':' expression
               |  expression ':' expression
```

```elite
let person = {name: "Alice", age: 30}
person.name              // "Alice"
person.age               // 30
```

Record fields are accessed with dot notation. Records are structurally equal:
`{a:1, b:2} == {b:2, a:1}` is `true`.

### 8.4 Tuples

```
tuple_literal := '(' expression ',' expression { ',' expression } ')'
```

```elite
(1, "hello", true)       // 3-tuple
()                       // 0-tuple (unit)
(42)                     // NOT a tuple — just 42 parenthesized
(42,)                    // 1-tuple (trailing comma required for single element)
```

Tuple elements are accessed by index:
```elite
let t = (10, 20, 30)
t[0]                     // 10
t[1]                     // 20
```

### 8.5 Algebraic Data Types (`@data`)

```
data_decl := '@data' identifier '=' constructor { '|' constructor }
constructor := identifier [ '(' [ field { ',' field } ] ')' ]
field := identifier [ '::' type ]
```

```elite
// Simple enum-like
@data Color = R | B

// With fields
@data Tree = Leaf(value) | Node(left::Tree, value, right::Tree)

// Recursive ADT
@data List = Nil | Cons(head, tail)

// Pattern matching on ADT constructors
define sum(Leaf(v)) => v
     | sum(Node(l, v, r)) => sum(l) + v + sum(r)

// Complex example: Red-Black Tree
@data Color = R | B
@data RBTree = Empty | Node(color::Color, left::RBTree, key, value, right::RBTree)
```

`@data` generates constructor functions, pattern-matching support, and structural
equality automatically.

### 8.6 Structural Equality

ELite uses **structural equality** (`==`) by default for all built-in data structures:

- Lists: equal if same length and elements pairwise equal
- Records: equal if same keys and values pairwise equal
- Tuples: equal if same length and elements pairwise equal
- ADT instances: equal if same constructor and fields pairwise equal

Identity equality (`===`) compares object references, matching Java semantics.

---

## 9. Classes and Objects

### 9.1 Class Definition

```
class_def := [ 'abstract' ] 'class' identifier '(' [ param_list ] ')'
             [ 'extends' identifier '(' [ arguments ] ')' ]
             [ 'implements' type { ',' type } ]
             '{' { member } '}'
member     := method_def | field_def
```

```elite
class Point(x, y) {
    distance(other) => sqrt((x - other.x)^2 + (y - other.y)^2)
    +(other) => Point(x + other.x, y + other.y)
    toString() => "(${x}, ${y})"
}
```

Constructor parameters are listed after the class name. Methods are defined inside
`{ }`. Fields are implicitly created for constructor parameters. `this` is implicit;
use `this.x` only when a local variable shadows a field.

### 9.2 Instantiation

```elite
new Point(3, 4)          // Java-style
Point(3, 4)              // Constructor call without 'new'
```

Both forms are equivalent.

### 9.3 Inheritance

```elite
class ColoredPoint(x, y, color) extends Point(x, y) {
    toString() => "[${color}] ${super.toString()}"
}
```

The subclass constructor must invoke the superclass constructor with the `extends`
clause. `super.method()` calls the superclass implementation.

### 9.4 Abstract Classes

```elite
abstract class Shape {
    abstract area()
}

class Circle(radius) extends Shape {
    area() => PI * radius^2
}
```

Abstract classes cannot be instantiated. Abstract methods must be overridden by
concrete subclasses.

### 9.5 Operator Overloading

Methods with operator names overload the corresponding operators:

| Method Name | Operator | Example |
|-------------|----------|---------|
| `+(other)` | `a + b` | Binary addition |
| `-(other)` | `a - b` | Binary subtraction |
| `*(other)` | `a * b` | Binary multiplication |
| `/(other)` | `a / b` | Binary division |
| `%(other)` | `a % b` | Modulo |
| `^(other)` | `a ^ b` | Power |
| `==(other)` | `a == b` | Equality |
| `<(other)` | `a < b` | Less-than (also enables `<=`, `>`, `>=`) |
| `~()` | `~x` | Unary string conversion |
| `->(other)` | `x -> op` | Pipe/message send |
| `@(other)` | `x @ op` | Postfix application |

```elite
class Vector(x, y) {
    +(other) => Vector(x + other.x, y + other.y)
    *(scalar) => Vector(x * scalar, y * scalar)
    ==(other) => x == other.x && y == other.y
    toString() => "Vector(${x}, ${y})"
}
```

### 9.6 Reverse Operator Resolution

When `a + b` is evaluated and `a`'s class does not define `+`, the runtime attempts
a **reverse lookup**: it checks if `b`'s class defines `?+` (reverse operator). This
enables expressions like `1 + point` where `Integer` does not know about `Point`.

---

## 10. Lazy Evaluation

### 10.1 Delay and Thunks

```
delay_expr := '&' expression
```

The `&` prefix creates a lazy suspension (thunk). The expression is not evaluated
until its value is demanded.

```elite
let x = &(expensive_computation())   // not evaluated yet
x.force()                            // now it is evaluated
```

### 10.2 Lazy Sequences

Lazy sequences are built using cons (`:`) with a delayed tail:

```elite
// Infinite sequence of integers
define from(n) => [n : &from(n + 1)]
define naturals = from(1)

// Only first 5 elements are computed
naturals[0..4]  // [1, 2, 3, 4, 5]
```

### 10.3 Lazy List Comprehensions

List comprehensions produce lazy sequences by default:

```elite
define squares = [x * x | x <- [1..]]    // infinite lazy sequence
squares[0..9]                              // first 10 squares
```

### 10.4 Memoization

Delay closures cache their result: once forced, subsequent accesses return the
cached value without re-evaluation.

```elite
define fibs = [1 : &[1 : &map2((+), fibs, fibs.tail)]]
fibs[0..9]  // computes each Fibonacci number only once
```

---

## 11. Meta-programming and DSL

### 11.1 Operator Declarations

New operators can be introduced at compile time:

```
op_decl := '@infix' '(' precedence ')' string '=' token_type
        |  '@prefix' string
```

```elite
@infix(7)  '%%' = MOD          // left-associative, precedence 7
@infix(0)  ':=' = ASSIGN       // right-associative (precedence ≥ 0 = right-assoc)
@prefix    '!!'                // prefix unary operator
```

The precedence level determines binding strength — higher numbers bind tighter.
A negative or zero precedence indicates right-associativity.

### 11.2 Grammar Extension

Entirely new syntactic forms can be defined using the `grammar` keyword:

```
grammar_decl := 'grammar' '{' rule { rule } '}'
rule         := 'goal' ':' production { '|' production }
production   := terminal { terminal } '->' expression
terminal     := string          -- literal keyword
             |  '#' identifier  -- capture variable
```

```elite
grammar {
    goal
        : 'send' #message 'to' #someone
          -> print("Hello, ${someone}! ${message}.")

        | 'convert' #amount #from_unit 'into' #to_unit
          -> amount[from_unit] -> to_unit
}

send 'Welcome' to 'Mars'
print(convert 25 DEM into ECU)
```

The grammar section defines new reduction rules with semantic actions (the `->`
part). Variables prefixed with `#` are captured from the input and bound in the
action expression.

### 11.3 How Grammar Extension Works

1. The `grammar` block is parsed at compile time.
2. `GrammarParser` compiles the rules into an LALR(1) parse table.
3. The parse table is serializable, allowing caching across sessions.
4. At parse time, when the core parser encounters a token that starts a grammar
   rule, it delegates to the extended grammar's `ParserCombinator`.
5. Semantic actions are compiled into `ELNode` ASTs and inserted into the parse tree.

### 11.4 Example: Custom Control Flow

```elite
grammar {
    goal
        : 'repeat' #body 'until' #condition
          -> {
              do {
                  body
              } while (!condition)
          }
}

define x = 0
repeat {
    print(x)
    x = x + 1
} until (x >= 5)
```

### 11.5 Limitations

- Grammar extensions must be LALR(1) — no left recursion, no ambiguous grammars.
- Extensions are global to the compilation unit; they cannot be scoped to a block.
- Error messages for grammar extension parse failures refer to the extension grammar,
  which may be less informative than core language errors.

---

## 12. Java Interoperability

### 12.1 Importing Java Classes

```
import_decl := 'import' qualified_name
            |  'import' 'static' qualified_name '.*'
```

```elite
import java.util.Date
import javax.swing.JFrame
import static java.lang.Math.*
```

### 12.2 Calling Java Code

```elite
// Static methods
System.out.println("hello")
Math.abs(-42)

// Instance methods
"text".toUpperCase()
obj.toString()

// Static fields
Math.PI
```

### 12.3 Creating Java Objects

```elite
new java.util.Date()
new javax.swing.JFrame("Title")
new java.util.ArrayList()
```

### 12.4 Implementing Interfaces

```elite
let listener = new java.awt.event.ActionListener() {
    actionPerformed(e) => print("clicked")
}
```

### 12.5 Array Creation and Access

```elite
let arr = new int[10]
arr[0] = 42
arr[0]               // 42
```

### 12.6 Type Mapping

| ELite Type | Java Type |
|-----------|-----------|
| `Integer` | `java.lang.Integer` / `int` |
| `Long` | `java.lang.Long` / `long` |
| `Double` | `java.lang.Double` / `double` |
| `Float` | `java.lang.Float` / `float` |
| `Boolean` | `java.lang.Boolean` / `boolean` |
| `Char` | `java.lang.Character` / `char` |
| `String` | `java.lang.String` |
| List `[a, b]` | `elite.lang.Seq` (also `java.util.List`) |
| Record `{k: v}` | `java.util.Map` |
| Function | `elite.lang.Closure` |

---

## 13. Error Handling

### 13.1 Try-Catch

```
try_stmt := 'try' block
            { 'catch' '(' [ identifier '::' type ] ')' block }
            [ 'finally' block ]
```

```elite
try {
    risky_operation()
} catch (ex::IOException) {
    print("I/O error: ${ex.message}")
} catch (ex) {
    print("Unknown error: ${ex}")
} finally {
    cleanup()
}
```

### 13.2 Throwing Exceptions

```
throw_stmt := 'throw' expression
```

```elite
throw new IllegalArgumentException("bad argument")
throw "something went wrong"     // throws an ELite runtime exception
```

### 13.3 Assertions

```
assert_stmt := 'assert' expression [ ':' expression ]
```

```elite
assert x > 0
assert ptr != null : "pointer must not be null"
```

---

## 14. Modules and Imports

### 14.1 Require (Module Loading)

```
require_stmt := 'require' string
```

```elite
require 'math'       // load the math module
require 'io'         // load the I/O module
require 'xml'        // load XML literal support
require 'syntax'     // load syntax extensions
```

Built-in modules are located in `META-INF/script/elite/` on the classpath. Each
module is an ELite source file (`.xel`) that defines new functions, operators, and
potentially grammar rules.

### 14.2 Available Modules

| Module | Description |
|--------|-------------|
| `math` | Mathematical functions (sqrt, sin, cos, etc.) |
| `io` | File and stream I/O |
| `xml` | XML literal syntax and DOM operations |
| `syntax` | Standard syntax extensions |
| `complex` | Complex number arithmetic |
| `rational` | Rational number support |
| `matrix` | Matrix operations |
| `measure` | Units of measure and conversions |
| `function` | Additional functional programming utilities |

### 14.3 Import Syntax Summary

```
top_level := require_stmt | import_decl | define_stmt | expression
```

All `require` and `import` statements must appear at the top of a file, before any
`define` or expression.

---

## 15. Standard Library

### 15.1 I/O Functions

| Function | Signature | Description |
|----------|-----------|-------------|
| `print(x)` | `(Object) → void` | Print to stdout |
| `println(x)` | `(Object) → void` | Print with trailing newline |
| `printf(fmt, args...)` | `(String, Object...) → void` | Formatted print |
| `stdin` | Standard input stream | For reading input |
| `stdout` | Standard output stream | For writing output |

### 15.2 List/Sequence Functions

| Function | Signature | Description |
|----------|-----------|-------------|
| `length(lst)` | `(Seq) → Integer` | Number of elements |
| `empty(lst)` | `(Seq) → Boolean` | True if empty |
| `map(f, lst)` | `((A)→B, Seq<A>) → Seq<B>` | Apply f to each element |
| `filter(pred, lst)` | `((A)→Boolean, Seq<A>) → Seq<A>` | Keep elements satisfying pred |
| `foldl(f, init, lst)` | `(((A,B)→A), A, Seq<B>) → A` | Left fold |
| `foldr(f, init, lst)` | `(((A,B)→B), B, Seq<A>) → B` | Right fold |
| `take(n, lst)` | `(Integer, Seq) → Seq` | First n elements |
| `drop(n, lst)` | `(Integer, Seq) → Seq` | All but first n |
| `zip(a, b)` | `(Seq<A>, Seq<B>) → Seq<(A,B)>` | Pair elements |
| `reverse(lst)` | `(Seq) → Seq` | Reverse sequence |
| `sort(lst)` | `(Seq) → Seq` | Sort elements |
| `concat(lists...)` | `(Seq...) → Seq` | Concatenate |
| `map2(f, a, b)` | `(((A,B)→C), Seq<A>, Seq<B>) → Seq<C>` | Map over two sequences |

### 15.3 Math Functions

| Function | Description |
|----------|-------------|
| `abs(x)` | Absolute value |
| `sqrt(x)` | Square root |
| `sin(x)`, `cos(x)`, `tan(x)` | Trigonometry |
| `log(x)`, `exp(x)` | Natural log, exponential |
| `floor(x)`, `ceil(x)`, `round(x)` | Rounding |
| `min(x, y)`, `max(x, y)` | Min/max |
| `int(x)` | Truncate to integer |
| `PI` | π ≈ 3.14159... |
| `E` | e ≈ 2.71828... |

### 15.4 String Functions

| Function/Method | Description |
|-----------------|-------------|
| `str.length()` | String length |
| `str.charAt(i)` | Character at index |
| `str.substring(a, b)` | Substring from a to b |
| `str.indexOf(sub)` | Find first occurrence |
| `str.toUpperCase()` | Uppercase |
| `str.toLowerCase()` | Lowercase |
| `str.split(delim)` | Split into list |
| `items.join(delim)` | Join list into string |

### 15.5 Reflection and Type Testing

| Function | Description |
|----------|-------------|
| `typeof(x)` | Return runtime type name |
| `x instanceof T` | Type test |
| `x is T` | Type test (alias) |
| `classOf(name)` | Get Java class by name |

---

## 16. REPL and Tooling

### 16.1 Interactive REPL

```bash
bin/elite.sh
```

Features:
- Line editing with backspace and left/right arrow navigation
- History navigation with up/down arrows
- TAB completion for variables, functions, and keywords
- Multi-line input — end a line with `\` to continue
- Ctrl+D to exit
- `_` holds the value of the last evaluated expression

### 16.2 Command-Line Options

```
elite [options] [script.xel] [args...]

Options:
  -e <expression>    Evaluate a single expression
  -h, --help          Show help message
```

### 16.3 Embedding in Java

ELite implements `javax.script.ScriptEngine` (JSR 223):

```java
import javax.script.*;

ScriptEngineManager mgr = new ScriptEngineManager();
ScriptEngine eng = mgr.getEngineByName("ELite");

eng.eval("define add(x, y) => x + y");
Object result = eng.eval("add(1, 2)");  // 3

// Compilable support
CompiledScript compiled = ((Compilable) eng).compile("x * 2");

// Invocable support
Invocable inv = (Invocable) eng;
inv.invokeFunction("add", 1, 2);  // 3
```

---

## 17. Formal Grammar

This section provides the formal grammar for the core language. The actual grammar
is extensible via `grammar` declarations; these productions represent the base
language before any extensions.

### 17.1 Top-Level Structure

```
program         := { require_stmt | import_decl | define_stmt | class_def
                   | data_decl | op_decl | grammar_decl | expression }
                   [ ';' | '\n' ]

require_stmt    := 'require' STRING

import_decl     := 'import' [ 'static' ] qualified_name [ '.*' ]
```

### 17.2 Definitions

```
define_stmt     := 'define' identifier '(' [ param_list ] ')'
                   [ '::' type ] ( '=>' expression | block )
                |  'define' pattern { '|' pattern } [ '::' type ]
                   ( '=>' expression | block )
                |  'define' identifier '=' expression

param_list      := param { ',' param }
param           := identifier [ '=' expression ] [ '::' type ]
                |  identifier '...'
```

### 17.3 Expressions

```
expression      := assignment

assignment      := conditional { assign_op conditional }
assign_op       := '=' | ':=' | '+=' | '-=' | '*=' | '/=' | '%=' | '~='

conditional     := coalesce [ '?' expression ':' conditional ]

coalesce        := logical_or { ( '??' ) logical_or }

logical_or      := logical_and { ( '||' | 'or' ) logical_and }

logical_and     := equality { ( '&&' | 'and' ) equality }

equality        := comparison { ( '==' | '!=' | '===' | '!==' ) comparison }

comparison      := shift { ( '<' | '>' | '<=' | '>=' | 'in' | 'not' 'in'
                          | 'instanceof' | 'is' ) shift }

shift           := additive { ( '<<' | '>>' | '>>>' ) additive }

additive        := multiplicative { ( '+' | '-' | '~' ) multiplicative }

multiplicative  := power { ( '*' | '/' | '%' | 'div' ) power }

power           := unary [ '^' power ]

unary           := ( '+' | '-' | '!' | 'not' | ':!:' | '++' | '--' | 'empty' )
                   unary
                |  xform

xform           := postfix { '->' postfix }

postfix         := primary
                   { '.' identifier
                   | '[' expression ']'
                   | '[' expression '..' expression ']'
                   | '(' [ arguments ] ')'
                   | '++' | '--' }

primary         := INTEGER | DOUBLE | STRING | CHAR | BOOLEAN | NULL
                |  identifier
                |  '&' primary                    -- delay / thunk
                |  '(' [ expression { ',' expression } ] ')'    -- tuple / grouping
                |  '[' [ list_items ] ']'          -- list
                |  '[' expression '..' [ expression ] ']'       -- range
                |  '{' [ record_items ] '}'        -- map / record
                |  '\' [ param_list ] '=>' expression            -- lambda
                |  'new' identifier '(' [ arguments ] ')'        -- Java constructor
                |  'case' '(' expression ')' '{' case_branches '}'  -- case expr
                |  '/*' REGEXP '*/'                 -- regex literal

list_items      := expression { ',' expression }
                |  expression ':' list_items        -- cons
                |  expression '|' comprehension_tail

comprehension_tail := generator { ',' qualifier } ']'
generator        := pattern '<-' expression
qualifier        := generator | 'let' identifier '=' expression | expression

record_items    := identifier ':' expression { ',' identifier ':' expression }

arguments       := expression { ',' expression }
                |  identifier '=' expression { ',' identifier '=' expression }
```

### 17.4 Statements

```
statement       := if_stmt | for_stmt | while_stmt | do_while_stmt
                |  try_stmt | throw_stmt | return_stmt | break_stmt
                |  continue_stmt | assert_stmt | expression

block           := '{' { statement [ ';' | '\n' ] } [ expression ] '}'

if_stmt         := 'if' '(' expression ')' block
                   { 'else' 'if' '(' expression ')' block }
                   [ 'else' block ]

for_stmt        := 'for' '(' pattern 'in' expression
                           { ',' pattern 'in' expression } ')' block

while_stmt      := 'while' '(' expression ')' block

do_while_stmt   := 'do' block 'while' '(' expression ')'

try_stmt        := 'try' block
                   { 'catch' '(' [ identifier '::' type ] ')' block }
                   [ 'finally' block ]

throw_stmt      := 'throw' expression

return_stmt     := 'return' [ expression ]

break_stmt      := 'break'

continue_stmt   := 'continue'

assert_stmt     := 'assert' expression [ ':' expression ]
```

### 17.5 Patterns

```
pattern         := primary_pattern { '|' primary_pattern }      -- or-pattern
primary_pattern := literal
                |  identifier [ '::' type ]                     -- variable binding
                |  '_'                                          -- wildcard
                |  '[' [ pattern { ',' pattern } ] ']'          -- list pattern
                |  '[' pattern ':' pattern ']'                  -- cons pattern
                |  '(' [ pattern { ',' pattern } ] ')'          -- tuple pattern
                |  '{' identifier { ',' identifier } '}'        -- record pattern
                |  identifier '(' [ pattern { ',' pattern } ] ')' -- constructor
pattern         := pattern 'if' expression                      -- guard
```

### 17.6 Classes and Types

```
class_def       := [ 'abstract' ] 'class' identifier
                   '(' [ param_list ] ')'
                   [ 'extends' identifier '(' [ arguments ] ')' ]
                   [ 'implements' type { ',' type } ]
                   '{' { member } '}'

member          := method_def | field_def

method_def      := [ 'private' | 'protected' | 'public' ]
                   [ 'static' ] [ 'abstract' ]
                   identifier '(' [ param_list ] ')' [ '::' type ]
                   ( '=>' expression | block )

field_def       := [ 'static' ] identifier [ '=' expression ]

data_decl       := '@data' identifier '=' constructor { '|' constructor }
constructor     := identifier [ '(' [ param_list ] ')' ]

type            := simple_type | parameterized_type
simple_type     := 'Integer' | 'Long' | 'Double' | 'Float' | 'Boolean'
                |  'String' | 'Char' | 'Number' | 'Object' | 'Void'
                |  qualified_name
parameterized_type := simple_type '<' type { ',' type } '>'
```

### 17.7 Meta-programming

```
op_decl         := '@infix' '(' INTEGER ')' STRING '=' token_type
                |  '@prefix' STRING

grammar_decl    := 'grammar' '{' { grammar_rule } '}'

grammar_rule    := 'goal' ':' production { '|' production }

production      := ( STRING | '#' identifier )+ '->' expression
```

---

## Appendix A: Quick Sort

```elite
define qsort([])     => []
     | qsort([x:xs]) => qsort([y | y <- xs, y < x])
                      ~ [x]
                      ~ qsort([y | y <- xs, y >= x])
```

## Appendix B: Fibonacci via Lazy Sequence

```elite
define fibs = [1 : &[1 : &map2((+), fibs, fibs.tail)]]
fibs[0..9]  // [1, 1, 2, 3, 5, 8, 13, 21, 34, 55]
```

## Appendix C: Red-Black Tree with ADT

```elite
@data Color = R | B

@data Tree = Empty
           | Node(color::Color, lhs::Tree, key, value, rhs::Tree)

class TreeSet {
    private root = Empty()

    private member(Empty(), _)       => false
    private member(Node(_, a, y, b), x)
        if x == y => true
        if x <  y => member(a, x)
        default   => member(b, x)
}
```

## Appendix D: Pi via Spigot Algorithm

```elite
define pi =
    let g(q=1, r=180, t=60, i=2)
        let u = 3*(3*i+1)*(3*i+2)
        let y = (q*(27*i-12)+5*r) div (5*t)
            [y : &g(10*q*i*(2*i-1), 10*u*(q*(5*i-2)+r-y*t), t*u, i+1)]

pi[0..*20]  // first 20 digits of π
```

---

*ELite Language Specification, Version 2.0*

*© 2006-2026 Daniel Yuan. Licensed under the Apache License, Version 2.0.*
