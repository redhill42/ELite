# TypeInference 子 Pass 重构设计

日期:2026-09-02
状态:已批准(方案 A:org.elite.ir.infer 子包 + 三阶段编排)

## 背景与目标

现 TypeInference(约 1000 行单类)以"每轮全规则扫描 + changed 检测"收敛,所有规则
混在一起,且复杂结点(ACCESS/APPLY/NEW/MATCH)的反射推导缺失(ACCESS 目标只认
List/Array,APPLY 只有粗粒度反射,实例字段/方法引用无类型)。

本重构把推断拆成三个子 Pass,并引入反射推导:

1. **Pass1**(执行一次):标注类型、常量类型、不可变变量类型。未推导出的结点
   保持 `inferredType == null`。
2. **Pass2**(可重复):运算符——所有操作数已推导的表达式推导出结果类型。
3. **Pass3**(可重复):ACCESS/APPLY/NEW/MATCH 等复杂结点,参考 IRBuilder 的做法,
   通过反射推导类型,**一边推导一边做语义检查**(将来 IRBuilder 不再需要这些
   检查;对已推导静态类型的结点直接生成静态分派指令,Any 结点仍动态分派)。

循环 Pass2+Pass3 直到所有结点非 null(含 Any)或达到迭代上限;上限/停滞时强制
剩余 null → Any,保证后置条件"所有结点 inferredType != null"。

## 非目标

- 本阶段**不修改 IRBuilder**(inferNodeType 保留;静态分派指令生成是下一阶段)
- 语义检查与 IRBuilder 现有检查**暂时双份**(用户已确认接受重复报错)
- 不实现基于类型的代码优化(下一步工作)
- "遗漏没关系":未覆盖的反射场景回退 Any,动态分派总是生效

## 类型系统新增

`org.elite.type` 新增两个类(载荷为反射对象,equals 按反射对象,toString 如下):

| 类 | 载荷 | toString 示例 |
|---|---|---|
| `JavaMethodType` | `java.lang.reflect.Method` | `JavaMethod[void PrintStream.println(String)]` |
| `JavaFieldType` | `java.lang.reflect.Field` | `JavaField[PrintStream]` |
| `JavaConstructorType` | `java.lang.reflect.Constructor<?>` | `JavaConstructor[Point(int, int)]` |

toString 格式:JavaMethod = `JavaMethod[<返回类型> <声明类SimpleName>.<方法名>(<参数类型,...>)]`;
JavaField = `JavaField[<字段类型SimpleName>]`;
JavaConstructor = `JavaConstructor[<声明类SimpleName>(<参数类型,...>)]`。
参数/返回/字段类型显示名沿用 PrimitiveType 别名规则(Int/Char/BigInt/void 等)。

**JavaConstructorType 落点(用户裁决)**:NEW/APPLY(类名直接调用)结点自身类型为
`JavaConstructorType`(记录具体构造器,供下阶段静态分派);需要**值类型**的消费点
(变量初始化、运算操作数、容器元素、比较、IDENT 传播等)统一经
`valueType(Type)` 解引用:JavaConstructorType → 声明类的 `JavaClassType`,其余原样。
`typeOf(node)` 保持原始类型;新增 `valueTypeOf(node) = valueType(typeOf(node))`
供 Pass2 的值消费规则与 Pass3 的接收者处理使用。JavaFieldType 同样经
`valueType` 解引用为其字段类型的类(接收者链 System.out.println 需要)。

## 架构:新子包 org.elite.ir.infer

| 类 | 职责 |
|---|---|
| `InferenceContext` | 共享状态与 helper:errors/reportError/failIfErrors、varTypes/assigns、eliteClasses、typeOf/setType/merge/normalize/compatible/数值族判定/loadJavaClass(自 TypeInference 迁移) |
| `Pass1` | 标注、常量、不可变变量(一次) |
| `Pass2` | 运算符与结构规则(可重复) |
| `Pass3` | 反射推导 + 语义检查(可重复) |
| `TypeInference` | 编排器:登记 ELite 类 → Pass1 → 循环(Pass2, Pass3)至收敛/上限 → 强制 Any |

