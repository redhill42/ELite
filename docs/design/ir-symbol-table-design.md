# IR 栈式符号表设计

## 目标

用一个统一的栈式符号表取代当前分散的变量管理机制，解决重名变量在捕获场景下的作用域问题。

## 当前分散机制的缺陷

| 机制 | 问题 |
|------|------|
| `savedVarBindings` (Deque) | 只做 slot 级遮蔽，变量名不变，STORE_GLOBAL/DEFINE_GLOBAL 使用相同名字 |
| `isCaptured` (Set) | 只记录"是否被捕获"，不记录被哪个 scope 捕获 |
| `globalSlots` (Set) | 辅助判断是否需要 STORE_GLOBAL，是 symptom 不是 root |
| `knownFunctions` (ThreadLocal Deque) | 函数查找独立于变量查找，无法统一管理 |
| `varIndex` / `varNames` / `paramFlags` | 三个独立结构，信息分散 |

当 `queens(n)` 的外层参数 `n` 与 `safe([x:xs], y, n)` 的模式变量 `n` 同名时，DEFINE_GLOBAL 写入相同的名字，外层被覆盖。

## 新设计：ScopeStack + SymbolInfo

### 两遍扫描

进入新 scope 时采用两遍策略：

**第一遍**（register）：快速扫描 scope 内所有 `DEFINE` 语句，将名字预先注册到符号表中。此时不编译表达式，只建立符号条目。

**第二遍**（compile）：编译每个 `DEFINE` 的表达式和 scope 内的语句体。此时所有名字都已注册，超前引用（函数之间相互调用等）可以正确解析。

```
enterScope()
// ── 第一遍：收集 ──
for each define in scope:
    scopeStack.define(name, flags=UNRESOLVED)

// ── 第二遍：编译 ──
for each define in scope:
    compile(define.expr)         // 此时所有名字已存在
    update symbol with func info  // 补充已知函数信息
for each statement in scope:
    compile(statement)
```

这解决了 `define f() { g() }; define g() { f() }` 中超前引用 `g` 的问题。

---

## CLASSDEF 元数据

符号条目需要携带 class 信息，为将来的 CLASSDEF 编译提供数据：

```java
class ClassInfo {
    String      name;           // 类名
    String      baseClass;      // 基类名 (String → 编译期解析)
    String[]    interfaces;     // 实现的 Java 接口名
    String[]    dataSlots;      // 构造器参数名 (vars[])
    MemberInfo[] members;       // 实例成员 (ivars)
    MemberInfo[] staticMembers; // 静态成员 (cvars)
    boolean     isData;         // @data 标注
    boolean     isImmutable;    // @data(immutable)
}

class MemberInfo {
    String      name;
    int         modifiers;     // public/private/protected/static/&lazy
    IRFunction  func;          // 方法编译后的 IRFunction
    String      typeAnnotation;// 类型标注
}
```

`SymbolInfo` 中增加：

```java
class SymbolInfo {
    // ...原有字段...
    ClassInfo   classInfo;      // 如果是 CLASSDEF，携带类元数据
}
```

将来编译 `obj.method()` 时：
```
lookup(objType) → SymbolInfo.classInfo
    → classInfo.members[name] → MemberInfo.func → INVOKE_DIRECT
```

编译 `ClassName(args)` 时：
```
lookup("ClassName") → SymbolInfo.classInfo
    → classInfo.dataSlots → 构造器参数信息
    → classInfo.members[name] → 方法 IRFunction
```

---

## Known Function 骨架预分配

第一遍扫描时为每个函数创建 `IRFunction` 骨架（含原型，不含代码体），直接放入常量池。编译函数体时从符号表取出骨架，将代码填入。无需指令回填。

### 流程

```
第一遍（register + 预分配骨架）:
  define f() { g() }
    → 创建骨架: IRFunction skeletonF(name="f", paramCount=0, ...)
    → poolIdxF = putConstant(skeletonF)
    → SymbolInfo { func = skeletonF, poolIdx = poolIdxF }
    → scopeStack.define("f", ...)

  define g() { ... }  
    → 创建骨架: IRFunction skeletonG(name="g", paramCount=0, ...)
    → poolIdxG = putConstant(skeletonG)
    → SymbolInfo { func = skeletonG, poolIdx = poolIdxG }
    → scopeStack.define("g", ...)

第二遍（编译函数体）:
  f 的体 { g() }:
    lookup("g") → SymbolInfo.poolIdx = poolIdxG  // 已有效
    → emit INVOKE_DIRECT poolIdxG                 // 直接使用！

  g 的体 { ... }:
    编译完成后:
    → compile(body) → int[] code, offsets, ...
    → skeletonG.code = code
    → skeletonG.blockOffsets = offsets
    → ...填充其余字段...
```

