# ELite Standard Library Reference

## 1. Built-in Types (`elite.lang`)

ELite ships with several built-in types that extend the Java type system with functional and mathematical capabilities.

### Seq — Lazy/Persistent Sequences

`Seq` is ELite's core functional data structure, inspired by Clojure's sequences. It provides immutable, potentially lazy, linked-list semantics.

```elite
// Construction
define s = [1, 2, 3]              // via list literal → ArraySeq
define s2 = cons(1, cons(2, nil))  // explicit cons cells
define s3 = Seq(1, 2, 3)          // variadic factory

// Operations
s.head()          // first element
s.tail()          // rest of sequence
s.isEmpty()       // is it empty?
seq(collection)   // convert any collection to Seq

// Lazy operations (return new lazy sequences)
s.filter(\x => x > 1)
 .map(\x => x * 2)
 .take(5)
 .drop(2)
 .takeWhile(\x => x < 10)
```

**Key properties:**
- Immutable — operations create new sequences
- Lazy — filters/maps are evaluated on demand
- Persistent — structural sharing between versions
- `java.util.AbstractList` compatible — works with Java collections APIs

### Symbol

Interend, lightweight identifiers:

```elite
'foo              // Symbol("foo")
'hello-world      // Symbol("hello-world")
```

### Rational — Exact Fractions

```elite
1/3 + 2/3         // 1 (exact rational)
3/4 * 2/3         // 1/2
```

Rational numbers maintain exact precision, avoiding floating-point errors. Enabled by the `rational.xel` module.

### Decimal — High-Precision Decimal

```elite
Decimal("0.1") + Decimal("0.2")   // 0.3 (exactly)
```

Unlike `double`, `Decimal` performs exact decimal arithmetic suitable for financial calculations.

### Range — Numeric Ranges

```elite
1..5              // 1, 2, 3, 4, 5 (inclusive)
1..<5             // 1, 2, 3, 4 (exclusive)
1..10:2           // 1, 3, 5, 7, 9 (step 2)
```

Ranges implement `Seq` and can be used with all sequence operations.

### Timestamp and TimeSpan

```elite
Timestamp("2026-06-12")                    // date
Timestamp("2026-06-12T10:30:00")           // datetime
Timestamp.now()                            // current time

TimeSpan("1h30m")                          // 1 hour 30 minutes
TimeSpan("2d")                             // 2 days

Timestamp.now() + TimeSpan("1h")           // one hour from now
Timestamp.now() - TimeSpan("30m")          // 30 minutes ago
```

## 2. Module Reference

Modules are loaded via `require`:

```elite
require math       // load math.xel
require rational   // enable rational literals
```

### `math` — Mathematical Functions

Java class: `elite.lang.MathLib`

```elite
sin(x), cos(x), tan(x), asin(x), acos(x), atan(x)
sqrt(x), cbrt(x), exp(x), log(x), log10(x)
abs(x), signum(x), ceil(x), floor(x), round(x)
min(a, b), max(a, b), hypot(x, y)
random(), random(max)
toRadians(deg), toDegrees(rad)
gcd(a, b), lcm(a, b)
factorial(n), binomial(n, k)
```

### `io` — Input/Output

Java class: `elite.lang.IO`

```elite
print("hello")            // print without newline
println("hello")          // print with newline
printf("x=%d", x)         // formatted output
stdin, stdout, stderr     // standard streams
readFile("path.txt")      // read entire file as string
writeFile("path", text)   // write string to file
```

### `complex` — Complex Numbers

Java class: `elite.lang.Complex`

```elite
Complex(3, 4)             // 3 + 4i
Complex.I                 // imaginary unit
Complex.polar(r, theta)   // from polar coordinates
c.real, c.imag            // components
c.abs(), c.arg()          // magnitude, argument
c + d, c - d, c * d, c / d  // arithmetic
c.conjugate()             // complex conjugate
```

### `rational` — Rational Literals

Enables `a/b` syntax for exact rational numbers:

```elite
require rational
define x = 1/3            // Rational(1, 3)
x + 2/3                   // 1
```

### `measure` — Units of Measure

Java class: `elite.lang.Measures`

```elite
3.5m                      // 3.5 meters
2.0kg                     // 2 kilograms
10s                       // 10 seconds
5m / 2s                   // 2.5 m/s
```

### `matrix` — Matrix Operations

```elite
Matrix([[1, 2], [3, 4]])  // 2×2 matrix
A * B                     // matrix multiplication
A + B                     // matrix addition
A.T                       // transpose
A.det()                   // determinant
A.inv()                   // inverse
```

### `xml` — XML Literals

```elite
require xml
define doc = <root>
    <child attr="value">text</child>
</root>
doc.root.child.@attr      // "value"
doc..child                 // all descendant <child> elements
```

## 3. Built-in Global Functions

| Function | Description |
|----------|------------|
| `typeof(x)` | Return the Java `Class` of `x` |
| `identity(x)` | Return `x` unchanged |
| `assert(cond)` | Throw if `cond` is false |
| `list(xs...)` | Create a list from variadic args |
| `cons(head, tail)` | Create a Cons cell |
| `seq(coll)` | Convert collection to Seq |
| `nil` | Empty Seq (same as `[]`) |

## 4. System Properties

| Property | Values | Default | Description |
|----------|--------|:--:|------|
| `elite.opt.level` | 0-3 | 2 | Optimization level |
| `elite.strict` | true/false | false | Throw on IR/BC fallback |
| `elite.debug` | true/false | false | Print fallback diagnostics |
