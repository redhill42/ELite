# IR 优化 Pass 设计

## 目标

对 `IRBuilder` 生成的 IR 进行多 pass 后处理，消除编译期可判定的冗余指令：`PUSH_TRUE; JUMP_IF_FALSE`、不可达块、跳转链等。

## 架构

```
IRFunction (原始)
  │
  ├── PeepholePass      ← 块内局部模式替换
  ├── JumpThreadingPass ← 跳转到跳转块 → 直接跳转目标
  ├── DeadBlockPass     ← 消除不可达基本块
  ├── BlockFusionPass   ← 合并无分支的相邻块
  └── PeepholePass      ← 再跑一次（融合暴露新模式）
  │
  ▼
IRFunction (优化后)
```

每个 pass 的输入和输出都是 `IRFunction`（归一化格式），pass 之间通过 `blockOffsets` 和 `code[]` 传递信息。

---

## Pass 1: PeepholePass（块内窥孔优化）

不跨块，不改变 block 结构。单遍扫描每个块内的指令流，将已知的冗余模式替换为等价的更短序列。

### 规则

#### 1.1 必定跳转 / 永不跳转

| 模式 | 替换 | 说明 |
|------|------|------|
| `PUSH_TRUE; JUMP_IF_TRUE target` | `JUMP target` | 恒跳转 |
| `PUSH_FALSE; JUMP_IF_FALSE target` | `JUMP target` | 恒跳转 |
| `PUSH_TRUE; JUMP_IF_FALSE target` | 两条都删除 | 永不跳转，TRUE 无后续消费者 |
| `PUSH_FALSE; JUMP_IF_TRUE target` | 两条都删除 | 永不跳转 |

#### 1.2 无效果操作

| 模式 | 替换 | 说明 |
|------|------|------|
| `DUP; POP` | 删除两条 | 无净效果 |
| `PUSH_CONST #idx; POP` | 删除两条 | 死推入 |

#### 1.3 跳转到下一条指令

| 模式 | 替换 | 说明 |
|------|------|------|
| `JUMP B`，且 B 是 block 内紧接的下一条指令 | 删除 `JUMP` | 冗余跳转 |

#### 1.4 相同目标的条件跳转 + 无条件跳转

| 模式 | 替换 | 说明 |
|------|------|------|
| `JUMP_IF_TRUE B; JUMP B` | `JUMP B` | 条件无意义 |

#### 1.5 STORE_VAR 后跟 POP

`STORE_VAR` 将值存入 `locals[slot]` 同时压栈（dup 语义）。若后续紧跟 `POP`：

| 模式 | 替换 | 说明 |
|------|------|------|
| `STORE_VAR v; POP` | `STORE_VAR_NODUP v`（新 opcode）| 只存不推 |

> 注：`STORE_VAR_NODUP` 可以作为新的 opcode，也可用已有的 `STORE_VAR` + `POP` 保留。性能差异不大，设计层面先留下。

#### 1.6 空操作消除

`NOP`（opcode=0xFE）→ 从指令流中删除。常量折叠等 pass 用 `NOP` 标记已删除指令。

#### 实现策略

单遍扫描，用滑动窗口（2-3 条指令）匹配模式。生成新的 `int[]` 替换块内指令。

---

## Pass 2: JumpThreadingPass（跨块跳转优化）

当块 B 的最后一条指令是 `JUMP B_next`，且 `B_next` 块只包含 `JUMP B_target` 时，将 B 的跳转直接指向 `B_target`。

```
B:                    B:
  ...        →          ...
  JUMP B_next           JUMP B_target
    
B_next:                B_next:
  JUMP B_target          JUMP B_target    ← 可能变成不可达块
```

### 迭代

重复此过程直到收敛（每轮至少消除一条中间跳转）。

### 副作用

可能产生不可达块（没有任何跳转指向它们），交给 DeadBlockPass 清除。

---

## Pass 3: DeadBlockPass（不可达块消除）

### 步骤

