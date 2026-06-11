# IR Opcode 实现计划

本文档描述了 ELite IR 中待实现的 opcode 及其实现状态。

## 优先级总览

| 优先级 | Opcodes | 状态 | 实现方法 |
|--------|---------|:--:|------|
| P0 | CLOSURE + CAPTURE | ⏳ | 未实现 |
| P1 | GUARD_TYPE + DEOPT | ✅ | 类型守卫 + deopt 回退块，支持显式标注(strict)和推导(deopt)两种模式 |
| P2 | LOAD_FIELD + STORE_FIELD | ✅ | 已知 Java 类型属性访问优先级：getter/setter → public field → ELResolver |
| P3 | TABLE_SWITCH | ⏳ | 未实现 |
| P4 | CAT | ⏳ | 未实现 |
| P5 | INC + DEC | ✅ | 局部变量自增/自减，展开为 INC/DEC opcode |

---

## 已完成项目实现总结

### P1: GUARD_TYPE (0x80) + DEOPT — 类型守卫 ✅

**实现文件**: `IRSpeclializer.java`, `IRInterpreter.java`, `IRBytecodeCompiler.java`, `IRBuilder.java`, `IRFunction.java`

- `IRFunction.paramFlags`: 标记每个参数是否显式标注类型（`PARAM_EXPLICIT_TYPE`）
- `GUARD_TYPE typeId, deoptBlockId`: 检查栈顶类型，匹配则继续，不匹配则跳转到 deoptBlockId
  - `deoptBlockId == STRICT_GUARD (0xFFFF)`: 显式类型 — 不匹配时抛 `TypeMismatchError`
  - 其他值: 推导类型 — 不匹配时跳转到 deopt block（包含原始动态操作的回退块）
- IRBuilder: `buildLambda` 和 `registerDef` 中根据 `var.type != null` 设置 paramFlags
- IRSpeclializer: 扫描动态操作，emit GUARD_TYPE。显式类型用 strict guard，推导类型用 deopt guard
- 守卫消除 (Phase 6): 同一 basic block 内，已守卫过的变量不重复检查。`STORE_VAR` 和函数调用后失效
- 类型兼容检查: `checkType` 接受 `Long → T_INT`, `Integer → T_LONG` 等
- `elite.opt.level` 系统属性控制优化级别：0=AST, 2=IR(默认), 3=Bytecode

### P2: LOAD_FIELD (0x71) + STORE_FIELD (0x72) + INVOKE_GETTER (0xE1) + INVOKE_SETTER (0xE2) ✅

**实现文件**: `IRBuilder.java`, `IRInterpreter.java`, `IRBytecodeCompiler.java`, `Opcode.java`, `IREmitter.java`

- 已知 Java 类型属性访问优先级：
  1. JavaBean getter (`getXxx()`/`isXxx()`) → `INVOKE_GETTER`，setter (`setXxx()`) → `INVOKE_SETTER`
  2. public field → `LOAD_FIELD` / `STORE_FIELD`
  3. ELResolver chain → `LOAD_PROPERTY` / `STORE_PROPERTY`（回退）
- `IRBuilder.resolveGetter/Setter`: 编译时通过 `Class.getMethod()` 查找 getter/setter
- `IRInterpreter`: loadField/storeField 使用 `Class.getField().get/set()`，INVOKE_GETTER/SETTER 使用 `Method.invoke()`
- `IRBytecodeCompiler`: loadField/storeField 通过静态辅助方法反射访问
- 类型未知时回退到原有的 ELResolver 链

### P5: INC (0xA3) + DEC (0xA4) ✅

**实现文件**: `IRBuilder.java`, `IRInterpreter.java`, `IRBytecodeCompiler.java`

- 局部变量的 `++x`/`x++`/`--x`/`x--` 展开为 INC/DEC opcode
- `INC varIdx`: locals[varIdx] += 1，push 新值
- `DEC varIdx`: locals[varIdx] -= 1，push 新值
- 前缀: 直接 INC/DEC（push 新值即结果）
- 后缀: PUSH_VAR(旧值) + INC/DEC + POP（保留旧值在栈上）
- 非局部变量回退到 trampoline（AST 解释器）
- IRInterpreter: 支持 Long/Integer/Double 类型自增/自减
- IRBytecodeCompiler: incLocal/decLocal 静态辅助方法

### 编译流水线优化 ✅

**实现文件**: `ELProgram.java`, `CompilationError.java`, `IRFunction.java`

- `elite.opt.level` 系统属性选择执行策略：
  - 0: AST 解释器（验证 parser/AST）
  - 2: IR 解释器（默认，不支持时回退 AST）
  - 3: JVM 字节码（CompilationError 时回退 IR→AST）
- `CompilationError extends Error`: 区分编译器能力不足 vs 编译器 bug
- `IRFunction.hasUnsupportedOps()`: 预检查 trampoline 操作（0xE0）
- `execute()` 异常传播: 用户程序异常直接向上传播，不包装

---

## P0: CLOSURE (0x63) + CAPTURE (0x73) — 闭包支持

