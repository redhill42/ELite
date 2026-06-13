# Bytecode Compiler Gaps

> 更新日期：2026-06-13

本文档记录 IR 字节码编译器（`IRBytecodeCompiler.compileInst()`）相对于 IR 解释器的能力差距，以及 IR builder 的已知缺陷。

## 🔴 P0：IRBuilder 条件块重复（块管理 bug）

**影响**：O2/O3 — 动态比较（如 `i % 2 == 0`）在 `if` 条件中时，生成的 IR 包含重复条件块，导致 `JUMP_IF_TRUE` 不可达，条件分支始终走 else。

**现象**：
```
B0: 条件计算 → JUMP B4 (else)
B1: 条件计算 → JUMP B4 (else)  ← 重复块，不可达
B2: JUMP_IF_TRUE B3, JUMP B4     ← 条件跳转，不可达
```

**复现**：`if (i % 2 == 0) { i *= 5 }` — `define i = 2` 后 `i` 仍为 2。

**根因分析**：`build(node.cond)` 对动态比较（`buildComparison` → `emitDynamicCmp`）在 IR builder 的块管理中生成了两个相同内容的块。常量比较（如 `1 == 1`）无此问题。需要深入调试 `IRBuilder.finish()` 的块汇编逻辑和 `startBlock`/`allocBlockId` 的块 ID 分配。

**修复方向**：重写 IR builder 的块管理，或为条件块引入显式的块合并机制。

---

## 一、指令覆盖总览

IR 共定义 85 个 opcode（不含 `NOP`），在 `-O3` 字节码编译器中的处理情况：

## 一、指令覆盖总览

IR 共定义 85 个 opcode（不含 `NOP`），在 `-O3` 字节码编译器中的处理情况：

| 分类 | 数量 | 说明 |
|------|:--:|------|
| 纯 JVM 字节码 | ~40 | 直接映射到 JVM 指令（ADD, CMP, JUMP, RETURN 等） |
| 静态辅助方法 | ~30 | 调用 `IRBytecodeCompiler` 的静态 helper（`pushGlobal`, `invokeDyn`, `loadProp` 等） |
| 直接 invokevirtual | 3 | `INVOKE_GETTER`/`SETTER`/`METHOD` — checkcast + invoke |
| CompilationError 回退 | 1 | `0xE0` trampoline — AST 依赖，永久无法编译 |
| **缺失** | **1** | `CONTAINS` (0x7F) — 应当修复 |
| **退化** | **12** | Long/Double 类型化算术 — 应生成原生 JVM 指令 |
| 死指令 | 3 | `TABLE_SWITCH`, `CAPTURE`, `CAT` — 从未被 IRBuilder 发射 |

## 二、已知缺陷

### 缺陷 1：CONTAINS 缺失（严重）

**文件**：`IRBytecodeCompiler.java` — `compileInst()` 的 default 分支

`in` 操作符（`x in [1,2,3]`）在 IRBuilder 中发射 `CONTAINS` 指令，IR 解释器正确处理，但字节码编译器没有 `case CONTAINS`，导致 `default → UnsupportedOperationException("BC: CONTAINS")`。

**回退路径**：`UnsupportedOperationException`（`RuntimeException` 子类）→ `ELProgram.evaluate()` case 3 的 `catch (RuntimeException)` → IR 解释器 → 返回正确结果。每次 `in` 操作都触发完整回退链。

**验证**（`-Delite.debug=true -Delite.opt.level=3`）：
```
[elite] bytecode runtime fallback: BC: CONTAINS
```

**修复**：添加 `case CONTAINS -> emitCall2("contains")` 和静态方法 `contains(Object container, Object element)`。

### 缺陷 2：Long/Double 类型化算术退化（中等）

**文件**：`IRBytecodeCompiler.java` 行 235-247

`IRSpeclializer` 将动态操作替换为类型化操作（例如 `DYNADD` → `LADD`），但在字节码编译器里，这些类型化指令全部指向了动态 helper：

```java
case LADD -> emitDynCall("dynAdd", 2);  // 运行时仍然做 instanceof 检查
case DADD -> emitDynCall("dynAdd", 2);  // 同上
```

受影响指令（12 条）：
- `LADD`, `LSUB`, `LMUL`, `LDIV`, `LREM`, `LNEG`, `LPOW`
- `DADD`, `DSUB`, `DMUL`, `DDIV`, `DNEG`, `DPOW`

