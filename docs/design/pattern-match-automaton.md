# Pattern Match 表驱动自动机设计

## 1. 模式类型全谱系

所有实现 `ELNode.Pattern` 接口并实现 `matches(context, value)` 的结点类型：

### 1.1 纯谓词（无副作用，无子模式）

| 模式 | 判定方式 | IR 可编译 |
|------|---------|-----------|
| `NUMBER(v)` | `EQ.equals(value, v)` | `PAT_EQ #constIdx` |
| `STRINGVAL(s)` | `value.equals(s)` | `PAT_EQ #constIdx` |
| `SYMBOL(:sym)` | `value == sym` | `PAT_IDENTITY #constIdx` |
| `BOOLEANVAL(b)` | `coerceToBoolean(value) == b` | `PAT_BOOL #constIdx` |
| `CHARVAL(c)` | `coerceToChar(value) == c` | `PAT_EQ #constIdx` |
| `NULL` | `value == null` | `PAT_IS_NULL` |
| `REGEXP(/re/)` | `regex.matcher((String)value).matches()` | `PAT_REGEX #constIdx` |
| `CLASS(Type)` | `TypedClosure.typecheck(type, value)` | `PAT_INSTANCEOF #constIdx` |
| `NIL` | 空 `List` 或空 `CharSequence` | `PAT_IS_EMPTY` |
| `RANGE(a..b)` | `List.contains(value)` | 暂保留 trampoline |
| `NOT(!pat)` | `!pat.matches(value)` | 编译为内部模式的逆 |

### 1.2 变量绑定（有副作用）

| 模式 | 行为 | IR 可编译 |
|------|------|-----------|
| `DEFINE(x)` | 未绑定 → `setVariable(x, value)`；已绑定 → `EQ.equals(old, value)` | `PAT_BIND #nameIdx` |
| `DEFINE(_)` | 永远匹配，不绑定 | NOP |
| `DEFINE(x::Type)` | 同上 + typecheck | `PAT_BIND #nameIdx, #typeIdx` |
| `DEFINE(x @ subpat)` | as-pattern: 子模式也需匹配 | 绑定 + 递归编译子模式 |

### 1.3 结构化分解（有子模式 + 递归匹配）

| 模式 | 分解方式 | IR 可编译 |
|------|---------|-----------|
| `TUPLE(a, b, c)` | `Array.getLength() == n` + 逐元素 `pat[i].matches(array[i])` | `PAT_DECONS_TUPLE n` |
| `CONS(h, t)` | `Seq.head()` + `Seq.tail()` 递归匹配 | `PAT_DECONS_CONS` |
| `MAP(k1: v1, k2: v2)` | `ELResolver.getValue(base, key)` 逐键匹配 | 暂保留 trampoline |
| `NEW(Cls(a,b))` | `ClassDefinition.matches(value)` 或 `DataClass` slot 匹配 | 暂保留 trampoline |

### 1.4 控制流模式

| 模式 | 行为 | IR 可编译 |
|------|------|-----------|
| `OR(pat1 \| pat2)` | 尝试 pat1，失败则回退绑定重试 pat2 | `PAT_OR_BEGIN` / `PAT_OR_COMMIT` / `PAT_OR_END` |
| `EXPR(#expr)` | 运行时求值 + 相等比较 | 暂保留 trampoline |

---

## 2. 匹配流程分析

### 2.1 MATCH 求值（当前 AST 路径）

```
match(args...) {
  case pat1_row => body1
  case pat2_row => body2
}
```

```
1. 求值所有 args → values[]
2. 创建临时 VariableMapperImpl + pushContext
3. 遍历 CASE:
   3a. 每个 pattern[i] 匹配 values[i]
   3b. 若全部匹配 + guard 通过 → 返回 body
   3c. 若失败 → map.clear(), 继续下一个 CASE
4. 无匹配 → default 或 throw
```

### 2.2 关键运行时状态

```
┌──────────────┐
│  values[]     │ ← 已求值的匹配参数（只读）
├──────────────┤
│  bind_frame   │ ← 模式变量绑定表 (name → value)
│   [slot 0]    │
│   [slot 1]    │
│   ...         │
├──────────────┤
│  or_save[]    │ ← OR 回溯时的绑定快照
└──────────────┘
```

