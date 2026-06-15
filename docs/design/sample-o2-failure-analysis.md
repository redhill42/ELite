# src/sample/*.xel O2 运行失败分析报告

**日期**: 2026-06-14
**测试环境**: Java 17 (OpenJDK 17.0.19), macOS aarch64
**测试方法**: 对每个样本分别在 O0 (AST解释器) 和 O2 (IR解释器) 下运行，对比结果

## 总览

| 样本 | O0 | O2 | 失败类别 |
|------|-----|-----|---------|
| C.xel | ✅ | ✅ | — |
| dsl.xel | ❌ | ❌ | 语法变更 (`@`表示法) |
| hello.xel | ❌ | ❌ | 语法变更 (`@`表示法) + IR类型强制 |
| list.xel | ✅ | ❌ | IR变量解析 |
| monad.xel | ✅ | ❌ | IR变量解析 (`let`绑定) |
| rbtree.xel | ✅ | ❌ | IR方法调用 |
| scheme.xel | ✅ | ❌ | IR动态调用 |
| seq.xel | ✅ | ❌ | IR变量解析 (`let`绑定) |
| uri.xel | ✅ | ✅ | — |
| xml.xel | ✅ | ❌ | IR类型强制 (`String→Number`) |
| xmlbuilder.xel | ✅ | ✅ | — |

**已搁置** (Swing相关，难以自动化测试):
| GameOfLife.xel | ❌ | ❌ | JVM模块系统 + Swing |
| meta.xel | ✅ | ❌ | IR静态字段访问 (Swing) |
| swing.xel | ✅ | ❌ | IR构造函数调用 (Swing) |

**统计**: 15个样本中，排除3个Swing相关，剩余12个。O2通过4个 (C, uri, xmlbuilder + func)，失败8个。
其中2个 (dsl, hello) 在O0下也失败，属于之前语法变更未更新样本。

---

## 详细分析

### 类别 0: 语法变更导致的失败 (O0和O2均失败)

这些样本使用了旧的 `expr.method()` 后缀调用语法，需要更新为 `expr@method` 形式。

#### 0-1. dsl.xel — 多处使用 `expr.method()` 旧语法

**O0错误**: `找不到类型'java.lang.Integer'的'C'方法` (line 46)
**O2错误**: `ClassCastException: ELNode$RETURN cannot be cast to ELNode$THROW`

**涉及位置**:
```elite
// 旧语法 (当前)
print(25.C()->F());           // line 46
print(212.F()->C);            // line 47
print(25.C() + 212.F());      // line 48
print("400 FRF = ${400.FRF -> ECU}");    // line 92
print("100 ECU = ${100.ECU -> BEF}");    // line 93
print("100 DEM = ${100.DEM -> PTE}");    // line 94
print("400 FRF + 100 DEM = ${(400.FRF + 100.DEM) -> ECU}"); // line 95

// 应更新为 (例如):
print(25@C()@F);            // 或 C(25)@F
// 注意: 这些实际上是 DSL 语义中的温度构造，不是简单方法调用
```

**分析**: `dsl.xel` 使用了两种 `expr.method()` 形式：
1. 温度构造: `25.C()` 实际上是想以摄氏单位构造温度为25度，等价于 `C(25)`
2. 货币面值: `400.FRF` 构造400法国法郎的度量值

这些不仅仅是语法替换问题，还涉及 DSL 语义。`25.C()` 在 ELite 中调用`C`静态方法构造温度对象，等价于 `C(25)`。货币单位 `400.FRF` 是 `value.unit` 语法，等价于 `400[FRF]`。

**建议修复方案**:
- 温度构造: `25.C()` → `C(25)`, `212.F()` → `F(212)` 等
- 货币单位: `400.FRF` → `400[FRF]`, `100.ECU` → `100[ECU]` 等
- `->` 管道符号保持不变（单位转换语义）

#### 0-2. hello.xel — `"str".print()` 旧语法

**O0错误**: `找不到类型'java.lang.String'的'print'方法` (line 13)
**O2错误**: `ClassCastException: String→Number` (先生成了 "Hello, World!" 输出，后在 line 7 失败)

