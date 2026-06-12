# ELite Pattern Matching Guide

## 1. Overview

ELite supports pattern matching via the `match`/`case` expression, inspired by functional languages like Haskell and Scala. Pattern matching allows deconstructing values and dispatching based on their structure.

## 2. Basic Syntax

```elite
match expression {
    case pattern1 => result1
    case pattern2 => result2
    ...
    default => fallbackResult
}
```

The `match` expression evaluates the scrutinee, then tries each `case` in order. The first matching case's result is returned. `default` matches everything (equivalent to `case _`).

## 3. Constant Patterns

Match against literal values:

```elite
define describe(n) => match n {
    case 0 => "zero"
    case 1 => "one"
    case 2 => "two"
    default => "many"
}

describe(0)    // "zero"
describe(5)    // "many"
```

String constants:

```elite
match color {
    case "red"   => 0xFF0000
    case "green" => 0x00FF00
    case "blue"  => 0x0000FF
    default      => 0x000000
}
```

## 4. Variable Patterns

Capture the matched value:

```elite
match x {
    case n if n > 0 => "positive " ~ n
    case n if n < 0 => "negative " ~ (-n)
    case 0          => "zero"
}
```

## 5. Guard Clauses (`if`)

Add boolean conditions to patterns:

```elite
match age {
    case n if n < 0   => "invalid"
    case n if n < 13  => "child"
    case n if n < 20  => "teenager"
    case n            => "adult"
}
```

## 6. Type Patterns

Match on the runtime type of a value:

```elite
define classify(x) => match x {
    case n: Number  => "number: " ~ n
    case s: String  => "string length: " ~ s.length()
    case b: Boolean => "boolean: " ~ b
    case _: List    => "a list"
    default         => "unknown type"
}
```

## 7. Destructuring Patterns

### List Patterns

```elite
match list {
    case []           => "empty"
    case [x]          => "single: " ~ x
    case [x, y]       => "pair: " ~ x ~ ", " ~ y
    case [x, y, ...rest] => "at least two: " ~ x ~ ", " ~ y
}
```

### Map Patterns

```elite
match person {
    case {name: n, age: a} => n ~ " is " ~ a ~ " years old"
    case {name: n}         => n ~ " (age unknown)"
    default                => "not a person record"
}
```

### Tuple Patterns

```elite
match point {
    case (x, y)    => "2D: (" ~ x ~ ", " ~ y ~ ")"
    case (x, y, z) => "3D: (" ~ x ~ ", " ~ y ~ ", " ~ z ~ ")"
}
```

## 8. Pattern Matching in Function Definitions

ELite supports pattern matching directly in function parameter lists:

```elite
define factorial {
    case 0 => 1
    case n => n * factorial(n - 1)
}

factorial(5)   // 120
```

This is syntactic sugar for:

```elite
define factorial(n) => match n {
    case 0 => 1
    case n => n * factorial(n - 1)
}
```

Multiple function clauses:

```elite
define fib {
    case 0 => 0
    case 1 => 1
    case n => fib(n - 1) + fib(n - 2)
}
```

## 9. Pattern Matching with Classes

```elite
class Point(x, y)

define describe(p) => match p {
    case Point(0, 0)    => "origin"
    case Point(x, 0)    => "on x-axis: " ~ x
    case Point(0, y)    => "on y-axis: " ~ y
    case Point(x, y)    => "point at (" ~ x ~ ", " ~ y ~ ")"
}
```

## 10. Exhaustiveness

ELite does not currently enforce exhaustiveness checking for pattern matches. If no case matches, a runtime error occurs. Always include a `default` case as a safety net:

```elite
match x {
    case 1 => "one"
    case 2 => "two"
    default => "unexpected: " ~ x   // always include this
}
```

## 11. Performance

At `-O3`, simple constant patterns in `match`/`case` may still go through trampoline (AST fallback). This is because the `TABLE_SWITCH` optimization for converting constant pattern matches to JVM `tableswitch`/`lookupswitch` instructions is not yet implemented.

For performance-sensitive code:
- Use simple `if`/`else` chains for known-type comparisons
- Use guard clauses for complex conditions
- Reserve `match` for cleaner, more maintainable code where absolute performance is not critical
