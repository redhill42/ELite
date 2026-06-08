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

---

## Language Specification

Version 1.1 — June 2026

---

## 1. Introduction

ELite is a dynamic, multi-paradigm programming language running on the Java Virtual
Machine. It blends functional, object-oriented, and logic programming features with
a powerful DSL construction facility driven by a user-extensible LALR(1) grammar.

The language is implemented as a `javax.script` engine, making it embeddable in any
Java application.

### 1.1 Design Philosophy

- **Expressiveness.** Multiple styles — imperative, functional, message-passing,
  and declarative — coexist in a single syntax.
- **Minimal ceremony.** Type annotations are optional. Semicolons are optional.
  Parentheses are optional where they can be inferred.
- **Extensibility.** New syntactic forms, new operators, and new data constructors
  can be introduced without modifying the compiler.
- **Interoperability.** Seamlessly call Java classes, implement interfaces, and
  import Java packages.
- **Gradual typing.** Add type annotations incrementally; the compiler checks what
  you annotate and infers the rest.

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

### 1.3 Document Conventions

- `|` separates alternatives.
- `[` `]` encloses optional elements.
- `*` means zero or more repetitions.
- `+` means one or more repetitions.
- `...` means continuation of the same pattern.

---

## 2. Lexical Structure

### 2.1 Source Files

Source files use UTF-8 encoding. A file may contain one or more top-level
expressions, definitions, or declarations. The customary file extension is `.xel`.

### 2.2 Comments

```
// Single-line comment

/* Multi-line
   comment */

/**
 * Documentation comment.
 */
```

### 2.3 Identifiers

An identifier starts with a letter, underscore `_` or dollar sign `$`, followed by
letters, digits, underscores, or `$`. Identifiers are case-sensitive.

```
x   foo   myVar   _temp   $internal   snake_case   camelCase
```

### 2.4 Keywords

The following words are reserved:

```
abstract   break     case      catch    class     continue
default    define    do        else     extends   false
for        grammar   if        import   in        instanceof
let        match     new       null     private   protected
public     require   return    static   switch    this
throw      true      try       type     void      while
yield
```

### 2.5 Operators and Punctuation

```
+   -   *   /   %   ^   **
==  !=  <   >   <=  >=  <=>
&&  ||  !
&   |   ~   <<  >>  <<<
=   :=  +=  -=  *=  /=  %=
.   ->  ::  @   #   $   ?
( )   [ ]   { }   ,   ;   :
```

### 2.6 Literals

**Integer:** `42`, `0`, `-7`, `0xFF`, `0b1010`

**Long:** `42L`, `0xFFFF_FFFF_FFFFL`

**Double:** `3.14`, `1.0e-9`, `2.0d`, `1.5f` (float). Numbers are `double` by
default; `Long` and `Float` require an explicit suffix.

**Character (Char):** `'a'`, `'\n'`, `'\u0041'` — note that ELite uses single
quotes for a single *character*, not a string.

**String:** `"hello"`, `"line1\nline2"`

**Multiline string (heredoc):**
```elite
"""
This is a
multi-line string.
"""
```

**Boolean:** `true`, `false`

**Null:** `null`

### 2.7 String Interpolation

Expressions inside `${ }` are evaluated and interpolated:
```elite
let name = "World"
"Hello, ${name}!"   // "Hello, World!"
"1 + 2 = ${1 + 2}"  // "1 + 2 = 3"
```

---

## 3. Expressions

### 3.1 Primary Expressions

```
Literal         42  3.14  "hello"  true  false  null
Identifier      x
Parenthesized   (x + 1)
List            [1, 2, 3]    []           // empty list
Tuple           (1, 2, 3)    // 3-tuple
Lambda          \x => x + 1   \x, y => x + y
```

### 3.2 Arithmetic Operators

| Operator | Meaning | Associativity |
|----------|---------|---------------|
| `^` | Exponentiation | Right |
| `-x` | Unary negation | — |
| `* / %` | Multiply, Divide, Modulo | Left |
| `+ -` | Add, Subtract | Left |

Note: `/` always performs floating-point division. Use `x div y` for integer
division.

### 3.3 Comparison and Equality

| Operator | Meaning |
|----------|---------|
| `==` | Structural equality |
| `!=` | Structural inequality |
| `<` `>` `<=` `>=` | Relational comparisons |
| `<=>` | Three-way comparison |

