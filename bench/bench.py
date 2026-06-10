#!/usr/bin/env python3
"""ELite vs Python performance comparison benchmark."""

import time
import sys

# ============ 1. Fibonacci iterative ============
def fib_iter(n):
    a, b = 0, 1
    for i in range(n):
        a, b = b, a + b
    return b

# ============ 2. Sum loop ============
def sum_loop(n):
    s = 0
    for i in range(1, n + 1):
        s += i
    return s

# ============ 3. Nested loop ============
def nested_loop(n):
    s = 0
    for i in range(1, n + 1):
        for j in range(1, i + 1):
            s += j
    return s

# ============ 4. Recursive fibonacci (non-tail) ============
def fib_rec(n):
    if n <= 1:
        return n
    return fib_rec(n - 1) + fib_rec(n - 2)

# ============ 5. Tail-recursive fibonacci ============
def fib_tail(n, a=0, b=1):
    if n <= 0:
        return a
    return fib_tail(n - 1, b, a + b)

# ============ 6. List operations ============
def list_bench(n):
    result = []
    for i in range(1, n + 1):
        result.append(i * i)
    return result

# ============ 7. String concatenation ============
def str_bench(n):
    s = ""
    for i in range(1, n + 1):
        s += "E" if i % 2 == 0 else "O"
    return len(s)

# ============ 8. Dict operations ============
def map_bench(n):
    m = {}
    for i in range(1, n + 1):
        m[i] = i * i
    return len(m)


def benchmark(label, fn, *args, warmup=1, iters=5):
    # Warmup
    for _ in range(warmup):
        fn(*args)
    # Measure
    start = time.perf_counter()
    for _ in range(iters):
        fn(*args)
    elapsed = time.perf_counter() - start
    avg_ms = (elapsed / iters) * 1000
    print(f"  {label:20s} {avg_ms:10.3f} ms")
    return elapsed


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    sys.setrecursionlimit(100000)

    print(f"=== Python Benchmark (n={n}) ===")

    benchmark("fib_iter",          lambda: fib_iter(n * 1000))
    benchmark("sum_loop",          lambda: sum_loop(n * 100000))
    benchmark("nested_loop",       lambda: nested_loop(n * 10))
    benchmark("fib_rec",           lambda: fib_rec(n))
    benchmark("fib_tail (x100)",   lambda: fib_tail(n * 100))  # Python no TCO
    benchmark("list_bench",        lambda: list_bench(n * 1000))
    benchmark("str_bench",         lambda: str_bench(n * 10000))
    benchmark("map_bench",         lambda: map_bench(n * 1000))