### IRFunction 骨架构造

```java
IRFunction createSkeleton(String name, int paramCount, int captureCount,
                          String[] varNames, int[] paramFlags) {
    return new IRFunction(name, paramCount, captureCount,
        new int[] { Opcode.RETURN_VOID },  // 最小占位代码
        new int[] { 0 },                     // 单块偏移
        new Object[0],                       // 空常量池（后续填充）
        varNames,
        DebugInfo.EMPTY,
        paramFlags);
}
```

### 编译时填入

```java
void fillSkeleton(IRFunction skeleton, int[] code, int[] offsets,
                  Object[] constants, int[] paramFlags) {
    skeleton.code = code;
    skeleton.blockOffsets = offsets;
    skeleton.constantPool = constants;
    skeleton.paramFlags = paramFlags;
    // name, paramCount, captureCount 已在骨架中
}
```

### 优势

- 零回填：常量池引用自第一遍起有效
- `INVOKE_DIRECT` 正常生成，无需 `INVOKE_TARGET` 降级
- `IRFunction` 对象引用稳定，闭包创建 (`CLOSURE` opcode) 也可直接使用
- 递归调用自然支持：函数体中可以引用自己的骨架

---

## 待验证的边界场景

### 1. 自引用递归值

```elite
define doubles = [1 : &add_cons(doubles, doubles)]
```

第一遍注册 `doubles`，分配 slot。第二遍编译表达式时 `doubles` 引用自身——slot 已有效，但值是 thunk，需要 STORE_GLOBAL + DEFINE_GLOBAL 确保 thunk 可见。符号表需标记该变量被自身引用。

### 2. 捕获信息跨 scope 传播

```elite
define outer(a) {
    define inner1() => a          // inner1 捕获 a
    define inner2() => \ => a     // inner2 的嵌套 lambda 也捕获 a
}
```

`a` 被 inner1 和 inner2 捕获。编译 inner1 时需要在符号表中标记 `a.captured = true`。编译 inner2 中的嵌套 lambda 时，需要通过符号表找到 `a` 来自外层 scope，并反向传播捕获标记。当前 `computeLambdaCaptures` + `captureFreeVariables` 做这件事，新设计中符号表需要原生支持从内层向外层传播 `captured` 标记。

### 3. 模式变量的定义与引用

```elite
define check((a, a)) => "ok"
```

第一个 `a` 是定义（绑定），第二个 `a` 是引用（已绑定检查）。重命名时必须一同修改——因为它们是同一名字在同一 scope 中。

OR 模式中：
```elite
| (a, _) | (_, a) => a
```

`a` 出现在左分支（定义）、右分支（定义）、body（引用）。Parser 已保证两侧 binding 相同。重命名时 walk 整个 CASE 子树——所有 `a` 一起改。由于 OR 分支和 body 都在同一 case scope 内，一次遍历即可覆盖。

实现约束：遍历范围以当前 scope 为界，不进入嵌套 lambda（lambda 有独立 scope，其中的 `a` 是另一个变量）。

### 4. OR 模式的重命名一致性（已由 AST 重命名解决）

```elite
| (x, _) | (_, x) => x
```

Parser 已强制两个分支绑定相同变量。重命名时两个分支的 `x` 应产生相同的 mangled name（例如都叫 `x$1`），因为它们指向同一个符号。这要求重命名基于"外层名字 + 作用域深度"而非"首次遇到"。

### 4. 模式变量被 thunk/lambda 捕获

```elite
define foo(n) {
    | m => delay(m + n)     // thunk 捕获 m 和 n
    | m => \=> m + n        // lambda 捕获 m 和 n
}
```

`m` 是模式变量，`n` 是函数参数。thunk/lambda 通过 nested IRBuilder 编译，scope stack 传入。`m` 和 `n` 需要能通过符号表找到。如果 `m` 与外层某变量重名，需要重命名且 thunk/lambda 内的引用使用 mangled name。

### 5. AST 级重命名

重命名直接修改 AST 节点中的名字（`DEFINE.id`、`IDENT.id`），而非只修改 IR 常量池。这意味着：

- IR 指令使用 mangled name → ✅
- DEFINE_GLOBAL 使用 mangled name → ✅  
- Trampoline 求值 AST 时看到 mangled name → ✅（无需映射）
- evalContext 中内外层变量自然隔离 → ✅