Structural equality compares values deeply (lists, tuples, records).

### 3.4 Logical and Bitwise Operators

| Operator | Meaning |
|----------|---------|
| `!` `not` | Logical NOT |
| `&&` `and` | Logical AND (short-circuit) |
| `\|\|` `or` | Logical OR (short-circuit) |
| `&` | Bitwise AND |
| `\|` | Bitwise OR |
| `~` | Bitwise NOT |

`and`/`or`/`not` are aliases for `&&`/`||`/`!` respectively.

### 3.5 String Operators

| Operator | Meaning |
|----------|---------|
| `~` | String concatenation |
| `<<` | Stream / append |

```elite
"Hello, " ~ "World!"     // "Hello, World!"
buf << "text"             // append to StringBuilder
```

### 3.6 Access Operators

| Syntax | Meaning |
|--------|---------|
| `obj.prop` | Property access |
| `obj.method(args)` | Method call |
| `obj -> method` | Message-passing call |
| `list[i]` | Indexing (0-based) |
| `list[start..end]` | Slice |
| `(x, y)` | Tuple literal |
| `tup[0]` | Tuple element access |

### 3.7 Conditional Expression

```elite
condition ? trueBranch : falseBranch
```

### 3.8 Lambda Expressions

```elite
\x => x + 1
\x, y => x + y
\ => 42    // thunk (no-argument lambda)
```

Lambdas are closures — they capture variables from their enclosing scope.

### 3.9 Function Application

```elite
f(x, y)        // standard
x.f(y)         // method-chain style
x -> f -> y    // message-passing style
```

All three forms are equivalent when `f` is a unary function.

### 3.10 List Comprehensions

```elite
// Simple comprehension
[x * 2 | x <- [1..5]]

// With filter
[x | x <- [1..10], x % 2 == 0]

// Multiple generators
[(a, b) | a <- [1..3], b <- [1..3], a != b]

// Generator with let binding
[y | x <- [1..10], let y = x * x]
```

---

## 4. Statements and Control Flow

### 4.1 Block Expression

```elite
{
    statement1;
    statement2;
    expression   // value of last expression is the block's value
}
```

### 4.2 Variable Declaration

```elite
define x = 42                    // immutable binding
define x = 42;                   // semicolon optional
let y = 100                      // local binding (block-scoped)
```

`define` creates a lexical binding visible in the current scope. `let` creates a
binding visible only within the enclosing block.

### 4.3 Assignment

```elite
x = 42          // re-assignment
x := 42         // alternative syntax (with @infix declaration)
x += 1          // compound assignment
```

### 4.4 Conditional Statement

```elite
if (x > 0) {
    print("positive")
} else if (x < 0) {
    print("negative")
} else {
    print("zero")
}
```

The condition parentheses are optional:
```elite
if x > 0 => print("positive")
```

### 4.5 Pattern Matching (`match`)

```elite
match (value) {
    case 0 => "zero"
    case 1 => "one"
    case _ => "many"
}
```

### 4.6 For Loop

```elite
for (i in [1..10]) {
    print(i)
}

for (x in list, i in [0..]) {
    print("${i}: ${x}")
}
```

### 4.7 While Loop

```elite
while (condition) {
    body
}
```

### 4.8 Do-While Loop

```elite
do {
    body
} while (condition)
```

---

## 5. Functions

### 5.1 Function Definition

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

### 5.2 Pattern-Matched Functions

```elite
define fib(0) => 0
     | fib(1) => 1
     | fib(n) => fib(n-1) + fib(n-2)
```

Each branch is tried in order. The first matching pattern wins. Multiple branches
may share a single return-type annotation:

```elite
define fib(0)::Integer => 0
     | fib(1) => 1
     | fib(n) => fib(n-1) + fib(n-2)
```

### 5.3 Pattern Kinds

| Pattern | Meaning |
|---------|---------|
| `42` | Match literal value |
| `x` | Bind to variable |
| `_` | Wildcard (match anything, discard) |
| `[x:xs]` | Destructure list head/tail |
| `[]` | Match empty list |
| `{a, b}` | Match record |
| `Node(a, b)` | Match algebraic data constructor |
| `x if condition` | Guard clause |

### 5.4 Default Parameters

```elite
define greet(name="World") => "Hello, ${name}"
greet()       // "Hello, World"
greet("Tom")  // "Hello, Tom"
```