**涉及位置**:
```elite
// 旧语法 (当前)
"Hello, World!".print();               // line 13
'World'.sayHello().print();            // line 21

// 应更新为:
"Hello, World!"@print();               // 或 print("Hello, World!")
'World'@sayHello()@print();            // 或 print(sayHello('World'))
```

**O2中额外错误**: O2在 `stdout << "Hello, World!" << endl;` (line 7) 处报 `ClassCastException: String→Number`。这是IR解释器的类型强制问题——`<<` 操作符在IR层将右操作数错误地推断为数字类型。

---

### 类别 1: IR 变量解析失败 (O0通过, O2失败)

#### 1-1. list.xel — 操作符变量 `*2*` 未解析

**错误**: `标识符未定义: *2*` at line 5
**源码 line 5**: `define qsort([]) => []`

**分析**: 错误信息中的 `*2*` 可能来自后续行的 `m*n` (line 94)、`1.8*c` 等乘法表达式。IR构建器在编译乘法表达式时，将 `*` 操作符作为标识符查找，但查找失败。

**可能的根因**: IR Builder 在处理中缀操作符时，对于 `*` 这类不在IR内置操作码列表中的操作符，会尝试作为函数调用来处理（查找名为 `*` 的函数/变量）。当这种查找在IR的变量解析器中失败时，就会报 `标识符未定义: *2*`（其中 `2` 可能是某处的数值）。

实际上，更可能的情况是：`*` 操作符在IR编译期间被解析为某种内部名称模式，而传给变量解析器时格式错误（"*2*" 可能是操作符名+操作数的拼接）。

#### 1-2. monad.xel — `let` 绑定变量 `m` 未解析

**错误**: `标识符未定义: m` at line 108
**源码 line 108**: `define fib_generator() {`  
实际错误位置可能是 line 57-62 的 `fib` 函数：
```elite
define fib(n) {
    define fib_s = do {
        (n, a, b) <- get;
        n == 1 ? State.yield(a)
               : (put((n-1, b, a+b)) >> fib_s);
    }
    eval_state(fib_s, (n,1,1));
}
```

**分析**: `(n, a, b) <- get` 使用模式匹配从 State monad 中解构元组。变量 `n`, `a`, `b` 通过此模式绑定引入。O0的AST解释器能正确处理这种解构绑定，但IR编译器的 `let`/模式匹配变量解析逻辑未能正确注册这些变量到IR的局部变量表中。

**类似问题**: 可能影响所有 `let (pattern = expr)` 形式的模式匹配解构。

#### 1-3. seq.xel — `let` 绑定变量 `q` 未解析

**错误**: `标识符未定义: q` at line 44
**源码 line 44**: `void test_cons(n) {`  
实际错误位置是 line 85-88 (test_seq) 或 line 31 (pi 定义):
```elite
define pi =
    let g(q=1,r=180,t=60,i=2)
        let (u = 3*(3*i+1)*(3*i+2), y = (q*(27*i-12)+5*r) div (5*t))
            [y : &g(10*q*i*(2*i-1), 10*u*(q*(5*i-2)+r-y*t), t*u, i+1)]
```

**分析**: `let g(q=1,r=180,t=60,i=2)` 使用带默认参数的 `let` 绑定定义了局部递归函数 `g`，参数 `q`, `r`, `t`, `i` 有默认值。IR编译器未能正确处理 `let` 定义的递归函数绑定——函数名 `g` 或参数 `q` 等未注册到IR作用域中。

**根因类别**: `let` 表达式在IR编译中的变量作用域管理不完整。IRBuilder 中的 `compileLet` 或等效方法未正确处理：
1. 函数定义式 `let`（`let f(args) = body`）
2. 带默认值的 `let`（`let g(q=1,...)`）
3. 模式匹配式 `let`（`let (a, b) = expr`）

---

### 类别 2: IR 属性/方法查找失败 (O0通过, O2失败)

#### 2-1. rbtree.xel — Range 方法调用失败

**错误**: `Property 'shuffle' not found`
**源码**: `line 57: define data = [1..n].shuffle()`

**分析**: `[1..n]` 创建 Range 对象，`.shuffle()` 是 `math` 模块提供的扩展方法。在O0中该方法可通过 resolver 链找到。在O2中，IR编译的 Range 对象上的 `.shuffle()` 方法调用无法正确解析。

