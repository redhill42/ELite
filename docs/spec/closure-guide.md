# ELite Closure & Lazy Evaluation Guide

## 1. Overview

ELite provides first-class closures and lazy evaluation primitives, enabling functional programming patterns. Closures capture their lexical environment, and lazy evaluation allows working with potentially infinite data structures.

## 2. Closures

### Lambda Expressions

```elite
// Simple lambda
\ => 42                           // no params
\x => x * 2                       // one param
\x, y => x + y                    // two params

// Calling a lambda
(\x => x * 2)(7)                  // 14
```

### Named Functions as Closures

```elite
define makeAdder(x) => \y => x + y
define add5 = makeAdder(5)
add5(3)                           // 8 — x=5 captured in closure
add5(10)                          // 15
```

### Multi-Statement Closures

```elite
define makeCounter() => \ => {
    define start = 0
    start = start + 1
}
```

### Closure Semantics

ELite closures **capture variables by reference** from their enclosing scope. This means:

```elite
define makeAccumulator(init) => {
    let total = init
    \x => {
        total = total + x    // modifies the captured 'total'
        total
    }
}

define acc = makeAccumulator(0)
acc(5)    // 5
acc(3)    // 8 — total persists across calls
```

### Free Variable Capture

Free variables (variables referenced but not defined in the lambda's parameter list) are automatically captured:

```elite
define pi = 3.14159
define circleArea = \r => pi * r * r    // 'pi' is captured from outer scope

circleArea(5)    // 78.53975
```

At `-O3`, captured variables are packed into an `Object[]` and passed to the closure at creation time via `CLOSURE` opcode, avoiding runtime scope chain traversal.

## 3. Lazy Sequences (`Seq`)

ELite's `Seq` type is the foundation of lazy evaluation. Sequences can be constructed eagerly or lazily.

### Eager Construction

```elite
[1, 2, 3, 4, 5]           // list literal → ArraySeq
cons(1, cons(2, nil))      // explicit cons cells
Seq(1, 2, 3)               // variadic factory
```

### Lazy Construction

```elite
// Infinite sequence (by-need evaluation)
define ones = DelaySeq(\ => cons(1, ones))

ones.head()                 // 1
ones.tail().head()          // 1
ones.tail().tail().head()   // 1
```

### Lazy Transformations

All transformation operations on Seq are **lazy** — they return a new Seq that computes elements on demand:

```elite
define naturals = iterate(1, \x => x + 1)   // 1, 2, 3, ...

naturals
    .filter(\x => x % 2 == 0)               // even numbers (lazy)
    .map(\x => x * x)                       // squares (lazy)
    .take(5)                                 // [4, 16, 36, 64, 100]
```

### Sequence Operations

| Operation | Description | Eagerness |
|-----------|------------|:--:|
| `head()` | First element | Eager |
| `tail()` | Rest of sequence | Lazy |
| `isEmpty()` | Is empty? | Eager |
| `take(n)` | First n elements | Lazy |
| `drop(n)` | Skip n elements | Lazy |
| `filter(pred)` | Keep elements matching predicate | Lazy |
| `map(fn)` | Transform each element | Lazy |
| `takeWhile(pred)` | Take while predicate holds | Lazy |
| `dropWhile(pred)` | Drop while predicate holds | Lazy |
| `zip(other)` | Pair with another sequence | Lazy |
| `flatMap(fn)` | Map then flatten | Lazy |
| `reduce(fn)` | Reduce to single value | Eager |
| `forEach(fn)` | Execute side effect per element | Eager |

### Infinite Sequences

Lazy evaluation enables working with infinite sequences — only the elements that are actually consumed are computed:

```elite
// Fibonacci numbers
define fibs = define {
    define fibFrom(a, b) => DelaySeq(\ => cons(a, fibFrom(b, a + b)))
    fibFrom(0, 1)
}

fibs.take(10)    // [0, 1, 1, 2, 3, 5, 8, 13, 21, 34]
```

### List Comprehensions

```elite
[x * x | x <- 1..10, x % 2 == 0]     // squares of even numbers: [4, 16, 36, 64, 100]
```

List comprehensions are desugared into `flatMap`/`map`/`filter` chains and are **eager** — they evaluate the entire result immediately.

## 4. DelayClosure and DelayEvalClosure

### DelayClosure (Memoizing)

`DelayClosure` wraps a computation that is evaluated **at most once**. The result is cached:

```elite
define expensive = DelayClosure(\ => {
    System.out.println("computing...")
    42
})

expensive()    // prints "computing...", returns 42
expensive()    // returns 42 (cached, no print)
```

### DelayEvalClosure (Non-Memoizing)

`DelayEvalClosure` re-evaluates the computation on each invocation:

```elite
define now = DelayEvalClosure(\ => System.currentTimeMillis())
now()          // 1718123456789
// ...time passes...
now()          // 1718123460000 (different value)
```

## 5. Tail Call Optimization (TCO)

ELite implements tail call optimization for self-recursive functions. When a function's last action is a call to itself, the call is executed in the same stack frame:

```elite
define sumTo(n, acc=0) => if n <= 0 {
    acc
} else {
    sumTo(n - 1, acc + n)    // tail call — no stack growth
}

sumTo(1000000)    // works without stack overflow
```

For TCO to apply:
- The recursive call must be in **tail position** (the last action)
- The function must call **itself** (mutual recursion is not optimized)

## 6. Internal Representation

### Closures at -O3

At the bytecode level, closures are compiled as `IRClosure` objects:

```
CLOSURE funcIdx, captureCount:
  1. Pop captureCount values from stack
  2. Pack into Object[] captured
  3. Load IRFunction from constant pool
  4. Create IRClosure(fn, captured)
```

When a closure is called:
1. Arguments are expanded with captured values
2. A new `IRInterpreter` executes the function body with the combined locals array

### Lazy Sequences at Runtime

Lazy sequences are built from `DelaySeq`/`DelayCons` cells. Each cell holds a `Closure` that produces the next element when forced:

```elite
Cons(head=1, tail=DelaySeq(\ => computeRest()))
```

The `Seq.size()` default implementation is `O(n)` — it walks the entire sequence. Avoid calling `.size()` on lazy/infinite sequences.

## 7. Best Practices

- Prefer `DelayClosure` (memoizing) for expensive computations that may be accessed multiple times
- Use `DelayEvalClosure` for time-sensitive values (timestamps, IO)
- Be mindful of memory: infinite sequences held by a reference will never be GC'd
- Use `.take(n)` to limit infinite sequences before forcing evaluation
- For recursive algorithms on sequences, use tail recursion or iterative loops to avoid stack overflow
- Closure capture is by reference — be careful with mutable state shared across closures