### 5.5 Variadic Functions

```elite
define sum(args...) {
    // args is a list of all arguments
    foldl((+), 0, args)
}

sum(1, 2, 3, 4)  // 10
```

### 5.6 Higher-Order Functions

Functions are first-class values:

```elite
define twice(f, x) => f(f(x))
define addOne(x) => x + 1
twice(addOne, 5)  // 7

// Anonymous lambda
twice(\x => x * 2, 3)  // 12
```

### 5.7 Currying and Partial Application

```elite
define add(x, y) => x + y
define addOne = add.curry(1)
addOne(5)  // 6

// Operator section
define doubleAll = map((*2), _)   // multiply every element by 2
```

---

## 6. Data Structures

### 6.1 Lists

```elite
[]                     // empty list
[1, 2, 3]              // list literal
[1..10]                // range: [1, 2, ..., 10]
[x:xs]                 // cons: x prepended to xs
lst1 ~ lst2            // list concatenation
```

**List operations:**
```elite
lst.length()    // length
lst[i]          // element at index i (0-based)
lst[0..3]       // slice
lst.first       // first element
lst.tail        // all elements except first
lst.init        // all elements except last
lst.last        // last element
x in lst        // membership test
x not in lst    // exclusion test
```

### 6.2 Lazy Sequences

```elite
// Infinite sequence of integers
define from(n) => [n : &from(n+1)]
define integers = from(1)

// Only first 10 elements are computed
let firstTen = integers[0..*10]
```

The `&` prefix creates a lazy suspension (thunk). Evaluation is deferred until the
element is accessed.

### 6.3 Algebraic Data Types (`@data`)

```elite
@data Tree = Leaf(value) | Node(left::Tree, value, right::Tree)

// Pattern matching on constructors
define sum(Leaf(v)) => v
     | sum(Node(l, v, r)) => sum(l) + v + sum(r)
```

```elite
@data Color = R | B
@data RBTree = Empty | Node(color::Color, left::RBTree, value, right::RBTree)
```

Constructors may have zero, one, or many fields. Fields may carry `::` type
annotations.

### 6.4 Records

```elite
let person = {name: "Alice", age: 30}
person.name       // "Alice"
person.age        // 30
```

### 6.5 Tuples

```elite
(1, "hello", true)    // 3-tuple
tup[0]                // 1
tup[1]                // "hello"
```

---

## 7. Classes and Objects

### 7.1 Class Definition

```elite
class Point(x, y) {
    distance(other) => sqrt((x - other.x)^2 + (y - other.y)^2)
    +(other) => Point(x + other.x, y + other.y)
    toString() => "(${x}, ${y})"
}
```

Constructor parameters are listed after the class name. Methods are defined
inside `{ }`. `this` is implicit; use `this.x` only when a local variable
shadows a field.

### 7.2 Instantiation

```elite
new Point(3, 4)      // Java-style
Point(3, 4)          // Constructor call without 'new'
```

### 7.3 Inheritance

```elite
class ColoredPoint(x, y, color) extends Point(x, y) {
    toString() => "[${color}] ${super.toString()}"
}
```

### 7.4 Abstract Classes and Methods

```elite
abstract class Shape {
    abstract area()
}

class Circle(radius) extends Shape {
    area() => PI * radius^2
}
```

### 7.5 Operator Overloading

Methods with operator names overload the corresponding operators:
```elite
+(other)    // a + b
-(other)    // a - b
*(other)    // a * b
==(other)   // a == b
<(other)    // a < b (also enables <=, >, >=)
~()         // unary ~ (e.g. ~x)
```

### 7.6 Multi-methods (Pattern-Matched Methods)

Methods can be pattern-matched on their arguments:

```elite
class TreeSet {
    private member(t, x) {
        | Empty(), _   => false
        | Node(_,a,y,b), x if x == y  => true
        | Node(_,a,y,b), x if x < y   => member(a,x)
        | default => member(b,x)
    }
}
```

---

## 8. Meta-programming and Operator Declarations

### 8.1 Operator Declarations

New operators can be introduced at compile time:

```elite
@infix(7)  '%%' = MOD         // left-associative, precedence 7
@infix(0)  ':=' = ASSIGN      // right-associative
@prefix    '!!'                // prefix unary operator
```

The precedence level determines binding strength (higher number = tighter binding).

