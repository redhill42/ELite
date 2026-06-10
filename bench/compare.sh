#!/bin/bash
# ELite vs Python performance comparison
set -e

ELITE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CP="$ELITE_DIR/target/classes:$HOME/.m2/repository/org/ow2/asm/asm/9.9.1/asm-9.9.1.jar:$HOME/.m2/repository/javax/el/javax.el-api/3.0.0/javax.el-api-3.0.0.jar:$HOME/.m2/repository/org/jscience/jscience/4.3.1/jscience-4.3.1.jar:$HOME/.m2/repository/cglib/cglib/3.3.0/cglib-3.3.0.jar"

echo "=== ELite vs Python Performance Comparison ==="
echo

# ===== Python =====
echo "--- Python ---"
PY_N=20
python3 -c "
import time, sys
sys.setrecursionlimit(100000)

def fib_iter(n):
    a,b=0,1
    for i in range(n): a,b=b,a+b
    return b
def sum_loop(n):
    s=0
    for i in range(1,n+1): s+=i
    return s
def nested_loop(n):
    s=0
    for i in range(1,n+1):
        for j in range(1,i+1): s+=j
    return s
def fib_rec(n):
    if n<=1: return n
    return fib_rec(n-1)+fib_rec(n-2)
def fib_tail(n,a,b):
    if n<=0: return a
    return fib_tail(n-1,b,a+b)
def list_bench(n):
    r=[]
    for i in range(1,n+1): r.append(i*i)
    return r
def str_bench(n):
    s=''
    for i in range(1,n+1): s+='E' if i%2==0 else 'O'
    return len(s)
def map_bench(n):
    m={}
    for i in range(1,n+1): m[i]=i*i
    return len(m)

N=$PY_N
tests=[
    ('fib_iter',   lambda: fib_iter(N*5000)),
    ('sum_loop',   lambda: sum_loop(N*100000)),
    ('nested_loop',lambda: nested_loop(N*10)),
    ('fib_rec',    lambda: fib_rec(N)),
    ('fib_tail',   lambda: fib_tail(N*5000,0,1)),
    ('list_bench', lambda: list_bench(N*1000)),
    ('str_bench',  lambda: str_bench(N*5000)),
    ('map_bench',  lambda: map_bench(N*1000)),
]
for name,fn in tests:
    # warmup
    for _ in range(3): fn()
    t0=time.perf_counter()
    for _ in range(10): fn()
    t=(time.perf_counter()-t0)/10*1000
    print(f'  {name:20s} {t:10.3f} ms')
"

echo

# ===== ELite IR Interpreter =====
echo "--- ELite (IR Interpreter) ---"
java -cp "$CP" BCBench 2>&1 | head -20

echo

# ===== ELite Bytecode =====
echo "--- ELite (Bytecode) ---"
java -cp "$CP" -Delite.ir.enabled=true BCBench 2>&1 | head -20