这些 helper（`dynAdd(Object,Object)` 等）在运行时做 `instanceof` 检查来决定执行 Long 还是 Double 运算——specializer 在编译期已确定的类型信息被丢弃。

**应生成的原生 JVM 指令**：`LADD`/`LSUB`/`LMUL`/`LDIV`/`LREM`/`LNEG`、`DADD`/`DSUB`/`DMUL`/`DDIV`/`DNEG` 等，加上必要的 boxing/unboxing。`IPOW` 已有正确实现（`emitCall2("intPow")`），可作为参考模式。

**对比**：`LEQ`-`LGE` 比较指令**已**正确使用原生 `lcmp`（行 256-258），`DEQ`-`DGE` 已使用 `dcmpg`（行 260-265）。同样类型的算术指令却没有对应实现，不一致。

### 缺陷 3：GUARD_TYPE deopt 不支持（架构限制）

**文件**：`IRBytecodeCompiler.java` 行 448-459

字节码编译器对 `GUARD_TYPE` 只生成 strict guard（类型不匹配抛异常）：
```java
case GUARD_TYPE -> {
    // 忽略 deoptBlockId，始终使用 strict guard
    guardTypeStrict(Object, int) -> void;
}
```

IR 解释器支持两种 guard 模式：
- `deoptBlockId == STRICT_GUARD (0xFFFF)` → strict：抛 `TypeMismatchError`
- 其他 → deopt：跳转到包含原始动态操作的回退块

`-O3` 下所有 inferred type 的 guard 都退化为 strict。guard 失败时不是只回退一个 basic block，而是整个程序回退到 IR 解释器（`RuntimeException` 被 `ELProgram.evaluate()` 捕获）。

**根本原因**：`-O3` 生成的字节码将多个 basic block 编译为单一的 JVM 方法体，deopt 需要"多入口"能力（从不同块进入同一方法的不同位置）。JVM 方法只有一个入口点（第一条指令），无法在任意位置跳入。实现 deopt 需要每个 basic block 编译为独立的静态方法，或使用 `goto` 标签——两者都会大幅增加实现复杂度。

## 三、Trampoline 指令清单

`0xE0`（trampoline）代表 AST 依赖操作，IR 无法处理，字节码永远无法编译。以下语言特性会产生 trampoline：

| 语言特性 | 触发条件 |
|----------|---------|
| `match/case` 常量模式 | 所有常量模式匹配 |
| `instanceof` | 所有类型检查 |
| `for` 循环 | 非标准 for 形式 |
| 复杂数组字面量 | 非简单列表的数组 |
| 非局部 `++`/`--` | 全局变量或属性的自增自减 |
| 非 String 拼接 | 两端都不是 String 的 `~` 运算 |
| 复杂赋值目标 | 非 IDENT、非简单 ACCESS 的赋值左值 |
| 默认分支 | AST 节点类型未被专门处理时 |

## 四、死指令

以下 opcode 在 `Opcode.java` 中定义但从未被 IR 系统使用：

| Opcode | 值 | 原因 |
|--------|----|------|
| `TABLE_SWITCH` | 0x59 | 评估后搁置，从未实现 |
| `CAPTURE` | 0x73 | 闭包改用 `CLOSURE` 指令，CAPTURE 被废弃 |
| `CAT` | 0x90 | IRBuilder 始终发射 `DYNCAT`，CAT 从未使用 |

考虑从 `Opcode.java` 中移除这些死指令，或在注释中标记为"预留"。

## 五、过时注释

`Opcode.java` 行 153-154：
```java
public static final int INC = 0xA3;  // increment (to implement)
public static final int DEC = 0xA4;  // decrement (to implement)
```

INC 和 DEC 已在 IR 解释器（`IRInterpreter.java` 行 318-339）和字节码编译器（`IRBytecodeCompiler.java` 行 460-473）中完整实现。`(to implement)` 注释过时。

## 六、修复优先级

| 优先级 | 项目 | 投入 | 收益 |
|--------|------|:--:|------|
| P0 | CONTAINS 缺失 | 极低（~5行代码） | 消除 `in` 操作符的 -O3 回退 |
| P1 | Long/Double 类型化算术 | 中（12条指令 + unbox/box） | 消除 12 条指令的运行时动态分派 |
| P2 | GUARD_TYPE deopt | 高（需要重构字节码生成架构） | guard 失败时局部回退而非全局回退 |
| P3 | 清理过时注释和死指令 | 极低 | 代码可维护性 |
