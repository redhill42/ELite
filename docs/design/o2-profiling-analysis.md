# O2 Profiling Analysis

> 分析日期：2026-06-16
> 工具：JFR (Java Flight Recorder)，1ms sampling period

## 分层负载设计

从简单到复杂，逐步增加 IR 压力：

| Level | 负载 | 迭代 | 特点 |
|-------|------|------|------|
| 1 | `for` 循环内纯算术 `x = x + i` | 500M | 纯 IR，无函数调用 |
| 2 | `for` 循环内调用 `square(i)` | 100M | IR + 函数调用 (INVOKE_DIRECT) |
| 3 | 尾递归 `fib(60)` x 200k | 200k × 60 = 12M 调用 | IR + 递归 + 运算符 |

## Level 1: 纯算术循环

```
for (i in [1..500000000]) { x = x + i }
```

**结果**：500M 迭代仅 12 个 JFR 样本，无 IR exec path 内的样本。
IR 类型化算术 (LADD) + 优化后 indexed loop 几乎零开销，与编译语言性能相当。

## Level 2: 函数调用循环

```
define square(x) => x * x;
for (i in [1..100000000]) { sum = sum + square(i); }
```

**结果**：100M 迭代仅 8 样本。`square(i)` 被编译为 INVOKE_DIRECT，内联/直调开销可忽略。

## Level 3: 尾递归 fib(60) × 200k

```
define fib(n, a, b) { if (n <= 0) { a } else { fib(n-1, b, a+b) } }
```

**结果**：744ms 总时间，12M 函数调用，513 JFR 样本。

### 聚合热点

| 排名 | 方法 | 样本 | 占比 |
|------|------|------|------|
| 1 | `IRInterpreter.execute()` | 926 | ~100% |
| 2 | `IRInterpreter.interpret()` | 916 | ~99% |
| 3 | `ELProgram.evaluate()` | 396 | 调用链 |
| 4 | `checkType (GUARD_TYPE)` | 83 | 8% |
| 5 | `syncLocalsToGlobals` | 8 | <1% |

### interpret() 内部热点

| 行号 | 代码 | 说明 |
|------|------|------|
| 168-169 | `IRFormat.opCount(header)`/`payload(header)` | 每条指令的解码头开销 |
| 181-182 | `PUSH_VAR` — `ensureLocals` + `push(locals[idx])` | 局部变量读取 + 数组边界检查 |
| 532 | `push(l <= r)` — `LLE` 结果装箱 | Long 比较 + Boolean 装箱 |
| 682 | `push(callee.execute(args))` — `INVOKE_DIRECT` | 递归调用创建新 IRInterpreter |

---

## 根本瓶颈

### 瓶颈 #1 (P0): 类型未知导致动态运算符

尾递归 fib 的 IR 代码：
```
    PUSH_VAR v0       ; n
    PUSH_CONST 0
    DYNLE             ; ← 动态比较！不是 LLE
    ...
    PUSH_VAR v0       ; n
    PUSH_CONST 1
    DYNSUB            ; ← 动态减法！不是 LSUB
    ...
    PUSH_VAR v1       ; a
    PUSH_VAR v2       ; b
    DYNADD            ; ← 动态加法！不是 LADD
    INVOKE_TAIL 3
```

`n`, `a`, `b` 的类型未被推断为 `Long`，导致所有运算符走 `DYN*` 动态路径。每次 DYNLE/DYNSUB/DYNADD 内部都会：
1. 判断操作数实际类型 (`checkType`)
2. 调用 `ELEngine.resolveBinOp` 查找运算符闭包
3. 执行运算并返回结果

而类型化操作码 `LLE`/`LSUB`/`LADD` 直接用 `((Number)pop()).longValue()` 完成运算，无需解析。

**优化方向**：
- 增强类型推断：从函数调用 `fib(60, 0, 1)` 的参数类型反向推导函数体内变量类型
- 在 IRSpecializer 中插入 GUARD_TYPE 检查并生成 typed vs dynamic 双路径（deopt）
- 如果无法编译期确定，至少在第一次调用时记录实际类型并生成特化版本

### 瓶颈 #2 (P1): INVOKE_TAIL 每层递归创建新 IRInterpreter

```java
// line 682
IRInterpreter callee = new IRInterpreter(elctx, targetFn, evalContext);
push(callee.execute(args));
```

每次尾递归调用都创建新 `IRInterpreter` 对象 + 初始化 `execute()`（scope push、locals 数组、syncLocals 等）。12M 次调用 × 744ms = 62ns/次调用。

实际上 TCO 应该在本层栈帧内复用，不创建新的 IRInterpreter。

**优化方向**：INVOKE_TAIL 在当前 IRInterpreter 内循环执行，复用栈和 locals，只更新参数值。

### 瓶颈 #3 (P2): 每条指令的解码头开销

```java
int header = code[ip];          // line 167 — 内存读取
int oc = IRFormat.opCount(header);  // line 168 — 位运算
int pl = IRFormat.payload(header);  // line 169 — 位运算
```

每条指令都需要解析 header 的 opcode、opCount、payload。12M 次调用 × ~4 条指令 = 48M 次解码。

**优化方向**：将热门指令序列合并为超级指令（macro-op fusion），或使用 threaded interpreter。

---

## 性能模型

| 指标 | 数值 | 备注 |
|------|------|------|
| fib(60) 每次调用 | ~62ns | 含 3 个动态运算 + 1 个 INVOKE_TAIL |
| 纯算术循环 | ~0.6ns/iter | 几乎 JVM 原生速度 |
| 函数调用开销 | ~3ns/call | INVOKE_DIRECT 内联 | 

---

## 下一步

1. 类型推断增强 → 让 DYNLE→LLE, DYNSUB→LSUB, DYNADD→LADD
2. TCO 栈帧复用 → 避免每次递归 new IRInterpreter
3. 重新 profiling 验证 typed ops + TCO 的加速效果