```
原始 AST:                    rename("n" → "n$1") 后:
  define n = 0                 define n$1 = 0
  r = \=> print(n)             r = \=> print(n$1)
```

所有对 `n` 的 IDENT 引用也被同步修改。外层 `n` 保持原样。

### 6. 值定义的编译顺序

```
TRAMPOLINE opcode → AST 求值 → 需要访问 IR 编译的变量
```

当前 `syncLocalsToGlobals()` 将 IR locals 同步到 evalContext。如果变量使用 mangled name 存入 evalContext，AST 求值器查找的是 original name → 找不到。需要在 trampoline 边界做名字映射，或保持 original name 用于 evalContext（实际重命名只影响常量池条目，不影响 DEFINE_GLOBAL 用的名字）。

### 6. 超前引用规则

第一遍注册名字时，根据不同定义类型区别对待：

```
define f() { ... }       → 函数 (LAMBDA)     → 超前引用：允许 → 预分配骨架
define g = \=> ...        → 函数 (LAMBDA)     → 超前引用：允许 → 预分配骨架
class Foo { ... }         → CLASSDEF          → 超前引用：允许 → 预分配骨架（待 class 编译）
define x = y + 1          → 值定义              → 超前引用：禁止 → y 必须先定义
define t = delay(y + 1)   → thunk (本质 lambda) → 超前引用：允许 → thunk 的 IRFunction 可预分配
```

thunk 本质是零参数 lambda，编译为 `DELAY` opcode → `IRClosure`。thunk 体捕获的变量在 force 时才求值，此时被引用变量已定义。因此 thunk 内的变量引用不是值定义层面的超前引用。

但对于 `define x = y + 1`（直接值定义），`y` 必须在 `x` 之前定义——第一遍只注册了 `y` 的名字，第二遍按顺序编译，`x` 编译时 `y` 的 IR 指令尚未生成，但 `y` 的 slot 已分配、运行时 `y` 会先于 `x` 求值（Parser 保证顺序）。

**自引用递归值**（特例）：

```elite
define xs = [1 : &add_cons(xs, xs)]
```

`xs` 是值定义，但其自身引用在 thunk（`&`）中。这不是超前引用（引用的是正在定义的变量本身），处理方式：`xs` DEFINE_GLOBAL 在 thunk 创建之前执行，thunk 通过 PUSH_GLOBAL 访问。

### 7. Debug 信息中的名字

抛出错误时应显示 original name（用户写的名字），而非 mangled name（`n$1`）。`DebugInfo` 中存储的变量名应使用 original name。

---

## 核心结构

```
ScopeStack:
  ├── Scope[0]   ← 函数参数 (function entry)
  ├── Scope[1]   ← if 体 / loop 体
  ├── Scope[2]   ← 嵌套控制作用域
  ├── Scope[3]   ← case 0 (模式匹配变量)
  └── Scope[4]   ← case 1
```

### SymbolInfo

```java
class SymbolInfo {
    String  originalName;   // 源代码中的名字 "n"
    String  mangledName;    // 重命名后的名字 "n$2" (仅在被遮蔽时有值)
    int     slot;           // IR locals[] 索引
    int     flags;          // PARAM_LAZY | PARAM_CAPTURED | ...
    boolean captured;       // 被内层 closure 捕获
    IRFunction func;        // 如果是函数定义，指向其 IRFunction (known function)
}
```

### 基本操作

```
enterScope()          → scopeStack.push(new Scope())
leaveScope()          → scopeStack.pop()
define(name, flags)   → 如果 lookup(name) != null (外层已有) → 重命名
                         scopeStack.top().put(name, new SymbolInfo(...))
lookup(name)          → 从栈顶向栈底搜索，返回第一个匹配
```

### 重命名策略

```
define("n", flags)  ← 内层 scope
  │
  ├── lookup("n") → 在外层 scope 中找到
  │
  ├── 生成新名字: "n$1"  (n$depth 或 n$outerSlot)
  │
  └── scopeStack.top().put("n", SymbolInfo(mangledName="n$1", ...))
```

重命名后：
- `PUSH_VAR / STORE_VAR` → 使用 `symbol.slot`（slot 天然隔离）
- `PUSH_GLOBAL / STORE_GLOBAL / DEFINE_GLOBAL` → 使用 `symbol.mangledName`（常量池中不同字符串 → 不同 evalContext 条目）
- 内层 closure 捕获时 → 捕获 `mangledName` → 不会与外层冲突