### 8.2 Grammar Extension

Entirely new syntactic forms can be defined:

```elite
grammar {
    goal
        : 'send' #message 'to' #someone
          -> print("Hello, $someone! $message.")

        | 'convert' #amount #from_unit 'into' #to_unit
          -> amount[from_unit] -> to_unit
}

send 'Welcome' to 'Mars'
print(convert 25 DEM into ECU)
```

The grammar section defines new reduction rules with semantic actions (the `->`
part). Variables prefixed with `#` are captured from the input.

### 8.3 Importing Syntax Extensions

```elite
require 'syntax'    // load standard syntax extensions
require 'math'      // load math library
```

### 8.4 Java Imports

```elite
import javax.swing.JFrame
import java.awt.GridLayout
import static java.lang.Math.*
```

---

## 9. Type System

ELite features a **gradual type system** with local type inference. You can add
type annotations where you want safety, and omit them where you want flexibility.

### 9.1 Type Annotations (`::` Syntax)

```elite
define x::Integer = 42                      // variable
define add(a::Integer, b::Integer) => a + b // parameters
define add(a, b)::Integer => a + b          // return type
define greet()::String => "hello"           // no-argument
```

The `::` operator binds tighter than `=>` but looser than most other operators.

### 9.2 Built-in Types

| Type | Java class | Literal |
|------|-----------|---------|
| `Integer` | `java.lang.Integer` | `42` |
| `Long` | `java.lang.Long` | `42L` |
| `Double` | `java.lang.Double` | `3.14` |
| `Char` | `java.lang.Character` | `'a'` |
| `String` | `java.lang.String` | `"hello"` |
| `Boolean` | `java.lang.Boolean` | `true` |
| `Void` | `java.lang.Void` | `void` (return type only) |

### 9.3 Class Types

Any Java class can be used as a type annotation:
```elite
define now::java.util.Date => new java.util.Date()
```

### 9.4 Function Types (Internal)

Function types are inferred internally as `(ParamType...) -> ReturnType`. You do
not normally write them, but the type checker uses them to verify argument
compatibility.

### 9.5 Type Checking Rules

1. **Unknown types are errors.** Using an undeclared type name (e.g. `::Unknown`)
   triggers a compile-time error.
2. **Argument checking.** When a function with declared parameter types is called,
   each argument must be a subtype of the corresponding parameter.
3. **Return checking.** When a function declares a return type, the body's inferred
   type must be a subtype.
4. **Gradual rule.** Unannotated parameters are **not** strictly checked — they
   use unification-based inference.

### 9.6 Type Inference (HM-style unification)

The inference engine uses Hindley-Milner style unification with the following
type hierarchy:

```
Dynamic (gradual top — "anything goes")
  └── Top (any)
       ├── PrimitiveType  (Integer, Long, Double, String, Boolean, Char)
       ├── ClassType      (nominal types with generic parameters)
       ├── FunctionType   (param types → return type)
       └── VarType        (type variables for inference)
```

`Dynamic` is the top of the gradual type hierarchy — it is compatible with
everything, allowing gradual migration from untyped to typed code.

### 9.7 Error Messages

Type errors include source location (line:column) and support i18n:

```
EN: 1:13: Undefined type: 'UnKnown'
ZH: 1:13: 未定义的类型：'UnKnown'

EN: 1:72: argument 1: expected 'Integer' but got 'String'  
ZH: 1:72: 第1个参数：期望类型'Integer'，实际为'String'

EN: 1:42: return type mismatch: expected 'Integer' but got 'String'
ZH: 1:42: 返回类型不匹配：期望'Integer'，实际为'String'
```

---

## 10. Built-in Functions

### 10.1 I/O

| Function | Description |
|----------|-------------|
| `print(x)` | Print value to stdout |
| `println(x)` | Print value with trailing newline |
| `printf(fmt, args...)` | Formatted print |
| `stdin.readline()` | Read a line from stdin |
| `stdout << x` | Write to stdout |

### 10.2 List Operations