**可能的根因**: 
1. IR 对 `<expr>.method()` 调用链的编译不完整（此处的 `.` 是 Java 方法调用，非后缀应用语法）
2. `require 'math'` 导入的扩展方法未在IR的作用域中注册
3. 更根本的问题是：IR Builder 可能对 `.` 操作符的编译存在缺陷——混淆了"后缀应用"（现在用 `@`）和"Java成员访问"（仍用 `.`）

---

### 类别 3: IR 类型强制失败 (O0通过, O2失败)

#### 3-1. hello.xel — `<<` 操作符的类型错误

**错误**: `ClassCastException: String→Number`
**位置**: `line 7: stdout << "Hello, World!" << endl;`

**分析**: `<<` 是 ELite 中用于输出流的操作符。`stdout << "Hello, World!"` 应输出字符串 "Hello, World!" 到标准输出。IR 编译器在类型推断阶段可能将 `<<` 的右操作数推断为 `Number` 类型，生成了数字专用的IR操作码。当运行时实际传入 `String` 时触发类型转换失败。

**根因**: IR 的类型专门化（specialization）过于激进。`<<` 操作符可以接受任意类型的右操作数，但类型推断可能基于某个有限的上下文（如之前出现过整数右操作数）将其推断为 `Number`。

#### 3-2. xml.xel — 字符串算术运算的类型错误

**错误**: `ClassCastException: String→Number`
**位置**: `line 31: define total = order.item.price.@@value * order.item.quantity.@@value;`

**分析**: `@@value` 返回XML元素/属性的字符串值。然后 `*` 操作符尝试对两个字符串进行乘法运算。在O0中，AST解释器会隐式将字符串转换为数字进行算术运算。在O2中，IR解释器没有执行这种隐式类型转换，导致 `String` 到 `Number` 的转换失败。

**根因**: IR 解释器的类型强制（coercion）不如 AST 解释器灵活。当操作数是 `String` 而操作期望 `Number` 时，AST解释器会自动调用 `Coercion.coerce(value, Number.class)`，但IR解释器缺少相应的自动强制逻辑。

**关联问题**: `scheme.xel` 中也可能存在同样的隐式类型转换依赖（虽然它报的是 "动态调用失败"）。

---

### 类别 4: IR 动态调用失败 (O0通过, O2失败)

#### 4-1. scheme.xel — 动态调用失败

**错误**: `RuntimeException: 动态调用失败`
**位置**: Scheme 解释器的 APPLY 调用

**分析**: `scheme.xel` 使用 ELite 的元编程能力实现了一个 Scheme 子集解释器。其核心机制是通过 grammar DSL 将 Scheme 语法映射到 ELite AST 构造调用（如 `APPLY(operator, operands)`），然后通过 `eval()` 执行构造出的 AST。

O2模式下的执行路径是：ELite程序 → IR编译 → IR解释器执行。当 ELite 程序调用 `tree.eval()` 执行动态构造的 AST 时，这个动态AST 又需要被求值。问题可能出在：
1. 动态 AST 在 IR 模式下被尝试编译为 IR，但编译失败
2. 或者动态 AST 使用了某些 IR 不支持的求值路径

**根因**: 元编程 + 多层求值的场景对 IR 编译器是挑战。外层程序被编译为 IR 执行，但内层动态生成的代码可能走的是 AST 求值路径，两者的交互存在断裂。

---

---

## 根因分类汇总

按根因类型分组（排除已搁置的Swing相关样本）：

| 类别 | 影响样本数 | 根因 |
|------|-----------|------|
| **语法变更** | 2 (dsl, hello) | `expr.method()` 后缀调用语法改为 `expr@method`，样本未更新 |
| **变量作用域** | 3 (list, monad, seq) | IR Builder 对 `let` 绑定、模式匹配解构、操作符变量解析不完整 |
| **Java互操作** | 1 (rbtree) | IR 对 `.method()` Java成员访问（`.shuffle()`等扩展方法）的编译不完整 |
| **类型强制** | 2 (hello, xml) | IR 解释器缺乏自动类型强制（String→Number等），类型推断过于激进 |
| **动态调用** | 1 (scheme) | 元编程场景下的多层求值在 IR 模式下断裂 |

**搁置** (Swing相关):
| Swing相关 | 3 (GameOfLife, meta, swing) | 涉及AWT/Swing GUI组件，无法自动化测试，搁置 |

