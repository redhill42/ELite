# IR Fallback Strategy Removal

> 日期：2026-06-12

## 背景

`ELProgram.evaluate()` 中 O1/O2 路径原本使用 `try { IR } catch (Exception) { AST }` 模式，IR 解释器发生任何异常都静默回退到 AST。这带来两个问题：

1. **用户程序异常被截获**——程序 bug 被静默掩盖
2. **状态破坏**——IR 解释器可能已修改 Java 对象状态，再回退到 AST 重新执行导致逻辑错误

## 实施方案

### 第一阶段：重构基础

| 变更 | 说明 |
|------|------|
| `org.elite.eval.Runtime` | 提取字节码运行时辅助方法到独立类，与编译器分离 |
| ELContext 参数化 | 生成的 `execute()` 方法改为 `(ELContext, Object[])`，消除 ThreadLocal |
| 清理死代码 | 移除迁移到 Runtime 后的约 200 行废弃方法 |

### 第二阶段：策略变更

| 变更 | 文件 | 说明 |
|------|------|------|
| 移除 try-catch 回退 | `ELProgram.java` | O1/O2 路径不再捕获异常回退 AST；O3 路径移除 RuntimeException 回退，保留 CompilationError 回退 |
| 移除 `.method()` 二义性 | `ELNode.java` | `"hello".print()` 不再将全局函数伪装为对象方法；应使用 `val -> func` 管道语法 |
| 异常包装 | `ELiteScriptEngine.java` | RuntimeException 在 ScriptEngine 层包装为 ScriptException，保持 API 契约 |

### 第三阶段：IR 修复（46 → 0 failures）

以下按修复顺序排列：

#### 3.1 函数解析
**问题**：`print("hello")`、`Math.abs(-42)` 等全局/导入函数无法解析  
**根因**：`resolveGlobal` 和 `pushGlobal` 只检查 VariableMapper 和 ELResolver，跳过了 FunctionMapper  
**修复**：在 `IRInterpreter.resolveGlobal` 和 `Runtime.pushGlobal` 中添加 `MethodResolver.resolveGlobalMethod` 调用  
**影响测试**：8 个

#### 3.2 类型识别
**问题**：`"hello".length()` 无法解析为 String 方法  
**根因**：`resolveJavaClass` 只处理 `ClassType`，String 是 `PrimitiveType`  
**修复**：`resolveJavaClass` 增加 `PrimitiveType` 分支  
**影响测试**：4 个

#### 3.3 参数类型转换
**问题**：`"hello".substring(1,4)` 失败——ELite 的 `1` 是 `Long`，Java 方法期望 `int`  
**根因**：`INVOKE_METHOD` 未对参数做类型转换  
**修复**：IR 解释器和 Runtime 添加 `coerceArg` 方法，按 Java 参数类型转换 Number；field store 同样添加转换  
**影响测试**：4 个

#### 3.4 重载方法处理
**问题**：`Math.abs(-42)` 有 4 个重载，`resolveMethod` 返回 null  
**根因**：方法有歧义时 IR 编译为 LOAD_PROPERTY + INVOKE_DYN，LOAD_PROPERTY 找不到方法  
**修复**：`buildApply` 中当方法有歧义但类已知时，回退到 AST trampoline  
**影响测试**：9 个

#### 3.5 静态字段访问
**问题**：`Math.PI`、`System.out` 失败——`base.getClass()` 返回 `Class.class` 而非目标类  
**根因**：`loadField`/`storeField` 对 `Class` 实例处理不正确  
**修复**：检测 `base instanceof Class`，使用 `base` 本身作为类来查找字段；静态字段传 `null` 作为 target  
**影响测试**：2 个

#### 3.6 0-arg 属性调用
**问题**：`[1,2,3].size()` 先 LOAD_PROPERTY 返回 Integer(3)，再 INVOKE_DYN 尝试调用 Integer 作为函数  
**根因**：`buildApply` 对 0-arg ACCESS 未做特殊处理  
**修复**：0-arg ACCESS 不发出 INVOKE_DYN，直接保留 LOAD_PROPERTY 结果  
**影响测试**：3 个

#### 3.7 List/Seq 兼容
**问题**：`[1,2,3].tail` 失败——NEW_LIST 生成 `ArrayList`，不是 `Seq`  
**根因**：`ListELResolver` 处理 `first`/`rest` 但未处理 `head`/`tail` 别名  
**修复**：`ListELResolver` 添加 `head`→`first`、`tail`→`rest` 别名；不修改 `SeqELResolver`  
**影响测试**：1 个

#### 3.8 动态方法调用
**问题**：`list.map(fn)` 编译为 LOAD_PROPERTY + INVOKE_DYN，LOAD_PROPERTY 找不到方法 `map`  
**根因**：动态类型的 ACCESS+APPLY（args>0）在编译阶段无法解析方法  
**修复**：`buildApply` 中对未解析的 ACCESS+APPLY with args 回退 AST trampoline  
**影响测试**：3 个

#### 3.9 列表推导
**问题**：`[x*2 | x <- [1..5]]` 是特殊 AST 形式  
**修复**：`buildApply` 检测 FOREACH/FOR/XFORM 节点，回退 AST trampoline  
**影响测试**：2 个

#### 3.10 惰性序列
**问题**：`[n : &from(n+1)]` 导致 IR 死循环展开无限序列  
**根因**：`buildCons` 未检测 `delay` 标志  
**修复**：`buildCons` 添加 `hasDelayOrDottedTail` 检查，惰性序列回退 AST  
**影响测试**：1 个

#### 3.11 闭包参数传递
**问题**：`apply(\x => x * 2, 7)` 中 lambda 编译为 IRFunction，传入 AST 函数后无法调用  
**根因**：`ELEngine.invokeTarget` 不认识 IRFunction/IRClosure 类型  
**修复**：`invokeTarget` 添加 IRFunction/IRClosure 处理，通过 IRInterpreter 执行  
**影响测试**：2 个

#### 3.12 类运算符重载
**问题**：`a + b`（a/b 是用户定义类实例）编译为 DYNADD，`dynamicAdd` 强制转换为 Number 失败  
**根因**：`dynamicAdd` 等动态操作不支持 ClosureObject  
**修复**：`dynamicOp` 添加 `needsTrampolineDispatch` 检查，ClosureObject 操作数时构建 AST INFIX 节点委托 AST 求值  
**影响测试**：1 个

#### 3.13 其他
- `BeanPropertyELResolver`：PropertyNotFoundException 添加属性名，便于调试
- `--add-opens java.base/java.util=ALL-UNNAMED`：修复反射访问 `Arrays$ArrayList` 问题
- 测试修复：`importStaticWildcard`、`importStaticMethod` 中 Long 断言

## 关键设计决策

1. **AST 是最后防线**——不修改 ELResolver 链（如 SeqELResolver），因为这些组件被 AST 路径共用，出错会破坏最后的安全网
2. **回退应该显式**——不再静默回退；需要回退的场景通过 `buildTrampoline` 显式标记，编译期即可检测
3. **List 语义兼容 Seq**——`[1,2,3]` 保持为 Java List，通过 `ListELResolver` 添加 Seq 别名实现兼容，而非改变 IR 的列表实现
4. **移除 `.method()` 二义语法**——对象方法的调用不应自动转发到全局函数；管道操作符 `->` 是明确的一等公民
