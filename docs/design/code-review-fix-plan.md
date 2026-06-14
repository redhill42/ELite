# IR 子系统代码审查 — 修复计划

> 审查日期：2026-06-14
> 审查范围：6月8日以来新增代码（279文件, ~23,000行）
> 测试状态：591 tests, 0 failures, 0 errors, 29 skipped

---

## 概览

| 严重程度 | 数量 | 已修复 |
|---------|:----:|:------:|
| 🔴 Critical（语义错误/泄漏） | 7 | 0 |
| 🟠 Major（功能缺陷） | 5 | 0 |
| 🟡 Minor（代码质量） | 10 | 0 |

---

## 🔴 第一轮：Critical（语义错误 & 内存泄漏）

### P0-1: IRBuilder.emitTypedCmp — Token.NE 回退路径生成错误比较

- **文件**: `IRBuilder.java` ~610-623行
- **问题**: `Token.NE` 动态回退调用 `emitDynEq()` 后没有 `emitNot()`，`!=` 错误返回 `==` 结果
- **根因**: `emitDynamicCmp` 的 switch 中 `Token.NE` case 分支缺失，落入 default → emitDynEq()
- **修复**:
  - `emitTypedCmp` line 613: `else current.emitDynEq()` → `else { current.emitDynEq(); current.emitNot(); }` ✅
  - `emitDynamicCmp` line 622: 新增 `case Token.NE -> { current.emitDynEq(); current.emitNot(); }` ✅
  - `compileLambda` line 1221: `emitReturnVoid()` → `emitReturn(typeId)` ✅
  - 顺便修复了 GT/GE 的动态回退路径（之前用 `emitDynLt()`/`emitDynLe()` 语义错误）✅
- **验证**: 新增 6 个 NE 测试用例，全部通过。全量 597 测试无回归
- **状态**: ✅ 已修复

### P0-2: IRBytecodeCompiler — 默认参数覆盖显式传递的 null

- **文件**: `IRBytecodeCompiler.java` ~241-271行
- **问题**: 用 `IFNONNULL` 检查 locals[i] == null 判断是否需填充默认值。调用方显式传 null 会被错误覆盖
- **根因**: 缺少实际参数数量的传递，无法区分"未传参"和"传了 null"
- **修复**: 传递实际 argCount，仅对 `i >= argCount` 的索引应用默认值
- **验证**: 测试 `define f(x=42) = x; f(null)` 应返回 null 而非 42
- **状态**: ⬜ 待修复

### P0-3: Runtime.funcPool — ThreadLocal 泄漏

- **文件**: `Runtime.java:253-258`, `IRBytecodeCompiler.java:91`
- **问题**: `Runtime.setFuncPool()` 每次编译被调用，但 `clearFuncPool()` 从未执行。长期运行导致 constant pool 引用无法 GC
- **修复**:
  - 方案A: 在 `CompiledFunction.execute()` 的 finally 中调用 `Runtime.clearFuncPool()`
  - 方案B: 在 `IRBytecodeCompiler.compile()` 返回前调用
  - 方案C: 在 `Runtime.invokeDyn()` 等方法结束时清理
- **验证**: 重复执行 O3 编译的程序，确认无 OOM
- **状态**: ⬜ 待修复

### P0-4: O3 字节码管线 — 零测试覆盖

- **问题**: 591 个测试全部在 O2 (IR 解释器) 运行，O3 管线从未被自动化测试覆盖
- `BytecodeCompilerTest` 绕过了 `ELProgram.evaluate()` 完整管线
- `BytecodeClosureTest` 只做 `assertNotNull(compile(fn))`，从未执行
- **根因**: `ELProgram.OPT_LEVEL` 默认值为 2，无测试显式设置 `-Delite.opt.level=3`
- **修复**:
  1. 添加 `BytecodeE2ETest.java`（通过 ScriptEngine + O3 运行）
  2. 修复 `BytecodeClosureTest` 添加 `cf.execute()` + 断言
  3. 确保 CI 也运行 O3 测试