---

## 3. 决策自动机设计

### 3.1 核心思想

将 AST 解释执行的 `CASE.matches()` → `Pattern.matches()` 递归调用链
编译为一个**扁平的、基于栈的指令序列**，使用专用 opcode 替代 AST 方法调用。

### 3.2 执行模型

```
┌──────────────────────────────────────────────────┐
│                   Pattern Frame                     │
│  (类似于 IR 的 Frame，但专用于模式匹配)              │
│                                                    │
│  int[] bindSlots   ← 每个模式变量的槽位索引           │
│  Object[] bindVals  ← 绑定的值                       │
│  int orDepth       ← OR 嵌套深度                    │
│  int[] orSnapshots ← OR 回溯点保存的 bindVals 快照    │
└──────────────────────────────────────────────────┘
```

### 3.3 新增 IR Opcode

```
模式匹配框架:
  MATCH_BEGIN  nargs, nvars    ← 创建 bind frame
  MATCH_VALUE  argIdx          ← 将 values[argIdx] 压栈
  MATCH_END                    ← 清理 frame, 推进到下一条指令（跳出匹配）

简单谓词:
  PAT_EQ       #constIdx       ← 检查栈顶 == constant
  PAT_IDENTITY #constIdx       ← 检查栈顶 === constant
  PAT_BOOL     #constIdx       ← coerceToBoolean(栈顶) == constant
  PAT_IS_NULL                  ← 检查栈顶 == null
  PAT_IS_EMPTY                 ← 检查栈顶为空序列/字符串
  PAT_REGEX    #constIdx       ← regex.matcher(栈顶).matches()
  PAT_INSTANCEOF #constIdx     ← TypedClosure.typecheck
  PAT_NOT                      ← 取反上一次 PAT 结果

变量绑定:
  PAT_BIND     #nameIdx        ← bind_frame[name] = 栈顶 (或检查相等)
  PAT_UNBIND   n               ← 回退最后 n 个绑定

结构化分解:
  PAT_DECONS_TUPLE n            ← 栈顶是数组 → 弹出, 压入 n 个元素
  PAT_DECONS_CONS               ← 栈顶是 Cons → 弹出, 压入 head, tail

OR 回溯:
  PAT_OR_BEGIN                  ← 保存当前 bind frame 快照
  PAT_OR_TRY_TO  offset         ← 若上次 PAT 失败 → 恢复快照, 跳转 offset
  PAT_OR_COMMIT                 ← 丢弃快照 (已匹配)

守卫:
  PAT_GUARD    blockId          ← 若栈顶 false → 跳转 blockId (失败路径)

Case 调度:
  CASE_JUMP    caseIdx          ← 无条件跳转去尝试指定 case
  CASE_TRY     caseIdx, failB   ← 尝试匹配; 失败 → 跳转 failB
```

### 3.4 编译算法

```
compileMatch(MATCH node):
    // Step 1: 求值所有 match args
    for each arg in node.args:
        build(arg)
        emit(STORE_VAR, tmpSlot[argIdx])  // 暂存到临时槽位

    // Step 2: 收集所有 case 中引用的变量, 分配 bind slots
    bindVars = collectBindVariables(node.alts)
    emit(MATCH_BEGIN, node.args.length, bindVars.size())

    // Step 3: 为每个 case 编译匹配块
    exitBlock = allocBlockId()
    for each case in node.alts:
        caseBlock = allocBlockId()
        compileCase(case, caseBlock)

    // Step 4: Default / error
    if node.deflt != null:
        build(node.deflt)
        emit(JUMP, exitBlock)
    else:
        emit(THROW, "no pattern matched")

    sealAndStart(exitBlock)
    emit(MATCH_END)


compileCase(CASE node, caseBlock):
    // 编译一系列 pattern 检查
    for each column (pattern per arg):
        for each pattern in column:
            compilePattern(column[i], valueStack[column])

    // Guard
    failBlock = allocBlockId()  // 下一个 case 的入口
    if node.guards:
        for each guard:
            build(guard)
            emit(PAT_GUARD, failBlock)

    // Body
    build(node.bodies[i])
    emit(JUMP, exitBlock)

    // Next case entry point
    sealAndStart(failBlock)
```

### 3.5 模式编译规则

