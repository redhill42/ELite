# ELite 类型推导设计

日期:2026-09-02
状态:已批准(方案 A,不接 IRBuilder,消费方仅 --dump-ast)

## 背景与目标

ELite 目前没有任何静态类型分析:注解(`x::Int`、`define f(...)::T`)只作为类名
文本存在于 `DEFINE.type` / `LAMBDA.rtype`,运行时由 `TypedClosure` 守卫。
IRBuilder 注释明言 "we lacks type inferrer"。

本功能引入独立的类型模型与类型推导遍:在 `SymbolTableBuilder` 构造符号表之后,
对 `org.elite.parser.ELNode` AST 做多轮推导直至收敛,结果记录在
`ELNode.inferredType`。**本阶段不接入 IRBuilder**,唯一消费方是 `--dump-ast`
(在 AST dump 中显示推导类型)。推导是尽力而为:推导不出标注 `Any`,不产生错误。

## 非目标

- 不接入 IRBuilder / 不驱动任何代码生成优化(后续阶段)
- 不做类型错误检查(注解与推导结果不符时不报错)
- 不改动 `elite.ast.*`(javax.el 门面)
- 不改动现有 `ELNode.dump()` / `ASTDumper.dump` 的输出格式

## 类型模型:新包 `org.elite.type`

所有类型类不可变(全 final 字段、equals/hashCode、toString),基类:

```java
public abstract class Type {
  public abstract String toString();   // 供 dump-ast 显示与测试断言
}
```

| 类 | 附加信息 | toString 示例 |
|---|---|---|
| `PrimitiveType` | `Class<?> javaClass`(原始类或装箱类) | 按下方别名显示名规则(如 `Int`、`Long`) |
| `PrimitiveType.AnyInt` | 提升链标记(Int→Long→BigInt) | `AnyInt` |
| `PrimitiveType.AnyFloat` | 提升链标记(Float→Double→BigDecimal) | `AnyFloat` |
| `JavaClass` | `Class<?> javaClass` | `java.util.Date` 之类 simple name |
| `ELiteClass` | ELite class 定义(符号表条目) | 类名 |
| `List` | 元素 `Type` | `List[Int]` |
| `Tuple` | 成员 `Type[]` | `Tuple[String, Int]` |
| `Map` | key `Type`、value `Type` | `Map[String, Int]` |
| `Array` | 元素 `Type`、维数 int | `Array[Int, 2]` |
| `Function` | 返回 `Type`、参数 `Type[]` | `Function[Int, [Int, Int]]` |
| `Any` | 单例 | `Any` |

- `PrimitiveType` 覆盖:所有 Java 原始类型及装箱类型、`BigInteger`、`BigDecimal`、
  `elite.lang.Decimal`、`elite.lang.Rational`、`String`、`void`(Void.TYPE)。
  原始类型与装箱类型归一为同一类型实例(如 `int` 与 `Integer` 都显示 `Int`)。
- 别名显示名:`Integer`→`Int`、`Character`→`Char`、`BigInteger`→`BigInt`,
  其余用类 simple name;无别名的原始类型名按语言习惯(`Long`/`Float`/`Double`/
  `Boolean`/`Byte`/`Short`/`String`/`void`)。
- 类型相等的定义:结构相等(`List` 比较元素类型,`Tuple` 比较成员序列,依此类推;
  `JavaClass`/`PrimitiveType` 比较 Class 恒等;`AnyInt`/`AnyFloat`/`Any` 各自单例)。

## 注解解析

`DEFINE.type` / `LAMBDA.rtype` 是类名 ELNode(IDENT/ACCESS 链,`getFullClassName`
取全名)。解析顺序:

1. 查符号表:是 ELite class → `ELiteClass`
2. 否则查别名表:`Int`→`Integer`、`Char`→`Character`、`BigInt`→`BigInteger`
   (同时接受装箱类全名 `java.lang.Integer` 等形式)
3. 否则按 Java 类解析(原始类型名 `int`/`long`/`double`/`void`/... 解析为
   `X.TYPE`);失败 → `Any`
4. 解析结果命中 PrimitiveType 表 → `PrimitiveType`,否则 → `JavaClass`

解析失败不报错,回退 `Any`。ELite class 定义在推断开始前先整体登记
(第一遍扫描收集所有 CLASSDEF,保证注解能解析出 `ELiteClass`)。