- **状态**: ⬜ 待修复

### P0-5: IRSpeclializer.tryDeoptSplit — deopt 跳转时栈状态不一致

- **文件**: `IRSpeclializer.java` ~171-287行, `IRInterpreter.java` ~961-979行
- **问题**: GUARD_TYPE 失败跳转到 deopt 块时不清空栈。prefix 中已压入栈的值在 deopt 块重新执行时造成栈重复
- **根因**: deopt 块是原始指令的**完整拷贝**（包括 GUARD_TYPE 之前的指令），但跳转前这些指令已部分执行
- **修复**:
  - 方案A: deopt 块只包含从被 guard 的操作开始的代码（继承 prefix 栈状态）
  - 方案B: GUARD_TYPE 跳转前弹出 prefix 栈内容到合适位置
- **验证**: 编写触发类型不匹配 → deopt 的测试，验证计算结果正确
- **状态**: ⬜ 待修复

### P0-6: IRBytecodeCompiler.emitTryCatch — 完全的死代码

- **文件**: `IRBytecodeCompiler.java` ~564行, ~1146-1231行
- **问题**: commit `658d508` 声称实现了 JVM 异常表支持，方法也完整实现了，但从未被调用。try/catch 在所有路径都回退到 AST
- **修复**:
  - 在 `compileInst()` TRAMPOLINE 处理中对 TryDescriptor 调用 `emitTryCatch()`
  - 或在 IR 解释器 TRAMPOLINE 中执行预编译的 tryBody/catchBody
  - 如果短期内不启用，添加 `// TODO` 注释说明原因
- **验证**: try/catch/finally 在 O3 下不走 AST 回退
- **状态**: ⬜ 待修复

### P0-7: CompilationError — 零测试覆盖

- **问题**: O3→IR 回退的关键机制没有任何测试
- **修复**: 添加测试覆盖：
  1. `IRBytecodeCompiler.compile()` 抛出 CompilationError 的场景
  2. `ELProgram` 回退到 IR 后结果正确性
  3. 严格模式 (STRICT_BYTECODE) 下 CompilationError 的传播
- **状态**: ⬜ 待修复

---

## 🟠 第二轮：Major（功能缺陷）

### P1-1: STRICT_BYTECODE 从未被检查

- **文件**: `ELProgram.java:68-72`
- **问题**: 字段通过 `-Delite.strict=true` 可设置，但在 O3 CompilationError catch 块中从未检查
- **修复**: 在 case 3 的 catch (CompilationError) 中添加检查
- **状态**: ⬜ 待修复

### P1-2: compiledCache ThreadLocal 未在 resetState() 中清理

- **文件**: `IRBytecodeCompiler.java:809-825`
- **问题**: `resetState()` 清理 `funcRegistry` 和 `funcIdCounter` 但不清理 `compiledCache`
- **修复**: 添加 `compiledCache.remove()`
- **状态**: ⬜ 待修复

### P1-3: IRFunction/IRClosure 调用逻辑在三处重复

- **文件**: `ELEngine.java:507-518`, `Runtime.java:124-138`, `IRInterpreter.java:1086-1101`
- **问题**: 参数展开 + 捕获展开 + IRInterpreter 创建在三处几乎相同。修改需同时更新
- **修复**: 提取为单一方法 `Runtime.executeIRFunction(ELContext, IRFunction, Object[])`
- **状态**: ⬜ 待修复

### P1-4: SCOPE_ENTER/SCOPE_EXIT 字节码编译为 NOP — 架构风险

- **文件**: `IRBytecodeCompiler.java:610`
- **问题**: 字节码和 IR 解释器间的作用域语义存在根本性差异
- **修复**: 在 IRBuilder/IRFormat 文档中明确说明仅对 IR 解释器有意义
- **状态**: ⬜ 待修复