| Function | Description |
|----------|-------------|
| `length(lst)` / `lst.length()` | Number of elements |
| `empty(lst)` | True if list is empty |
| `map(f, lst)` | Apply f to each element |
| `filter(pred, lst)` | Keep elements satisfying pred |
| `foldl(f, init, lst)` | Left fold |
| `foldr(f, init, lst)` | Right fold |
| `take(n, lst)` | First n elements |
| `drop(n, lst)` | All but first n elements |
| `zip(lst1, lst2)` | Pair elements |
| `reverse(lst)` | Reverse list |
| `sort(lst)` | Sort elements |
| `shuffle(lst)` | Random shuffle |
| `concat(lists...)` | Concatenate lists |

### 10.3 Math

| Function | Description |
|----------|-------------|
| `abs(x)` | Absolute value |
| `sqrt(x)` | Square root |
| `sin(x)`, `cos(x)`, `tan(x)` | Trigonometry |
| `log(x)`, `exp(x)` | Log, exponential |
| `floor(x)`, `ceil(x)`, `round(x)` | Rounding |
| `min(x,y)`, `max(x,y)` | Min/max |
| `int(x)` | Truncate to integer |
| `int div y` | Integer division |

### 10.4 String

| Function | Description |
|----------|-------------|
| `str.length()` | String length |
| `str.charAt(i)` | Character at index |
| `str.substring(a,b)` | Substring |
| `str.indexOf(sub)` | Find substring |
| `str.toUpperCase()` | Uppercase |
| `str.toLowerCase()` | Lowercase |
| `str.split(delim)` | Split into list |
| `items.join(delim)` | Join list into string |

### 10.5 Reflection

| Function | Description |
|----------|-------------|
| `typeof(x)` | Return type name |
| `x instanceof T` | Type test |
| `classOf(name)` | Get class by name |
| `x is Class` / `x is Closure` | Runtime type test |

### 10.6 Concurrency (Coroutines)

```elite
yield(value)          // yield from generator
run_cont(m, handler)  // run continuation monad
call_cc(f)            // call with current continuation
```

---

## 11. Monads and Do-Notation

ELite supports a general monad framework with `do` notation and continuation
support.

```elite
// Maybe Monad
@data Maybe = Nothing | Just(x)
extends Monad {
    bind(k) => this is Just ? k(x) : this
    static yield(x) => Just(x)
}

define safeDiv(x, y) => y == 0 ? Nothing() : Just(x / y)

define compute() => do {
    a <- safeDiv(10, 2)
    b <- safeDiv(a, 0)
    yield(b)
}

// State Monad
define fib(n) {
    define fib_s = do {
        (n, a, b) <- get
        n == 1 ? State.yield(a)
               : (put(n-1, b, a+b) >> fib_s)
    }
    eval_state(fib_s, (n, 1, 1))
}
```

The `do` block uses `<-` to extract values from monadic computations. Guards use
`if` without else.

---

## 12. Java Interoperability

### 12.1 Calling Java Methods

```elite
System.out.println("hello")        // static method
obj.toString()                     // instance method
"text".toUpperCase()               // string method
```

### 12.2 Creating Java Objects

```elite
new java.util.Date()
new javax.swing.JFrame("Title")
new java.util.ArrayList()
```

### 12.3 Implementing Interfaces

```elite
let listener = new java.awt.event.ActionListener() {
    actionPerformed(e) => print("clicked")
}
```

### 12.4 Static Import

```elite
import static java.lang.Math.*
PI              // 3.14159...
sin(PI / 2)     // 1.0
```

### 12.5 Array Access

Java arrays are accessed with the same `[]` syntax:
```elite
let arr = new int[10]
arr[0] = 42
```

---

## 13. Error Handling

### 13.1 Exception Handling

```elite
try {
    risky_operation()
} catch (ex::IOException) {
    print("I/O error: ${ex.message}")
} catch (ex) {
    print("Unknown error: ${ex}")
}
```

### 13.2 Throwing Exceptions

```elite
throw new IllegalArgumentException("bad argument")
```

---

## 14. REPL and Tooling

### 14.1 Interactive REPL

Launch the REPL:
```bash
bin/elite.sh
```