## 推断遍:新类 `org.elite.ir.TypeInference`

- 实现 `DefaultVisitor`(与 `SymbolTableBuilder` 的 `BuilderVisitor` 同模式),
  静态入口 `TypeInference.infer(ELProgram program)`:
  重复 `visit(program)` 直到**整棵 AST 的 inferredType 无任何变化**,或达到
  上限 20 轮(递归/循环依赖不收敛时,未收敛节点保持 `Any`;规则设计保证单调,
  正常应远少于 20 轮收敛)。
- 每轮开始时清空"已变化"标记;`setType(node, type)` 仅在类型真正变化时写入
  `node.inferredType` 并置变化标记。
- 结果写入新字段 `ELNode.inferredType`(公开非 transient)。
- **接入点:仅 `CommandProvider.dump_ast`**——`SymbolTableBuilder.build(program)`
  之后、dump 之前调用 `TypeInference.infer(program)`。IRBuilder 不调用。

## 节点推导规则

通用原则:推导不出 → `Any`;`Any` 是吸收元素(与任何类型合并/参与运算 → `Any`
或对应 Any 族)。

**字面量与常量**
- `BOOLEANVAL` → `Boolean`;`CHARVAL` → `Char`;`STRINGVAL` → `String`
- `NUMBER`:整数值按量级 → `Int`/`Long`/`BigInt`;浮点值 → `Double`
- `NULL` → `Any`;`NIL`(空列表)→ `List[Any]`;`SYMBOL` → `Any`
- `Composite`(字符串插值)→ `String`

**变量与引用**
- `IDENT`:查符号表,取其对应定义/函数/类的推断类型;未定义 → `Any`
- `DEFINE`(变量):标注类型 → 注解类型(权威,不提升);否则见"变量处理"一节

**一元/二元运算**
- `!` → `Boolean`;`-x`(取负)→ 数值规则(见"数值提升与归一化")
- 算术 `+ - * / % **` 等:数值规则;若任一操作数为 `String` 且运算符是 `~`
  (或 `+` 的字符串路径)→ `String`
- 比较 `== != < <= > >=`、逻辑 `&& ||`、`in`/`instanceof` → `Boolean`
- 位运算:布尔操作数 → `Boolean`,否则按整型族规则
- `CONS` → `List[归一化(元素类型, 尾部元素类型)]`;`TUPLE` → `Tuple[...]`;
  `MAP` → `Map[K, V]`(K/V 各自归一化);`RANGE` → `JavaClass(elite.lang.Range)`
- `SEQ`/序列生成器 → `Any`(v1 不展开惰性序列元素类型)

**数组与对象**
- `ARRAY` 字面量 → `Array[元素, 1]`;`NEW` 数组 → `Array[元素, 维数]`
- `NEW` Java/ELite 类 → `JavaClass` / `ELiteClass`

**访问与调用**
- `ACCESS`:对 `Array`/`List` 按索引 → 元素类型;其余(属性/字段)→ `Any`
  (v1 不做 Java 字段反射)
- `APPLY`:
  - 目标是 `Function`(用户函数/闭包)→ 其返回类型
  - 目标是标注了返回类型的 DEFINE → 注解类型(不提升)
  - Java 方法(目标是 `JavaClass`)→ 反射取方法返回类型(v1 实现,失败回退 `Any`)
- `XFORM` → 子节点类型

**控制流**
- `COND`/`COALESCE` → 分支类型归一化合并
- `RETURN` → 表达式类型;LAMBDA 返回类型 = 所有 RETURN 归一化合并
- `WHILE`/`FOR`/`FOREACH`/`LET`/`ASSERT`/`TRY` 等语句块 → 最后表达式类型;
  `FOREACH` 循环变量类型 = 集合元素类型
- `MATCH` → 各 CASE 结果归一化合并;`CASE` 模式中带注解的 DEFINE 绑定按注解类型
- `ASSIGN` → 右操作数类型;`ASSIGNOP` **不处理**(按约定不改变变量类型,
  自身类型取左操作数当前类型)
- `METADATA`/`METASET` → 子节点类型

**函数与类**
- `LAMBDA` → `Function[返回类型, 参数类型]`;参数有注解按注解,无注解按
  `Any`(参数类型不从调用方反向推导,v1 简化)
