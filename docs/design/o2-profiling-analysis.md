# O2 Profiling Analysis

> 分析日期：2026-06-16
> 工具：JFR (Java Flight Recorder)
> 负载：`fib(20)` 递归 x 500次

## 负载代码

```elite
define fib(n) { if (n <= 1) { n } else { fib(n-1) + fib(n-2) } }
for (i in [1..500]) { fib(20); }
```

## 热点排名 (CPU samples)

| 排名 | 方法 | 样本数 | 类别 |
|------|------|--------|------|
| 1 | `HashMap.getNode/put` | 45+ | JVM |
| 2 | `ELNode$Binary.getValue` | 35 | AST 求值 |
| 3 | `ELNode$LAMBDA.invoke` | 29 | AST 求值 |
| 4 | `MethodResolver.resolveMethod` | 29 | 方法解析 |
| 5 | `MethodResolver$MethodMap.get` | 29 | 缓存查询 |
| 6 | `EvalClosure.invoke` | 25 | AST 求值 |
| 7 | `ELEngine.invokeTarget` | 22 | 调用分发 |
| 8 | `GlobalMethodMap.getExpandoMethod` | 19 | Expando 查找 |
| 9 | `ELNode$COND.invokeTail` | 19 | 条件求值 |
| 10 | `ELNode$Binary.invokeOperator` | 19 | 运算符分发 |
| 11 | `MethodResolver.resolveStaticMethod` | 18 | 静态方法解析 |
| 12 | `getStaticMethodClosure` | 18 | 缓存查询 |
| 13 | `ELNode.invokeTail` | 14 | 尾调用 |
| 14 | `EvalClosure.getValue` | 14 | 闭包求值 |
| 15 | `ELNode.getBoolean` | 13 | 布尔转换 |
| 16 | `ELNode$APPLY.getValue` | 13 | 函数调用 |
| 17 | `ELNode$IDENT.getValue` | 12 | 变量解析 |
| 18 | `ELNode$IDENT.invoke` | 11 | 变量调用 |
| 19 | `EvaluationContext.resolveVariable` | 11 | 变量查找 |
| 20 | `DelayEvalClosure.force` | 10 | 延迟求值 |
| 21 | `StackTrace.addFrame` / `removeFrame` | 8 | 栈帧管理 |

## 瓶颈分析

### 1. AST 求值路径占比过高 (瓶颈 #1)

`fib(20)` 是一个递归 lambda，其函数体的 `<=`、`+`、`-` 等运算符全部走 AST 的 `Binary.invokeOperator` → `MethodResolver` → `invokeTarget` 路径，没有用到 IR 的类型化算术运算（IADD/ISUB/IEQ）。

**根因**：lambda 内部的函数调用 `fib(n-1)` 触发了 `IDENT.invoke` → `EvalClosure.invoke` → `Lambda.invoke` 这整条 AST 调用链。lambda 的递归调用没有用 `INVOKE_DIRECT` 是因为 `fn` 不在 `knownFunctions` 中（可能是捕获了自身引用的问题）。

**优化方向**：
- 确保递归 lambda 的 self-call 走 INOVKE_DIRECT 或至少走 INVOKE_DYN
- 将函数体中的 `<=`、`+`、`-` 编译为类型化 IR 操作码而非 AST 回退

### 2. MethodResolver 开销 (瓶颈 #2)

每次 `Binary.invokeOperator` 都涉及：
- `MethodResolver.getInstance(elctx)` — `ELContext.getContext(Class)` → `HashMap.get`
- `resolveStaticMethod` — `MethodMap.get` → `HashMap.get`  
- `resolveMethod` — `getMethodClosure` → `ExpandoMethodMap.get` → `HashMap.get`

对于基本类型（int/long），运算符语义是确定的，不需要运行时方法解析。

**优化方向**：
- 类型特化 pass 识别基本数值类型，直接映射到 IADD/ISUB/IEQ 等类型化操作码
- 避免动态类型时需要的方法查找

### 3. 帧管理开销 (瓶颈 #3)

`StackTrace.addFrame` / `removeFrame` 每次函数调用都会触发：
- `ELContext.getContext(StackTrace.class)` — HashMap 查找
- `setCurrentELContext` / 恢复 — ThreadLocal 读写
- 帧对象分配

**优化方向**：
- IR 直调路径（已知函数）可跳过帧管理
- 将帧管理降级为 debug 模式选项

### 4. Expando 方法查找 (瓶颈 #4)

`GlobalMethodMap.getExpandoMethod` 每次运算符解析都要走一遍全局 expando 方法查找（HashMap 遍历），属于固定开销。

**优化方向**：
- 编译期解析已知运算符绑定，消除运行时查找

---

## Benchmark 数据 (10000 次迭代)

| 操作 | ops/s | ns/op | 备注 |
|------|-------|-------|------|
| `[1,2,3]` list literal | 1,185,203 | 843.7 | |
| `map literal` | 834,585 | 1,198.2 | |
| `list index access` | 437,706 | 2,284.6 | 2.7x slower than literal |

List index access 比 list literal 慢近 3 倍，说明 `LOAD_PROPERTY` 路径有优化空间。

---

## 优先级

**P0 — 应首先优化**：
1. 递归/闭包 lambda 的 IR 直调路径（避免 `Lambda.invoke` AST 回退）
2. 基本数值类型的运算符直接编译为类型化操作码

**P1 — 显著收益**：
3. MethodResolver 运算符查找暖启动优化（缓存命中率）
4. `list index access` 的 LOAD_PROPERTY 优化

**P2 — 渐进优化**：
5. 帧管理开销降低
6. Expando 查找提前绑定