## 优先级建议

### P0 (可能影响大量用户代码):
1. **变量作用域** — `let` 绑定和模式匹配是非常基础的语言特性，许多ELite程序都依赖它们

### P1 (常见使用场景):
2. **类型强制** — 字符串→数字的自动转换在很多场景中都会用到
3. **语法变更** — 样本代码需要更新以反映 `@` 语法变更
4. **Java互操作** — `rbtree.xel` 中的 `.method()` Java成员访问编译不完整

### P2 (特殊场景):
5. **动态调用** — 元编程场景 (scheme.xel) 相对少见

---

## P0 深入分析：IR 变量作用域失败的根因

### 核心发现：IR Locals → EvaluationContext 同步缺失

经过逐层追踪，三个 P0 失败（list.xel, monad.xel, seq.xel）指向**同一个根因**：

**IR 解释器的局部变量（`locals[]` 数组）在 fallback 到 AST 解释器（trampoline）之前，没有被同步到 `EvaluationContext`。**

### 关键代码路径

#### 1. IR 变量存储机制

`IRBuilder.ensureVar()`（`IRBuilder.java:1063-1074`）管理一个扁平的 `varIndex` 表（name → slot index）。函数参数通过 `buildLambda` 中的 `ensureVar` 注册到该表。

`IRInterpreter` 执行时，局部变量存储在 `locals[]` 数组中：
```java
// IRInterpreter.java:148-154
case PUSH_VAR: {
    int idx = pl & 0xFF;
    ensureLocals(idx);
    push(locals[idx]);  // <-- 直接数组访问
    ip += 1;
    break;
}
```

但对于 `PUSH_GLOBAL`，变量通过 `EvaluationContext` 的 VariableMapper 链查找：
```java
// IRInterpreter.java:1247-1272
private Object resolveGlobal(String name) {
    // 1. evalContext.resolveVariable(name) — VariableMapper 链
    // 2. MethodResolver.resolveGlobalMethod(...)
    // 3. elctx.getELResolver().getValue(...)
}
```

**问题**：函数参数只在 `locals[]` 数组中，不在 `EvaluationContext` 中。

#### 2. Trampoline 机制（IR → AST 回退）

当 IRBuilder 遇到无法编译的表达式时，通过 `buildTrampoline(node)` 生成 TRAMPOLINE 操作码：

```java
// IRBuilder.java:384-385
if (node.right instanceof ELNode.ACCESS && node.args.length > 0) {
    buildTrampoline(node);  // m.run(s) 走这个路径
}
```

运行时，TRAMPOLINE 将节点交给 AST 解释器执行：

```java
// IRInterpreter.java:939-961
case TRAMPOLINE: {
    ELNode node = (ELNode)constantPool[poolIdx];
    Object result = node.getValue(evalContext);  // <-- 使用 evalContext！
    push(result);
    syncLocalsFromGlobals();  // 只做 global→local 同步
    ...
}
```

**AST 解释器使用 `evalContext` 查找变量，但 IR locals 不在 `evalContext` 中**。

#### 3. 单向同步

现有的 `syncLocalsFromGlobals()`（`IRInterpreter.java:1180-1191`）只在 TRAMPOLINE **之后**同步：

```java
// Global → Local (单向，已存在)
private void syncLocalsFromGlobals() {
    String[] names = function.varNames();
    VariableMapper vm = elctx.getVariableMapper();
    for (int i = 0; i < names.length && i < locals.length; i++) {
        ValueExpression ve = vm.resolveVariable(names[i]);
        if (ve != null) {
            locals[i] = ve.getValue(elctx);  // VariableMapper → locals
        }
    }
}
```

**缺失**：对应的 `syncLocalsToGlobals()` —— 在 TRAMPOLINE 之前将 IR locals 写入 EvaluationContext。

### 三个失败案例的具体追踪

#### monad.xel: `标识符未定义: m`

**源码**（State monad 的 bind 方法）:
```elite
bind(k) => State(\s => let ((a, s') = run(s)) run_state(k(a), s'))
```