- `DEFINE`(函数,expr 为 LAMBDA)→ 其 LAMBDA 的 Function 类型
- `CLASSDEF`/`CLASS` → `ELiteClass`(第一遍预登记,供注解解析与自引用)

## 变量处理

- **不可变变量**(`Symbol.immutable == true`,SymbolTableBuilder 已分析):
  取初始化表达式推断类型;有标注则取标注类型
- **可变变量**(存在 ASSIGN):收集该变量**所有 ASSIGN 结点的右操作数类型**,
  两两归一化合并;合并结果一致且非 `Any` → 固定该类型,否则 `Any`
- 合并规则:逐对应用二元数值归一化(如 `Int` 与 `Long` → `Long`);
  `Any` 参与 → `Any`;不同类型族(如 `Int` 与 `String`)→ `Any`
- ASSIGN 左值有类型标注:变量类型即标注类型,忽略右操作数(标注不提升)
- ASSIGNOP 不参与变量类型计算

## 数值提升与归一化

- **标注上下文不提升**:`define add(x::Int, y::Int)::Int => x+y` 中,`x+y`
  的类型为 `Int`(返回类型标注权威);变量标注同理
- **未标注的整型算术** → `AnyInt`;浮点算术 → `AnyFloat`
  (非编译期可证安全,运行时可能沿链提升)
- **常量折叠**:整型常量运算结果按提升链 `Int → Long → BigInt` 取能容纳的最窄
  类型;浮点按 `Float → Double → BigDecimal`。`BigInt`/`BigDecimal` 参与 →
  对应具体类型(链顶,不再提升)
- **混合类型归一化**(二元运算,依据 TypeCoercion 数值距离序):
  - 两个具体整型族:取链上更宽者(`Int + Long → Long`)
  - 两个具体浮点族:取链上更宽者
  - 整型族 + 浮点族 → 浮点侧类型
  - 任一为 `AnyInt`/`AnyFloat` → 对应 `AnyInt`/`AnyFloat`(整浮混合时浮点侧优先)
  - `Decimal`/`Rational` 参与 → 按 TypeCoercion 距离序取更宽者;
    与整型族混合 → 按距离序(如 `Int + Decimal → Decimal`)
- 比较运算永远 `Boolean`,不涉及提升

## 消费方:--dump-ast

- `ASTDumper` 新增 `dumpTyped(ELProgram)`(及 `dumpTyped(ELNode)`):
  基于现有 dump 输出,在每个节点行追加 ` :: <Type>`;无推断类型(未跑推断)的
  节点不追加。现有 `dump()` 输出不变。
- `CommandProvider.dump_ast` 改为:
  parse → `SymbolTableBuilder.build` → `TypeInference.infer` →
  `System.out.println(ASTDumper.dumpTyped(program))`
- 其余 dump 用户(`dump-ir` 等)与现有测试不受影响

## 验证策略

- **JUnit**(与现有 817 测试同框架):`TypeTest`(类型模型 toString/equals、
  别名显示、AnyInt/AnyFloat)+ `TypeInferenceTest`(解析脚本 →
  `SymbolTableBuilder.build` + `TypeInference.infer` → 断言关键节点
  `inferredType.toString()`),覆盖:字面量、标注变量/参数/返回类型、
  AnyInt/AnyFloat、归一化、List/Tuple/Map/Array、Function、
  不可变/可变变量、收敛(多轮)、递归回退 Any、注解不提升
- **手工**:`bin/xel --dump-ast` 对样例脚本目视检查类型标注输出

## 分步实施(每步独立提交、可独立验证)

1. **类型模型**:`org.elite.type` 包全部类 + `TypeTest`
2. **ELNode.inferredType 字段 + 注解解析**(别名表、ELite class 登记、
   Java 类解析)+ 测试
3. **TypeInference 骨架**:visitor、收敛循环、字面量/IDENT/一元/比较/逻辑规则
   + 测试
4. **容器与结构**:List/Tuple/Map/Array/RANGE/CONS/ACCESS/Composite + 测试
5. **函数与变量**:LAMBDA→Function、DEFINE、APPLY(用户函数 + Java 反射)、
   RETURN、可变/不可变变量、ASSIGNOP 约定 + 测试
6. **数值提升与归一化**:AnyInt/AnyFloat、常量折叠提升链、混合归一化 + 测试
7. **消费方**:ASTDumper.dumpTyped + CommandProvider.dump_ast 接入 +
   手工验证 + 全量回归(817)