Features:
- Line editing (backspace, left/right arrows)
- History navigation (up/down arrows)
- TAB completion for variables and built-in functions
- Multi-line input (end a line with `\` to continue)
- Ctrl+D to exit
- `_` holds the value of the last evaluated expression

### 14.2 Command-Line Options

```
elite [options] [script.xel] [args...]
  -e <expression>     evaluate expression
  -h, --help          show help
```

### 14.3 Embedding in Java

```java
import javax.script.*;

ScriptEngine eng = new ScriptEngineManager().getEngineByName("ELite");
eng.eval("define add(x, y) => x + y");
Object result = eng.eval("add(1, 2)");  // 3
```

---

## 15. Grammar Reference

This section provides a simplified grammar for the core language. The actual
grammar is extensible; users may add new productions.

```
program        := (define | decl | expr) (';' (define | decl | expr))*

define         := 'define' id pattern* ('::' type)? '=>' expr
               |  'define' id '(' params ')' ('::' type)? block
               |  'define' id '(' params ')' ('::' type)? '=>' expr

pattern        := literal | id | '_' | '[' pattern* ']'
               |  pattern ':' pattern  |  id '(' pattern* ')'
               |  '{' (id (':' expr)?)* '}'

expr           := assign_expr

assign_expr    := cond_expr ('=' cond_expr | '+=' cond_expr | ...)?

cond_expr      := or_expr ('?' expr ':' cond_expr)?

or_expr        := and_expr ('||' and_expr)*

and_expr       := eq_expr ('&&' eq_expr)*

eq_expr        := rel_expr (('==' | '!=') rel_expr)*

rel_expr       := add_expr (('<' | '>' | '<=' | '>=') add_expr)*

add_expr       := mul_expr (('+' | '-' | '~') mul_expr)*

mul_expr       := unary_expr (('*' | '/' | '%') unary_expr)*

pow_expr       := access_expr ('^' pow_expr)?

unary_expr     := ('-' | '!' | 'not') unary_expr | postfix_expr

access_expr    := primary ('(' args ')' | '[' expr ']' | '.' id | '->' id)*

primary        := literal | id | '_' | '(' expr ')' | '[' list_items ']'
               |  '[' expr '..' expr ']'  |  '[' expr ':' list_items ']'
               |  '{' records '}'  |  '\' params '=>' expr
               |  'if' expr block ('else' block)?
               |  'while' expr block  |  'for' '(' iter ')' block
               |  'let' binding block  |  'do' block
               |  'match' '(' expr ')' '{' cases '}'
               |  'try' block 'catch' '(' pattern ')' block

block          := '{' (define | expr ';')* expr? '}'
```

### 15.1 Operator Precedence Table

| Precedence | Operators | Associativity |
|-----------|-----------|---------------|
| 13 | `.` `->` `[` `(` | Left |
| 12 | Unary `-` `!` `not` | — |
| 10 | `^` (power) | Right |
| 9 | `*` `/` `%` | Left |
| 8 | `+` `-` `~` | Left |
| 7 | `<<` `>>` | Left |
| 6 | `<` `>` `<=` `>=` `in` `not in` `instanceof` `is` | — |
| 5 | `==` `!=` | — |
| 4 | `&` | Left |
| 3 | `\|` | Left |
| 2 | `&&` `and` | Left |
| 1 | `\|\|` `or` | Left |
| 0 | `=` `:=` `+=` `-=` | Right |

---

## Appendix A: Complete Hello World Examples

```elite
// 1. Java style
System.out.println("Hello, World!");

// 2. C++ style (operator<<)
stdout << "Hello, World!" << endl;

// 3. Functional style
print("Hello, World!");

// 4. Object style
"Hello, World!".print();

// 5. Message-passing style
"Hello, World!" -> print;

// 6. With function definition
define sayHello(thing) => "Hello, ${thing}!";
print(sayHello("World"));
sayHello("World").print();
"World" -> sayHello -> print;
```

## Appendix B: Quick Sort (Idiomatic)

```elite
define qsort([])     => []
     | qsort([x:xs]) => qsort([y | y <- xs, y < x])
                      ~ [x]
                      ~ qsort([y | y <- xs, y >= x])
```

## Appendix C: Fibonacci (Lazy Sequence)

```elite
define fibs = [1 : &[1 : &add_cons(fibs.tail, fibs)]]
```

## Appendix D: Algebraic Data Type (Red-Black Tree)

```elite
@data Color = R | B

@data Tree = Empty
           | Node(color::Color, lhs::Tree, value, rhs::Tree)

class TreeSet {
    private root = Empty()

    private member(Empty(), _)       => false
    private member(Node(_, a, y, b), x)
        if x == y => true
        if x <  y => member(a, x)
        default   => member(b, x)
}
```

---

*ELite — Write what you mean, in whatever style you prefer.*