```
compilePattern(pattern):
    match pattern:
        DEFINE("_") → NOP
        DEFINE(name) → PAT_BIND #name
        DEFINE(name, type) → PAT_INSTANCEOF #type + PAT_BIND #name
        DEFINE(name, sub@pat) → compilePattern(sub) + PAT_BIND #name
        NUMBER(v) → PAT_EQ #pool[v]
        STRINGVAL(s) → PAT_EQ #pool[s]
        SYMBOL(:s) → PAT_IDENTITY #pool[s]
        NULL → PAT_IS_NULL
        CLASS(T) → PAT_INSTANCEOF #pool[T]
        NIL → PAT_IS_EMPTY
        NOT(pat) → compilePattern(pat) + PAT_NOT
        CONS(h, t) → PAT_DECONS_CONS + compilePattern(h) + compilePattern(t)
        TUPLE(elems) → PAT_DECONS_TUPLE n + for each e: compilePattern(e)
        OR(left, right) → PAT_OR_BEGIN
                          + compilePattern(left) + PAT_OR_TRY_TO rightLbl
                          + PAT_OR_COMMIT
                          + rightLbl: compilePattern(right)
                          + PAT_OR_COMMIT
```

---

## 4. 变量绑定管理

### 4.1 Bind Frame 结构

```
bindFrame:
  names: String[]          // 变量名（编译期确定）
  slots:  int[]            // IR 层的局部槽位
  values: Object[]         // 绑定的值
  orStack: Deque<int>      // OR 回溯点 (bindCount 快照)
```

### 4.2 PAT_BIND 语义

```
PAT_BIND nameIdx:
    val = pop()
    slot = bindFrame.slotOf(name)
    if bindFrame.values[slot] == UNSET:
        bindFrame.values[slot] = val   // 首次绑定
        push(TRUE)
    else:
        push(EQ.equals(val, bindFrame.values[slot]))  // 检查相等
```

### 4.3 OR 回溯

```
PAT_OR_BEGIN:
    orStack.push(bindFrame.bindCount)

PAT_OR_TRY_TO offset:
    if peek() == FALSE:                 // 上次匹配失败
        bindFrame.rollback(orStack.pop()) // 回退绑定
        // 继续执行（进入 OR 的右侧）

PAT_OR_COMMIT:
    orStack.pop()                        // 匹配成功, 丢弃快照
```

---

## 5. 实施路径

### Phase 1: 简单字面量匹配 (MATCH 编译框架)

**覆盖**: NUMBER, STRINGVAL, SYMBOL, NULL, BOOLEANVAL  
**不覆盖**: 变量绑定, OR, 结构分解, 守卫  
**产出**: `MATCH_BEGIN`, `MATCH_VALUE`, `PAT_EQ`, `PAT_IS_NULL`, `MATCH_END`, `CASE_TRY`

对应 `CONST_MATCH` 场景 — 无变量绑定的简单值匹配。

### Phase 2: 变量绑定 + 守卫

**覆盖**: DEFINE, PAT_BIND, PAT_UNBIND, PAT_GUARD  
**不覆盖**: OR, 结构分解  
**产出**: 完整的单参数模式匹配

### Phase 3: OR 回溯 + 结构化分解

**覆盖**: OR, PAT_OR_BEGIN/TRY/COMMIT, TUPLE, CONS  
**不覆盖**: MAP, NEW, RANGE（保留 trampoline）  
**产出**: 覆盖 rbtree.xel 中使用的 `| Pat(...) => body` 语法

### Phase 4: 多列 + 完整覆盖

**覆盖**: 多参数 match, MAP, NEW, REGEXP, NOT  
**产出**: 全面消除 MATCH trampoline

---

## 6. 不编译的模式（永久 trampoline）

以下模式由于依赖运行时信息，不适合编译：

| 模式 | 原因 |
|------|------|
| `EXPR(#expr)` | 表达式在编译期不可知 |
| `RANGE(a..b)` | 范围值运行时计算 |
| `NEW(Cls(...))` 的部分情况 | ClassDefinition 在编译期不可用 |
| `MAP(key: pat, ...)` 的部分情况 | ELResolver 解析是运行时行为 |

这些模式可以编译为一个内建的 TRAMPOLINE 调用（比整体 MATCH trampoline 粒度更细）。