原 `org.elite.ir.TypeInference` 类保留为**编排器 + 静态入口**(签名不变:
`infer(ELContext, ELProgram) throws ParseException`),规则迁移到子包。
原测试 TypeInferenceTest 的断言不变(行为等价)。

## 阶段划分细节

### Pass1(一次)

- 登记 ELite 类(def.symbol.clazz != null)
- `DEFINE` 标注 → 注解类型(权威,含兼容性检查与 ErrorType,沿用现有语义);
  不可变变量(无标注)→ 初始化表达式类型(递归 Pass1 内部先行推导子字面量)
- `LAMBDA.rtype`/`NEW.base` 注解解析(与现有 resolveAnnotationType 一致)
- 常量:`BOOLEANVAL/CHARVAL/STRINGVAL/NUMBER/NULL/NIL/SYMBOL/CONST`
- `CLASSDEF`/`CLASS` → `ELiteClassType`
- 一切其他结点保持 null

### Pass2(可重复)

- 算术/比较/逻辑/位运算/一元(现有规则,含 AnyInt/AnyFloat 归一化;
  操作数全非 null 才推导)
- 容器:CONS/TUPLE/MAP/ARRAY/RANGE(子元素全非 null 才推导;NIL→List[Any] 在 Pass1)
- `IDENT` 变量/函数引用(varTypes/symbol.def 已有类型时)
- `COND`/`COALESCE` 分支合并;`COMPOUND`/`LET`/`EXPR`/`RETURN`/`METADATA`/`METASET`
  子类型传播
- `FOREACH`/`WHILE`/`FOR`/`MATCH`/`CASE`(体/分支合并、循环变量元素类型)
- `ASSIGN`(rhs 类型 + 可变变量收集)、`ASSIGNOP`(左值类型)
- `LAMBDA` → FunctionType(参数注解 + 返回类型:标注权威,无标注取 body)
- 可变变量定型(recomputeVarTypes,现有冲突语义)
- `ACCESS` 的 List/Array 索引(目标为 ListType/ArrayType 时)

### Pass3(可重复,反射 + 语义检查)

参考 IRBuilder 的决策格(关键行号:ACCESS 778-875、APPLY 1059-1246、
NEW 4283-4358、resolveInstanceMethod/StaticMethod 1969-2000):

- **IDENT 按类名解析**:无符号、非变量的 IDENT → `ClassResolver.resolveClass`
  命中 → `JavaClassType`(System 场景第一步)
- **ACCESS 字段**:目标是 `JavaClassType` 且 index 为 STRINGVAL:
  - `cls.getField(name)` 命中 public 字段 → `JavaFieldType`
  - 缺失 → `reportError(EL_PROPERTY_NOT_FOUND)` + `ErrorType`
  - 非 public(`getDeclaredField` 命中但非 public)→ `EL_ILLEGAL_ACCESS` + ErrorType
- **ACCESS 方法引用**:字段未命中时按 IRBuilder 847-864 的做法,
  `MethodResolver.resolveStaticMethod/resolveMethod` 命中 → `JavaMethodType`
- **ACCESS 实例字段**:目标是任意已推导类型且 index 为 STRINGVAL → `getField`
  命中 → `JavaFieldType`;未命中回退 Any(v1 不做 getter 推断,遗漏可接受)
- **APPLY Java 方法调用**:目标是 `JavaMethodType` → 其 Method 的返回类型;
  目标是 `JavaClassType` 且右操作数为方法引用 ACCESS →
  `MethodResolver.resolveMethod(resolveStaticMethod)` +
  `getJavaMethod(args.length)` 命中 → 方法引用 ACCESS 记 `JavaMethodType`,
  调用结点结果类型 = 返回类型;
  方法不存在 → `reportError(EL_METHOD_NOT_FOUND)` + ErrorType;
  参数数量不符(getJavaMethod(arity)==null 但同名方法存在)→
  `reportError(EL_FN_BAD_ARG_COUNT)` + ErrorType
- **APPLY 构造器调用**:目标是 `JavaClassType` 且右操作数为按类解析的 IDENT
  (IRBuilder buildJavaClassCall 形态)→ 构造器扫描同 NEW,命中 → 调用结点
  `JavaConstructorType`(值类型经解引用)
