# IR Trampoline 场景分析

> 分析日期：2026-06-16
> IR 版本：当前 master

## 概述

IRBuilder 对无法在编译期正确处理或存在已知 bug 的 AST 节点，通过 `TRAMPOLINE` 操作码回退到 AST 解释执行。每个 trampoline 表示 IR 覆盖率的缺口。消除 trampoline 可直接提升 O2/O3 性能。

`buildTrampoline(ELNode node)` 将整个 AST 节点存入常量池，运行时由 `IRInterpreter` 的 TRAMPOLINE handler 调用 AST `node.getValue(context)` 完成求值。

## 完整清单

### 一、方法调用类（高频）

| # | 行号 | 场景 | 触发条件 | 示例 |
|---|---|---|---|---|
| 1 | 473 | **ACCESS + args > 0** | 任何带参数的方法调用 | `obj.method(arg)` |
| 2 | 479 | **0-arg ACCESS** | 零参方法调用 | `tree.eval()`, `list.size()` |
| 3 | 463 | **重载方法歧义** | 多个同名方法，编译期无法确定唯一重载 | 泛型重载方法 |

**根因**：IR 的 `LOAD_PROPERTY` 将 base 对象替换为 MethodClosure，丢失了实例方法的 target。`JavaMethodClosure.invoke(elctx, args[])` 期望 `args[0]` 为 target 对象，但 `INVOKE_DYN` 将 target 单独弹出栈顶。

**影响**：几乎所有的面向对象风格方法调用（`obj.method(...)`）都被 trampoline，是覆盖率最大的缺口。

---

### 二、控制流类

| # | 行号 | 场景 | 触发条件 | 示例 |
|---|---|---|---|---|
| 4 | 983 | **for-in (iterator)** | 非整数 `Range` 的遍历 | `for (x in mySeq)` |
| 5 | 406 | **list comprehension/XFORM** | 推导式和 transform 表达式 | `[expr \| x <- list]` |

**根因（#4）**：`ITER_NEXT`/`ITER_DONE` 与 `STORE_VAR` 的栈顺序问题。`ITER_DONE` 先弹出值再检查 null，而 `STORE_VAR` 也需要该值，导致第二次迭代时栈损坏（ClassCastException）。简单整数范围走优化路径（`buildOptimizedRangeFor`）不受影响。

**根因（#5）**：推导式内部的延迟求值和作用域绑定逻辑复杂，AST 已有成熟实现。

---

### 三、运算符/表达式类

| # | 行号 | 场景 | 触发条件 | 示例 |
|---|---|---|---|---|
| 6 | 586 | **自定义类型二元运算** | 非基本数值类型的运算符 | Units of Measure |
| 7 | 594 | **移位运算符（类型未知）** | `<<`, `>>`, `>>>` 操作数类型不确定 | stream I/O vs bit shift |
| 8 | 634 | **复杂赋值目标** | 非本地/非简单变量的赋值 | 计算后的属性赋值 |
| 9 | 644 | **非基本类型一元运算** | 自定义类型的 `-expr`, `+expr` | 自定义 neg/pos |
| 10 | 657, 659 | **复杂 cat 拼接** | 非二元 `~` 操作（含自定义类型） | 多段字符串拼接 |
| 11 | 304 | **复杂 ACCESS key** | 非简单标识符/数字/字符串的键访问 | 计算表达式作 key |

**根因（#6）**：自定义类型可能通过闭包重载运算符，编译期仅能识别基本数值类型，无法判断自定义类型的运算符语义。

**根因（#7）**：`<<` 在 ELite 中既可用于 bit shift 也可用于 stream 输出重定向，编译期无法区分。

---

### 四、数据结构类

| # | 行号 | 场景 | 触发条件 | 示例 |
|---|---|---|---|---|
| 12 | 496 | **惰性序列 / dotted-pair Cons** | `delay=true` 或非 NIL/非 CONS 尾部 | 惰性列表构造 |
| 13 | 241 | **Array 表达式** | Java 数组创建 | `new String[10]` |

**根因（#12）**：惰性序列需要 `DelayCons`/`DelaySeq` 包装，IR 的 `NEW_LIST` 只能处理立即求值的元素。