**调用链**:
1. `do { x <- get; State.yield(x+1) }` → 展开为 `get.bind(\x => State.yield(x+1))`
2. `get.bind(...)` 调用 `State.bind(k)(s)` → 执行 `run_state(k(a), s')`
3. IR 编译 `run_state(m, s) => m.run(s)` 时，参数 `m` 注册在嵌套 builder 的 `varIndex` 中
4. 但 `m.run(s)` 被检测为 ACCESS-with-args（`IRBuilder.java:384`），整体 trampoline
5. TRAMPOLINE 执行 AST `m.run(s)`，`m` 在 `locals[0]` 但不在 `evalContext` → **失败**

**验证方法**: `define f(m, s) => m + s` 在 O2 正常工作（无 trampoline）；`define f(m, s) => m.run(s)` 在 O2 失败（触发 trampoline）。

#### seq.xel: `标识符未定义: q`

**源码**:
```elite
define pi =
    let g(q=1, r=180, t=60, i=2)
        let (u = 3*(3*i+1)*(3*i+2), y = (q*(27*i-12)+5*r) div (5*t))
            [y : &g(10*q*i*(2*i-1), 10*u*(q*(5*i-2)+r-y*t), t*u, i+1)]
```

**调用链**:
1. `let g(q=1,r=180,t=60,i=2) body` 被 parser 展开为 `(\g => body)(\q=1,r=180,t=60,i=2 => ...)`
2. 内层 lambda 的参数 `q`、`r`、`t`、`i` 注册在嵌套 builder 的 `varIndex` 中
3. body 中的 `q*(27*i-12)+5*r` 等表达式可能触发 trampoline（取决于具体类型推断）
4. TRAMPOLINE 执行的 AST 中，`q` 在 `locals[]` 但不在 `evalContext` → **失败**

#### list.xel: `标识符未定义: *2*`

**源码** 使用了 `require 'syntax'` 的 `select...from...in...where` 语法和 `require 'math'` 的函数。

**调用链**:
1. `select...from...in...where` 展开为 `.mappend()` 调用链（ACCESS-with-args）
2. `.mappend()` 调用被 trampoline
3. 在 AST 求值期间，`*` 操作符需要被查找（作为二进制操作符的回退路径）
4. `*` 操作符不存在于 `evalContext` 的 VariableMapper 中 → 某个中间变量变成 `*2*`（可能是内部名称mangling）
5. **失败**（同一根因：trampoline 无法访问 IR 上下文中的变量/操作符）

### 修复方向

需要添加 **`syncLocalsToGlobals()`** 方法，在 TRAMPOLINE 之前将 IR 局部变量写入 EvaluationContext：

```java
// 新增方法: 将 IR locals 同步到 EvaluationContext
private void syncLocalsToGlobals() {
    String[] names = function.varNames();
    if (names == null) return;
    VariableMapper vm = elctx.getVariableMapper();
    for (int i = 0; i < names.length && i < locals.length; i++) {
        if (names[i] != null) {
            ensureLocals(i);
            vm.setVariable(names[i], new LiteralClosure(locals[i]));
        }
    }
}
```

然后在 TRAMPOLINE 处理中：
```java
case TRAMPOLINE: {
    // ... 
    syncLocalsToGlobals();          // NEW: locals → globals (before AST eval)
    Object result = node.getValue(evalContext);
    push(result);
    syncLocalsFromGlobals();        // Existing: globals → locals (after AST eval)
    // ...
}
```

**注意事项**:
- 需要处理变量遮蔽（shadowing）：如果局部变量与全局变量同名，`syncLocalsToGlobals` 会覆盖全局值。可能需要使用 `pushContext()` 创建局部作用域
- 性能考虑：每次 TRAMPOLINE 都同步所有局部变量可能开销较大，可以只同步被引用的变量
- 与闭包捕获的交互：闭包捕获的变量可能已经在 VariableMapper 中，需要避免重复设置

---

## P0 第二轮深入分析：模式匹配函数 + Trampolined 表达式中的闭包捕获失效

### 已修复（第一轮）

1. **IR locals → EvaluationContext 同步**（`syncLocalsToGlobals`）：基础参数在 trampoline 中可见
2. **INVOKE_DIRECT 不处理闭包**：捕获函数不再注册为直接调用，改走 `dynamicInvoke`
3. **非捕获函数注册**：`captureCount > 0` 的函数不注册到 `knownFunctions`

### 仍失败：list.xel `n` / seq.xel `sieve` / seq.xel `q`