### 当前状态
闭包通过 Java 层的 `Procedure` 对象和 `ELEngine.invokeTarget()` 实现。lambda 中的自由变量（如 `\y => x+y` 中的 `x`）被编译为 `PUSH_GLOBAL`，在运行时通过 ELContext 的 VariableMapper 查找。这绕过了 IR 的类型系统，且每次调用都需要 Java 反射。

### 实现步骤

1. **IR 语义定义**
   - `CLOSURE funcIdx, captureCount`：创建一个闭包对象，捕获栈顶 `captureCount` 个值，函数体由 `funcIdx` 指定
   - `CAPTURE varIdx`：将局部变量 `varIdx` 推入栈顶，并从当前 frame 中"捕获"它（将其复制到闭包的环境中）

2. **IRBuilder 修改**
   - `buildLambda()` (line 652)：分析 lambda 体中的自由变量（不在 `varIndex` 中的标识符）
   - 对于每个捕获的变量，在 lambda 体前发射 `CAPTURE` 指令
   - 将 `PUSH_GLOBAL` 替换为 `PUSH_VAR`（使用闭包内的变量索引）
   - 发射 `CLOSURE` 指令替代当前的 `PUSH_CONST <IRFunction>`

3. **IRInterpreter 实现**
   - `CLOSURE`：创建 `ClosureObject`（或类似的），包含 IRFunction 引用和捕获的变量数组
   - `CAPTURE`：将当前栈顶的值复制到闭包捕获数组中
   - `INVOKE_DYN`：当目标是闭包对象时，设置捕获的变量并调用 IRFunction

4. **IRBytecodeCompiler 实现**
   - `CLOSURE`：使用 `invokedynamic` 或生成匿名内部类来创建闭包对象
   - `CAPTURE`：将值存储到闭包对象的字段中
   - 生成的类需要有一个 `execute(Object[])` 方法

5. **优化**
   - 对于不捕获任何变量的 lambda（纯函数），可以复用同一个闭包实例（无状态 lambda）
   - 内联小闭包（InlinePass 已有基础）

### 风险
- 闭包的生命周期管理（逃逸分析相关）
- 与现有 `Procedure` 系统的兼容性
- 递归闭包（闭包调用自身）的处理

---

## P1: GUARD_TYPE (0x80) + GUARD_NONNULL (0x81) + DEOPT (0x82) — 类型守卫

### 当前状态
`IRSpeclializer` 会将动态操作（DYNADD 等）替换为类型化操作（IADD 等），但**不插入类型守卫**。如果运行时类型与假设不符，类型化操作会产生错误结果而非回退。

### 实现步骤

1. **IRSpeclializer 修改** (`specializeBlock`)
   - 在替换动态操作前，对操作数插入 `GUARD_TYPE` 指令
   - 例如：`DYNADD` → `GUARD_TYPE T_INT; GUARD_TYPE T_INT; IADD; DEOPT L_fallback`
   - `GUARD_TYPE` 检查栈顶值是否为指定类型
   - `GUARD_NONNULL` 检查栈顶值是否为非 null
   - 如果守卫失败，跳转到 `DEOPT` 指定的回退块

2. **IRInterpreter 实现**
   - `GUARD_TYPE`：检查栈顶类型，不匹配则跳转到 DEOPT 目标
   - `GUARD_NONNULL`：检查栈顶是否为 null
   - `DEOPT`：跳转到指定的回退块（包含原始 DYNADD 的动态版本）

3. **IRBytecodeCompiler 实现**
   - `GUARD_TYPE`：发射 `CHECKCAST` 或 `INSTANCEOF` + 条件跳转
   - `GUARD_NONNULL`：发射 `IFNONNULL` 跳转
   - `DEOPT`：发射 `GOTO` 跳转到回退块

4. **回退块生成**
   - 在 `specializeBlock` 中为每个被替换的操作生成回退代码
   - 回退块包含原始的动态操作，确保守卫失败时结果正确

### 风险
- 守卫本身有运行时开销；需要在"守卫成本"和"类型化操作加速"之间平衡
- DEOPT 跳转目标的管理（需要新增 basic block）
- 与 InlinePass 的交互（内联后的守卫可能指向不存在的块）

---

## P2: LOAD_FIELD (0x71) + STORE_FIELD (0x72) — 直接字段访问

### 当前状态
所有属性访问（`obj.prop`）统一走 `LOAD_PROPERTY` → ELResolver 反射路径，即使是已知 Java 类型的字段也是如此。

### 实现步骤

1. **IRBuilder 修改** (`buildAccess`)
   - 当 `TypeInferrer` 推断出 base 对象的类型是已知 Java 类时
   - 将字段名解析为 `java.lang.reflect.Field`
   - 发射 `LOAD_FIELD fieldPoolIdx` 或 `STORE_FIELD fieldPoolIdx`

2. **IRInterpreter 实现**
   - `LOAD_FIELD`：从常量池获取 `Field` 对象，调用 `field.get(base)`
   - `STORE_FIELD`：调用 `field.set(base, value)`