1. 以 block 0 为起点，对控制流图做**可达性分析**（BFS/DFS 遍历 `JUMP`/`JUMP_IF_*` 的目标）。
2. 去掉不可达块。
3. 重排块 ID，更新所有跳转目标。

### 控制流图构建

从每个块的终止指令提取后继：
- `JUMP blockId` → `[blockId]`
- `JUMP_IF_TRUE blockId` → `[blockId, 下一个块]`
- `JUMP_IF_FALSE blockId` → `[下一个块, blockId]`
- `RETURN` / `RETURN_VOID` → `[]`
- `THROW` → `[]`

> 注意：ELite IR 的块是独立内存单元，不按 ID 串联。条件跳转的落空路径是 ID 顺序的下一个块。

---

## Pass 4: BlockFusionPass（相邻块合并）

若块 B_k 只包含无条件 `JUMP B_{k+1}`，且 `B_{k+1}` 无条件跳转指令的限制允许（即 B_{k+1} 没有其他前驱），则将两者合并为单一块，删除中间的 `JUMP`。

```
B_k:                  B_k:
  ...          →        ...
  JUMP B_{k+1}          {B_{k+1} 的内容}
                      {B_{k+1} 的终止指令}
B_{k+1}:
  ...
  JUMP B_target
```

### 条件

- B_k 的终止指令必须是 `JUMP B_{k+1}`
- B_{k+1} 只有 B_k 一个前驱（通过 CFG 分析确定）

---

## 调用点

在 `IRBuilder.compile()` 和 `IRBuilder.compile(ELProgram)` 的 `finishIR()` 方法中调用优化 pipeline：

```java
static IRFunction optimizeIR(IRFunction fn) {
    boolean changed;
    do {
        changed = false;
        fn = PeepholePass.run(fn);          // 必定执行
        changed |= JumpThreadingPass.run(fn); // 返回是否有变更
        fn = DeadBlockPass.run(fn);          // 跳转线程后可能有死块
        changed |= BlockFusionPass.run(fn);  // 合并相邻块
    } while (changed);
    return fn;
}
```

置于常量折叠之后，类型专门化之前（当前类型专门化禁用中）。

---

## 示例

### 输入 IR（来自 CONST_MATCH wildcard 模式）

```
B0:
  PUSH_VAR       v0       ← 匹配参数
  PUSH_TRUE              ← DEFINE("_") 总是匹配
  JUMP_IF_FALSE  B2       ← 冗余：恒为 TRUE，永不跳转
  ...                      ← guard/body 后续
```

### PeepholePass 之后

```
B0:
  PUSH_VAR       v0
                            ← PUSH_TRUE; JUMP_IF_FALSE 被删除
  ...
```

### 另一个例子

```
B2:
  JUMP_IF_TRUE   B6       ← 条件跳转
  JUMP           B5       ← 落空跳转（条件为 false）

B3:
  ...

B5:
  JUMP           B3       ← 仅包含跳转

B6:
  PUSH_TRUE              ← done 块
```

### JumpThreadingPass 之后

```
B2:
  JUMP_IF_TRUE   B6
  JUMP           B3       ← 直接跳到目标

B5:                       ← 变为不可达
  JUMP           B3

B3:
  ...
```

### DeadBlockPass 之后

B5 被移除，所有块 ID 重排。

---

## 暂不包含的优化

| 优化 | 原因 |
|------|------|
| 常量传播 | 需要数据流分析（def-use 链），复杂度高 |
| 公共子表达式消除 | 需要值编号，对动态类型语言收益有限 |
| 内联 | 已有 `InlinePass`，独立处理 |
| 循环优化（LICM 等） | IR 层的 loop 结构不足以支撑 |
| 全局栈深度分析 | 栈是执行时的，编译期难以精确追踪 |

---

## 新增 opcode（可选）

| Opcode | 用途 |
|--------|------|
| `STORE_VAR_NODUP` | `STORE_VAR` 不压 dup 的版本（PeepholePass 1.5 需要） |

或者：保持 `STORE_VAR; POP` 不变，让 JVM 字节码编译器处理消除（JVM 层的 store 本身就不压栈）。