- **NEW**:base 是 ELite 类 → `ELiteClassType`;Java 类 →
  `getConstructors()` 中 public 非 varargs 且参数数匹配者唯一 → 结点
  `JavaConstructorType`(值类型经解引用);
  无匹配 → `reportError(EL_CONSTRUCTOR_NOT_FOUND)` + ErrorType;
  多个匹配 → `JavaClassType`(无法记录具体构造器,动态分派,记 TODO)
- **MATCH**:Pass2 已处理结果合并;`::Type` 模式守卫的类型在注解解析范围
- 前置条件不满足(目标未推导)时保持 null,下轮重试

### 收敛与终止

- `TypeInference.infer`:
  1. `Pass1` 一次
  2. 循环:`progress = false`;Pass2(置 progress 若有新类型);Pass3(同);
     直到全部非 null 或 !progress 或轮数 ≥ 50
  3. 末尾强制:遍历全树,`inferredType == null` → `AnyType.ANY`
- 终止证明:每个结点最多从 null → 具体/Any 一次;Any 是终态(现有规则不回改);
  具体类型单调(与现有语义一致)——最多 50 轮必然覆盖全树或停滞

## 走查示例:System.out.println("hello")

| 结点 | 轮次 | 类型 |
|---|---|---|
| `"hello"` | Pass1 | String |
| `System`(IDENT,无符号) | Pass3 轮1 | System |
| `System.out`(ACCESS) | Pass3 轮1 | JavaField[PrintStream](getField) |
| `System.out.println`(ACCESS) | Pass3 轮1 | JavaMethod[void PrintStream.println(String)] |
| `println("hello")`(APPLY) | Pass3 轮1 | void |

构造器示例(`define p = new java.util.ArrayList()`):NEW 结点 →
`JavaConstructor[ArrayList()]`,`p` 经解引用 → `ArrayList`。

全部静态类型;下一阶段编译器可据此生成与 Java 无差别的字节码。

## 语义检查清单(v1,与 IRBuilder 双份)

| 检查 | 消息 | 触发 |
|---|---|---|
| 字段缺失 | EL_PROPERTY_NOT_FOUND | ACCESS JavaClass 字段未命中 |
| 字段非 public | EL_ILLEGAL_ACCESS | getDeclaredField 命中但非 public |
| 方法缺失 | EL_METHOD_NOT_FOUND | APPLY 方法未命中 |
| 参数数量不符 | EL_FN_BAD_ARG_COUNT | 同名方法存在但 arity 不匹配 |
| 构造器缺失 | EL_CONSTRUCTOR_NOT_FOUND | NEW 无匹配公开构造器 |

与 IRBuilder 现有检查并存(用户决定:重复可接受,下阶段删 IRBuilder 侧)。

## 验证策略

- **JUnit**:
  - `JavaFieldType/JavaMethodType` toString/equals(含 System.out.println 格式)
  - Pass1 后未推导结点为 null(例如 `define x = 42` 的 PASS2 类结点)
  - System.out.println("hello") 全静态化走查(逐结点断言)
  - 字段/方法/构造器缺失 → assertThrows(ParseException,消息含对应 key)+ ErrorType
  - 现有 TypeInferenceTest 44 个测试全部保持通过(行为等价)
  - 收敛:全部非 null 后置条件(含 Any)
- **手工**:`xel --dump-ast -e 'System.out.println("hello")'` 目视各结点类型
- 全量回归 874+ 绿

## 分步实施

1. JavaMethodType/JavaFieldType/JavaConstructorType + 测试
2. InferenceContext + Pass1(迁移 + 一次性语义)+ 编排骨架(暂单轮)+ 测试
3. Pass2 迁移(现有规则归类,值消费点改 valueTypeOf)+ 现有测试全绿
4. Pass3 反射推导(ACCESS/APPLY/NEW 含构造器)+ 语义检查 + 测试
5. 编排循环/收敛/强制 Any + System.out.println 与 ArrayList 构造器走查测试
   + 手工验证
6. 全量回归与文档同步

每步独立提交、可独立验证。