### P1-5: IRSpeclializer.remapAllJumps 遗漏 ITER_DONE

- **文件**: `IRSpeclializer.java` ~145-164行
- **问题**: 块分裂后重映射跳转目标时只处理 `isJump()` + `INVOKE_TAIL`，遗漏 `ITER_DONE`
- **修复**: 添加对 ITER_DONE 目标的重映射
- **状态**: ⬜ 待修复

---

## 🟡 第三轮：Minor（代码质量）

### P2-1: IREmitter.fits16() 冗余表达式

- **文件**: `IREmitter.java:45`
- **问题**: `(v & 0xFFFF) == (v & 0xFFFF)` 永远为 true
- **修复**: 简化为 `return v >= 0 && v <= 0xFFFF;`
- **状态**: ⬜ 待修复

### P2-2: IREmitter.emitPushVar primTypeId 参数未使用

- **文件**: `IREmitter.java:90-91`
- **修复**: 移除参数或编码入指令
- **状态**: ⬜ 待修复

### P2-3: IRSpeclializer 类名拼写错误

- **文件**: `IRSpeclializer.java`
- **问题**: 应为 `IRSpecializer`（少一个 s）
- **修复**: 重命名（注意更新所有引用）
- **状态**: ⬜ 待修复

### P2-4: INVOKE_DIRECT 地址范围错位

- **文件**: `Opcode.java:117`
- **问题**: 值 0x5F 在控制流段末尾，注释说属于函数段 (0x60-0x6F)
- **修复**: 移动值或更新注释
- **状态**: ⬜ 待修复

### P2-5: IRInterpreter locals 数组无扩容

- **文件**: `IRInterpreter.java:100`
- **问题**: stack 有 growStack() 扩容，locals 没有。超过 64 个局部变量时 ArrayIndexOutOfBounds
- **修复**: 为 locals 添加扩容机制
- **状态**: ⬜ 待修复

### P2-6: specializeBinary / replaceDynNeg 死代码

- **文件**: `IRSpeclializer.java:603-620`
- **修复**: 删除或重构使用
- **状态**: ⬜ 待修复

### P2-7: compileOrGet 中 key 变量计算后未使用

- **文件**: `IRBytecodeCompiler.java:680-703`
- **修复**: 删除无效计算或改用 key 作为 Map 键
- **状态**: ⬜ 待修复

### P2-8: 不同路径的错误消息不一致

- **文件**: `Runtime.java:57` — `"Undefined: "`, `IRInterpreter.java:1257` — `"Undefined identifier: "`
- **修复**: 统一消息格式
- **状态**: ⬜ 待修复

### P2-9: IRBuilder 闭包捕获作用域内变量静默失败

- **文件**: `IRBuilder.java` ~1017-1022行
- **问题**: 被捕获变量在作用域内定义时 varIndex.get() 返回 null，捕获静默跳过
- **修复**: 检查 scopeStack 或添加警告
- **状态**: ⬜ 待修复

### P2-10: SingleLoader 静态 ClassLoader 永久持有编译类

- **文件**: `IRBytecodeCompiler.java:73`
- **问题**: 长期运行已编译类永远无法 GC
- **修复**: 每线程独立 ClassLoader 或过期缓存
- **状态**: ⬜ 待修复（长期优化）

---

## 修复进度追踪

| 轮次 | 项目数 | 已完成 | 进行中 | 待开始 |
|:----:|:------:|:------:|:------:|:------:|
| 🔴 第一轮 | 7 | 1 | 0 | 6 |
| 🟠 第二轮 | 5 | 0 | 0 | 5 |
| 🟡 第三轮 | 10 | 0 | 0 | 10 |
| **总计** | **22** | **1** | **0** | **21** |

---

## 相关文档

- [IR Opcode 实现计划](ir-opcode-implementation-plan.md)
- [字节码编译器差距分析](bytecode-compiler-gaps.md)
- [IR 回退策略移除方案](ir-fallback-strategy-removal.md)