3. **IRBytecodeCompiler 实现**
   - `LOAD_FIELD`：发射 `GETFIELD` 字节码
   - `STORE_FIELD`：发射 `PUTFIELD` 字节码
   - 需要处理静态字段（`GETSTATIC`/`PUTSTATIC`）

4. **类型检查**
   - 确保字段声明的类型与 IR 类型系统一致
   - 必要时插入 `CHECKCAST`

### 风险
- 字段可能被安全管理器阻止（`setAccessible` 需要 `--add-opens`）
- 需要处理字段隐藏（子类同名字段）
- static 和 instance 字段需要区分

---

## P3: TABLE_SWITCH (0x59) — 表跳转

### 当前状态
`match/case` 表达式回退到 AST trampoline，无法利用 IR 的跳转优化。

### 实现步骤

1. **IRBuilder 修改** (`buildTrampoline` 路径)
   - 识别密集的整数/字符串 case 分支
   - 发射 `TABLE_SWITCH numCases, defaultBlockId`，后跟 `(value, targetBlock)` 对
   - 对于字符串 switch，先计算 hash，再用 TABLE_SWITCH

2. **IRInterpreter 实现**
   - `TABLE_SWITCH`：弹出 key，在 case 表中查找（二分或线性），跳转到目标块

3. **IRBytecodeCompiler 实现**
   - `TABLE_SWITCH`：发射 JVM 的 `TABLESWITCH` 或 `LOOKUPSWITCH` 字节码
   - 这是 TABLE_SWITCH 的最大优势——JVM 原生支持，性能极高

4. **Block 管理**
   - TABLE_SWITCH 后跟的 case 值/目标块对需要占据额外的指令字
   - 需要扩展 `IRFormat` 来支持变长指令

### 风险
- 需要扩展 block 管理（当前 basic block 必须用 terminator 结束，但 switch 有多个出口）
- 稀疏 case 不适合 TABLE_SWITCH，需要 LOOKUPSWITCH 或回退 if-else

---

## P4: CAT (0x90) — 类型化字符串拼接

### 当前状态
`DYNCAT` 处理所有拼接（`String.valueOf(x) + String.valueOf(y)`）。当两端已知为 String 时，CAT 可以避免两次 `valueOf` 调用。

### 实现步骤

1. **IRBuilder 修改** (`buildCat`)
   - 当 `typeIdFromNode` 推断两端都是 `T_STRING` 时
   - 发射 `CAT` 替代 `DYNCAT`

2. **IRInterpreter 实现**
   - `CAT`：直接 `(String) lhs + (String) rhs`（假设类型已检查）

3. **IRBytecodeCompiler 实现**
   - `CAT`：发射 `invokevirtual String.concat(String)` 或使用 `invokedynamic` 的字符串拼接

4. **需要 GUARD_TYPE**
   - 如果类型推断不可靠，需要插入 `GUARD_TYPE T_STRING` 守卫
   - 否则 CAT 依赖 P1（GUARD_TYPE）才能安全使用

### 风险
- 依赖类型推断的准确性
- 没有守卫时类型不匹配会导致 ClassCastException

---

## P5: INC (0xA3) + DEC (0xA4) — 自增/自减

### 当前状态
`x++` / `x--` 被展开为 `x = x + 1` 或回退到 AST trampoline。

### 实现步骤

1. **IRBuilder 修改**
   - 在 `buildAssignOp` 或单独的处理路径中识别 `++`/`--` 操作
   - 对整数类型发射 `INC`/`DEC`（前提：操作数是局部变量且类型已知）

2. **IRInterpreter 实现**
   - `INC varIdx`：`locals[varIdx] = ((Number)locals[varIdx]).longValue() + 1`，将结果留在栈上
   - `DEC varIdx`：同上，减 1

3. **IRBytecodeCompiler 实现**
   - `INC`/`DEC`：发射 JVM `IINC` 指令（仅适用于 int 局部变量）
   - 对于其他类型，展开为 load + add/sub + store

4. **语义细节**
   - 前缀 `++x` 返回新值，后缀 `x++` 返回旧值 —— 需要不同指令或额外的 DUP 操作
   - 需要处理 long/double 类型

### 风险
- 前缀/后缀语义的栈管理容易出错
- 收益较小（节省一条赋值指令）

---

## 实施顺序建议

```
Phase 1: P1 (GUARD_TYPE) — 基础设施
         ↓
Phase 2: P4 (CAT) — 依赖 P1 的类型守卫
         P5 (INC/DEC) — 独立，可并行
         ↓
Phase 3: P0 (CLOSURE+CAPTURE) — 最大性能收益
         P2 (LOAD_FIELD/STORE_FIELD) — Java interop 优化
         ↓
Phase 4: P3 (TABLE_SWITCH) — 模式匹配优化
```

每个 phase 包含：opcode 实现 → IRBuilder 发射 → IRInterpreter 处理 → IRBytecodeCompiler 生成 → 测试覆盖。