---

### 五、定义/声明类

| # | 行号 | 场景 | 触发条件 | 示例 |
|---|---|---|---|---|
| 14 | 866 | **@data CLASSDEF** | `@data` 标注的类定义 | `@data class Point(x, y)` |
| 15 | 894 | **CLASS import 解析失败** | 编译期类名解析失败的回退 | 动态加载的类 |
| 16 | 815 | **复杂 DEFINE** | 未知结构的 define 节点 | 递归 define |

**根因（#14）**：`@data` 类的构造器使用 `&tail` 延迟字段（`EvalClosure` 包装）。IR 编译期无法生成等价的惰性求值代码，会立即求值导致无限递归。

**根因（#15）**：编译期 `Class.forName()` 失败时回退到 TRAMPOLINE。正常情况下不应触发（imports 全部在编译期可解析）。

---

### 六、模式匹配/特殊语法类

| # | 行号 | 场景 | 触发条件 | 示例 |
|---|---|---|---|---|
| 17 | 170 | **CONST_MATCH** | 常量模式匹配 | `let 42 = x` |
| 18 | 171 | **MATCH** | 值模式匹配 | `let [a, b] = list` |
| 19 | 414 | **@data 构造函数调用** | 手动调用 @data 构造器 | `Point(3, 4)` 其中 Point 是 @data |

**根因（#19）**：同 #14 — data 构造器有 `&tail` 延迟参数，需要 `EvalClosure` 包装。

---

### 七、兜底/未知类

| # | 行号 | 场景 | 说明 |
|---|---|---|---|
| 20 | 222 | **未知 EXPR 子类型** | 非 `ELNode.EXPR` 的 EXPR token |
| 21 | 225 | **未知 FOR 子类型** | 非 `ELNode.FOR` 的 FOR token |
| 22 | 245 | **default** | switch 中未匹配的 token 类型 |
| 23 | 583, 698, 771 | **非 Binary 节点** | 二元运算符但节点非 Binary（理论不应发生） |

---

## 优先级排布

```
P0 — 应首先消除（覆盖面大，性能影响显著）
├─ #1: ACCESS + args > 0 (方法调用)
├─ #2: 0-arg ACCESS (零参方法调用)
└─ #4: for-in iterator (通用迭代器)

P1 — 常见场景，中等影响
├─ #6: 自定义类型二元运算
├─ #8: 复杂赋值目标
├─ #9: 非基本类型一元运算
└─ #5: list comprehension/XFORM

P2 — 已有局部优化或回退合理
├─ #3: 重载方法歧义
├─ #12: 惰性序列
└─ #14: @data CLASSDEF

P3 — 罕见或纯兜底
├─ #17, #18: 模式匹配
├─ #7: 移位运算符（罕见）
└─ #10, #11, #13, #15, #16, #20-23: 边缘/兜底场景
```

---

## 关键设计约束

消除 trampoline 需要解决的核心问题：

1. **方法调用的 target 传递**（#1, #2）
   - `LOAD_PROPERTY` 替换 base 为 MethodClosure
   - `INVOKE_DYN` 将 MethodClosure 作为 target 出栈
   - `JavaMethodClosure.invoke` 的 2-arg 版本期望 args[0] 为 target
   - **解决方案方向**：新 opcode 保留 base，或修改 INVOKE_DYN 语义传递 base

2. **for-in 栈管理**（#4）
   - `ITER_NEXT` 推入 `[iterator, value]`，`ITER_DONE` 弹出 value 检查 null
   - `STORE_VAR` 也需要 value 副本
   - 第二次迭代时迭代器被错误消耗
   - **解决方案方向**：引入临时槽保存 iterator，或使用 `DUP` 正确管理栈

3. **延迟求值包装**（#14, #19）
   - `&tail` 字段需要 `EvalClosure` 延迟求值
   - IR 编译期无法生成等价的惰性代码
   - **解决方案方向**：扩展常量池支持 lazy closure 引用

4. **自定义运算符重载**（#6, #9）
   - 编译期无法判断自定义类型的运算符语义
   - **解决方案方向**：类型特化 pass 识别已知类型的运算符闭包