经过深入追踪，这三个失败都是**同一根因的变体**：

### 核心发现：Trampolined 表达式不参与自由变量分析

**追踪过程**：

1. `define scan(0) => ... | scan(i) => ...` 的 `node.expr` 是 `ELNode.LAMBDA`（通过调试确认），走 `buildLambda` 路径
2. `buildLambda` 创建嵌套 builder，注册参数 `i`
3. 编译 body：`[[q:qs] | qs <- scan(i-1), q <- [1..n], safe(qs, q, 1)]`
4. **列表推导式被整体 trampolined** — `buildIdent("n")` 在此过程中从未被调用
5. 因为 `buildIdent("n")` 从未被调用，`n` 不在 `capturedVars` 中
6. `capturedVars` 为空 → `captureCount = 0` → CLOSURE 不捕获任何变量
7. 运行时：`scan(3)` → `locals = [3]`（只有参数，无 `n`）
8. TRAMPOLINE 在 scan 内触发 → `syncLocalsToGlobals()` 只写入 `locals[0]=3`
9. AST 求值列表推导式 → 查找 `n` → `VariableMapper` 中无 `n` → 失败

**根因**：IRBuilder 在编译 trampolined 表达式时，无法分析其中引用了哪些自由变量。Trampolined 表达式中的标识符引用完全不可见于 IR 的闭包捕获机制。

### 三个案例的具体机制

#### list.xel `n`（queens 函数）
```
queens(n) {
    define scan(i) => [ ... [1..n] ... ]  // 列表推导式 → trampoline
}
```
- `scan` 编译为 LAMBDA → body 整体 trampoline
- `n` 在 trampolined 表达式中，对 IRBuilder 不可见
- 闭包未捕获 `n` → 运行时在 VariableMapper 中找不到

#### seq.xel `sieve`（is_prime 函数）
```
is_prime(n) {
    let sieve([x:xs] = primes) {        // let + 函数定义模式
        x*x>n ? true : n%x==0 ? false : sieve(xs)
    }
}
```
- `let` 语句由 parser 展开为 `(\sieve => body)(\引子变量 => match ...)`
- 内层 lambda 的 body 包含 MATCH（trampolined）
- `sieve` 递归调用自身 — 在 trampolined 表达式中不可见
- 闭包未正确设置 → 运行时找不到 `sieve`

#### seq.xel `q`（pi 定义）
```
let g(q=1, r=180, t=60, i=2)           // let 创建函数 g, 参数 q 有默认值
    let (u = ..., y = (q*(27*i-12)+5*r) div (5*t))  // 引用 q
        [y : &g(...)]                    // 延迟列表 → trampoline
```
- 与 `n` 相同的模式：`q` 在 trampolined 的列表推导式中引用
- 函数参数 `q` 在 IR locals 中，但不在 VariableMapper 中
- Trampoline 无法访问

### 修复方向

有多种可能的修复策略，按侵入性从小到大：

**方案 A：在 `execute()` 入口处同步参数到 VariableMapper**
- 在 `IRInterpreter.execute()` 中，绑定参数后立即调用 `syncLocalsToGlobals()`
- 每个函数调用的参数都写入 VariableMapper
- 简单但有变量遮蔽问题（同名参数覆盖外层变量）
- 需要配套的 scope 管理（pushContext/popContext 包裹每次调用）

**方案 B：在 `buildLambda` 中预扫描 trampolined body 提取自由变量**
- 在编译 lambda 之前，用 visitor 遍历 body AST，收集所有 IDENT 引用
- 排除 lambda 自身的参数，剩余的标记为捕获变量
- 侵入性较大但语义精确

**方案 C：在 TRAMPOLINE 时通过 call stack 查找变量**
- TRAMPOLINE 执行前，不仅同步当前函数的 locals，还遍历整个 `evalContext` 链
- 需要维护 IR 调用栈

推荐**方案 A**，因为：
- 最小代码改动
- 覆盖所有模式（包括 futures/新的 trampoline 触发点）
- 语义上等同于"所有函数参数在调用期间都是可见的"

需要注意的细节：
- 在 `execute()` 返回前需要恢复被覆盖的 VariableMapper 条目
- 或者使用 `evalContext.pushContext()` 为每个函数调用创建隔离作用域