### 重命名的级联效应

当重命名发生时，当前 scope 内所有对 `originalName` 的引用都必须替换为 `mangledName`。这可以在编译期通过符号表查找完成——每次 IDENT 访问都从符号表获取当前有效的名字。

## 取代的现有机制

| 旧机制 | 新机制 |
|--------|--------|
| `savedVarBindings` (Deque) | `scopeStack` (Deque<Scope>) |
| `isCaptured` (Set<String>) | `SymbolInfo.captured` (boolean) |
| `globalSlots` (Set<Integer>) | 不需要——`mangledName` 解决了名字冲突 |
| `knownFunctions` (ThreadLocal Deque) | `SymbolInfo.func` (IRFunction)，统一在符号表中 |
| `varIndex` / `varNames` / `paramFlags` | `SymbolInfo` 统一包含 |
| `enterControlScope()` / `leaveControlScope()` | `enterScope()` / `leaveScope()` |

## 构造器传播

`IRBuilder(IRBuilder parent)` 应将 `scopeStack` 传入 nested builder：

```java
private IRBuilder(IRBuilder parent) {
    this.scopeStack = parent.scopeStack;  // 共享引用
    // ...其他字段...
}
```

这样 nested builder 可以直接查找父 builder 的变量。

## Known Function 解析

当前 `knownFunctions` 是一个 ThreadLocal Deque。改为在符号表中查找：

```
lookup(name) → SymbolInfo.func != null → 是已知函数 → 可以 INVOKE_DIRECT
            → SymbolInfo.flags & PARAM_LAZY → 参数惰性 → buildThunk
```

函数定义时：`scope.top().put(name, new SymbolInfo(func=irFunction))`。

## 实施步骤

### Phase 1: SymbolInfo + Scope + ScopeStack 数据结构

新建 `SymbolTable.java`，包含：
- `class SymbolInfo` — 符号条目
- `class Scope` — 单层作用域 (Map<String, SymbolInfo>)  
- `class ScopeStack` — 作用域栈 (Deque<Scope>)
- `enterScope()` / `leaveScope()`
- `define(name)` — 定义变量，检测遮蔽并重命名
- `lookup(name)` — 查找变量
- `resolveName(name)` — 返回当前有效的名字（mangledName 或 originalName）

### Phase 2: 替换 varIndex / varNames / paramFlags

IRBuilder 中：
- 移除 `varIndex`, `varNames`, `paramFlags`
- 替换为 `ScopeStack scopeStack`
- 所有 `ensureVar(name)` → `scopeStack.define(name)`
- 所有 `varIndex.get(name)` → `scopeStack.lookup(name).slot`
- 所有 `isCaptured.contains(name)` → `scopeStack.lookup(name).captured`

### Phase 3: 替换 savedVarBindings / enterControlScope

- 移除 `savedVarBindings`
- `enterControlScope()` → `scopeStack.enterScope()`
- `leaveControlScope()` → `scopeStack.leaveScope()`

### Phase 4: 替换 knownFunctions

- `registerFunction(name, poolIdx)` → `scopeStack.lookup(name).func = irFunction`
- `resolveKnownFunction(name)` → `scopeStack.lookup(name).func`

### Phase 5: 清理 globalSlots

- 名字冲突已由重命名解决，不需要 `globalSlots` 判断

### Phase 6: 传播到 nested IRBuilder

- `IRBuilder(IRBuilder parent)` 将 `scopeStack` 传给子 builder

## 示例：queens 问题

```
define queens(n) {          ← Scope[0]: {n→slot0}
    define scan(0) => [[]]
         | scan(i) => ...   ← Scope[0]: {scan→slot1(func=...)}
    define safe([], _, _) => true
         | safe([x:xs], y, n) =>
                              ← Scope[2] (case 1 for safe):
                                  x → slot3, mangledName="x"
                                  xs → slot4, mangledName="xs" 
                                  y → slot5, mangledName="y"
                                  n → slot6, mangledName="n$1"  ← RENAMED!
            ...safe(xs, y, n+1)  ← 体中对 n 的引用 → mangledName="n$1"
                                 ← 与外层 Scope[0] 的 "n" 不冲突
```

lambda 捕获 `n$1` → PUSH_GLOBAL "n$1" → 查找 evalContext → 找到 case scope 中的值 → 正确 ✅

内层 `n$1` 和外层 `n` 是不同的常量池条目 → 彼此完全隔离。
