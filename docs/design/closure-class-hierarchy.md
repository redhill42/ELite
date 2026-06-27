# ELite Closure 类层次结构

## 概述

`elite.lang.Closure` 是 ELite 语言中**所有可计算对象的抽象基类**。它封装了一个表达式及其关联的求值上下文，是整个对象系统的核心抽象。Closure 继承自 `javax.el.ValueExpression`，使得每个 Closure 同时也是一个可求值的表达式。

Closure 及其派生类覆盖了以下计算场景：

| 场景 | 对应的 Closure 类 |
|------|------------------|
| ELite lambda 表达式 | `Procedure`, `EvalClosure` |
| IR 编译后的函数 | `IRClosure` |
| 用户自定义 class | `ClassDefinition` + `ThisObject` 系列 |
| @data 构造器 | `DataClass` |
| Java 对象/方法包装 | `MethodClosure`, `FieldClosure`, `LiteralClosure` |
| 动态扩展 (expando) | `ExpandoMethodClosure` |
| 惰性求值 (lazy/thunk) | `DelayClosure`, `DelayEvalClosure` |
| 类型强制装饰器 | `TypedClosure` |
| 装饰器/代理模式 | `DelegatingClosure` 系列 |
| 求值上下文 | `EvaluationContext` |
| XML 命名空间 | `Namespace` |

---

## 类层次总图

```
javax.el.ValueExpression
  │
  └── elite.lang.Closure  (abstract)
       │
       ├── AbstractClosure  (abstract)
       │    ├── FieldClosure
       │    ├── MethodClosure  (abstract)
       │    │    ├── JavaMethodClosure  (abstract)
       │    │    │    ├── SingleMethodClosure
       │    │    │    └── MultiMethodClosure
       │    │    └── ExpandoMethodClosure
       │    ├── ThisObject  (abstract, implements ClosureObject + Serializable)
       │    │    ├── BasicThisObject
       │    │    │    ├── DerivedThisObject
       │    │    │    └── ProxiedThisObject
       │    │    └── DelegatedThisObject
       │    ├── DataClass
       │    ├── DefaultClosureObject  (implements ClosureObject + Serializable)
       │    ├── EvaluationContext  (also implements PropertyDelegate)
       │    ├── EnvExtent.ScopedClosure  (private)
       │    ├── ClassDefinition.InitProc  (private)
       │    ├── ClassDefinition.ToStringProc  (private)
       │    ├── ClassDefinition.EqualsProc  (private)
       │    ├── ClassDefinition.HashCodeProc  (private)
       │    ├── ClassDefinition.CompareToProc  (private)
       │    ├── Closure.Compose  (private)
       │    └── Closure.Power  (private)
       │
       ├── AnnotatedClosure  (abstract)
       │    ├── DelayClosure  (abstract)
       │    │    ├── DelayEvalClosure
       │    │    │    ├── VarClosure
       │    │    │    ├── IRInterpreter.Thunk  (private)
       │    │    │    └── Grammar.ExpressionClosure  (private)
       │    │    ├── Builtin.DelayHead  (private)
       │    │    └── Builtin.FoldRightRec  (private)
       │    ├── EvalClosure
       │    │    └── Procedure
       │    ├── LiteralClosure
       │    │    ├── ContextVariableMapper.ContextValueExpression
       │    │    ├── ContextVariableMapper.StdIn
       │    │    ├── ContextVariableMapper.StdOut
       │    │    └── ContextVariableMapper.StdErr
       │    ├── ClassDefinition  (implements PropertyResolvable + MethodResolvable + Serializable)
       │    └── Namespace  (implements Coercible)
       │
       ├── DelegatingClosure  (abstract)
       │    ├── TypedClosure
       │    │    └── JavaTypedClosure  (private inner)
       │    ├── ClosureTypedClosure  (extends DelegatingClosure, private inner of TypedClosure)
       │    ├── NamedClosure
       │    ├── TargetMethodClosure
       │    ├── BasicThisObject.ExpandoClosure  (private)
       │    └── BasicThisObject.SynchronizedClosure  (private)
       │
       └── IRClosure
```

---

## 三大分支

从 `Closure` 派生出三个平行的抽象分支，分别服务于不同的设计目标：

### 分支一：`AbstractClosure` — 对象与方法的封装

提供 `ValueExpression` 接口的默认实现。`getValue()` 返回 `this`（闭包自身即其值），`setValue()` 抛出 `PropertyNotWritableException`。这个分支主要封装**可被求值、调用的对象**，包括 Java 方法的包装、ELite 对象实例（ThisObject）、数据类等。

**核心特征**：`getValue()` → `this`；子类需实现 `invoke(ELContext, Closure[])`。

### 分支二：`AnnotatedClosure` — 元数据与惰性求值

在 `Closure` 基础上增加了**注解（annotation）和修饰符（modifier）**支持。这是 ELite 类型系统在 runtime 的体现——每个通过 `AnnotatedClosure` 派生的闭包都可以携带 `private`/`static`/`abstract`/`final` 等修饰符，以及 ELite 的 annotation。

这个分支包含：
- **惰性求值链**：`DelayClosure` → `DelayEvalClosure` → `VarClosure`
- **AST 求值链**：`EvalClosure` → `Procedure`
- **字面量包装**：`LiteralClosure`（将任意 Java Object 包装为 Closure）
- **类定义**：`ClassDefinition`（ELite class 的 runtime 表示）
- **命名空间**：`Namespace`（XML 命名空间）

### 分支三：`DelegatingClosure` — 装饰器模式

实现 **Decorator 模式**。持有一个 `delegate: Closure` 字段，将所有方法调用转发给被装饰的闭包。子类在转发前后插入额外行为：

- `TypedClosure`：在 get/set 时进行类型强制转换
- `NamedClosure`：附加一个字符串名称（用于 keyword arguments）
- `TargetMethodClosure`：将 MethodClosure 绑定到特定目标对象
- `ExpandoClosure`：在调用参数前追加 `this` 对象
- `SynchronizedClosure`：在 `synchronized(proxy)` 块中调用

---

## 独立分支：`IRClosure`

直接从 `Closure` 继承，不经过任何中间抽象类。这是 IR 编译路径的闭包实现，包装 `IRFunction` 和捕获的自由变量。与 `Procedure`（AST 路径）在概念上对等，但基于字节码指令而非 AST 求值。

---

## 各类详细说明

### 1. `elite.lang.Closure` (abstract)

**包**: `elite.lang`  
**继承**: `javax.el.ValueExpression`

**用途**: 所有可计算对象的抽象根类。定义了闭包系统的完整协议。

**关键 API**:

| 方法 | 说明 |
|------|------|
| `getContext()` / `getContext(ELContext)` | 获取关联的 `EvaluationContext` |
| `invoke(ELContext, Closure[])` | **抽象** — 核心调用方法 |
| `call(ELContext, Object...)` | 便捷方法：将 varargs 转为 Closure[] 后调用 invoke |
| `test(ELContext, Object...)` | 调用后结果转为 boolean |
| `arity(ELContext)` | **抽象** — 参数个数 |
| `getMethodInfo(ELContext)` | **抽象** — 方法签名信息 |
| `getModifiers()` / `setModifiers(int)` | Java 反射修饰符 |
| `isProcedure()` | 是否可作为过程调用（默认 false） |
| `curry(Object...)` | 偏函数应用，返回 `AbstractClosure` |
| `flip()` | 参数反转，返回 `AbstractClosure` |
| `compose(Closure)` | 函数复合 f∘g，返回 `Compose` (extends AbstractClosure) |
| `pow(int)` | 函数幂 f^n，返回 `Power` (extends AbstractClosure) |
| 注解方法 | `isAnnotationPresent`, `getAnnotation`, `getAnnotations`, `addAnnotation`, `removeAnnotation` |
| `setMetaData(MetaData)` | 设置元数据 |
| `setValueChangeListener(...)` | 设置值变更监听器 |

**内部类**:
- `Compose extends AbstractClosure` — f(g(x))
- `Power extends AbstractClosure` — f 重复应用 n 次

---

### 2. `AbstractClosure` (abstract)

**包**: `org.elite.eval.closure`  
**继承**: `Closure`

**用途**: 提供 `ValueExpression` 的默认骨架实现。`getValue()` 返回 `this`（闭包自身即其值），`setValue()` 抛异常。

**默认行为**:
- `getValue()` → `this`
- `setValue()` → 抛出 `PropertyNotWritableException`
- `isReadOnly()` → `true`
- `getType()` → `Closure.class`
- `equals/hashCode` → 基于对象同一性 (identity)
- `arity()` → -1（未知）

---

### 3. `AnnotatedClosure` (abstract)

**包**: `org.elite.eval.closure`  
**继承**: `Closure`（直接继承，非 AbstractClosure）

**用途**: 在 Closure 基础上增加**元数据**支持。持有 `MetaData`（包含注解和修饰符），提供了完整的注解操作方法实现和修饰符存取。

**关键字段**:
- `MetaData metadata` — 注解 + 修饰符容器
- `int modifiers` — Java 反射修饰符

---

### 4. `DelegatingClosure` (abstract)

**包**: `org.elite.eval.closure`  
**继承**: `Closure`（直接继承）

**用途**: **Decorator 模式**的抽象基类。持有一个 `delegate: Closure`，将所有方法调用透明转发给被装饰的闭包。子类在转发前后添加行为。

**关键字段**:
- `Closure delegate` — 被装饰的闭包

**代理的方法**: `getContext`, `_setenv`, `setValueChangeListener`, 所有 modifier 方法, `arity`, `getMethodInfo`, 所有注解方法, `invoke`, `getValue`, `setValue`, `isReadOnly`, `getType`, `getExpectedType`, `getExpressionString`, `isLiteralText`, `equals`, `hashCode`, `toString`, `getValueReference`

---

## 对象系统相关

### 5. `ThisObject` (abstract)

**包**: `org.elite.eval.closure`  
**继承**: `AbstractClosure`  
**实现**: `ClosureObject`, `Serializable`

**用途**: ELite 对象实例的内部表示（"this"）。提供成员查找、属性访问、方法调用等核心对象协议，支持 public/protected/private 访问控制。

**关键方法**:
- `get_class()` — 返回 `ClassDefinition`
- `get_this()` — 返回自身
- `get_owner()` — 返回公共外观对象 (`ClosureObject`)
- `get_proxy()` — 返回 Java 代理对象
- `get_closure(ELContext, String)` — 查找成员闭包
- `get_my_closure(ELContext, String)` — 仅当前类查找（不含继承）
- `invoke(ELContext, String, Closure[])` — 内部方法调用（可访问 private）
- `invokeSpecial(...)` — 特殊方法 (operator overload)
- `invokePublic(...)` / `invokeProtected(...)` — 带访问控制的方法调用
- `getPublicClosure(...)` — 带访问控制的成员查找

---

### 6. `BasicThisObject`

**包**: `org.elite.eval.closure` (package-private)  
**继承**: `ThisObject`

**用途**: `ThisObject` 的基础实现，用于简单的（无继承、无 Java 代理）闭包对象。管理实例变量映射 (`vmap`)、Java 接口代理、成员闭包环境设置。

**关键字段**:
- `ClassDefinition cdef` — 所属类定义
- `Map<String,Closure> vmap` — 实例变量闭包映射
- `List<Class> interfaces` — 实现的 Java 接口
- `ClosureObject owner` — 公共外观对象
- `Object proxy` — Java 动态代理

**内部类**:
- `Environment extends VariableMapper` — 成员闭包的变量解析环境
- `ExpandoClosure extends DelegatingClosure` — 在调用 expando 闭包时自动追加 `this`
- `SynchronizedClosure extends DelegatingClosure` — 在 `synchronized(proxy)` 中调用
- `ClosureProxyHandler implements InvocationHandler` — Java 代理方法拦截，分发到 ELite 成员过程

---

### 7. `DerivedThisObject`

**包**: `org.elite.eval.closure` (package-private)  
**继承**: `BasicThisObject`

**用途**: 实现两个 ELite class 之间的**继承**。持有一个基类 `ThisObject` 的引用 (`zuper`)，方法查找沿继承链向上搜索。支持方法 override（用 `override()` 将子类闭包写入基类 vmap，原始闭包保存在 `zuper.smap` 中）。

**关键字段**:
- `SuperObject zuper` — 基类对象引用

**内部类**:
- `SuperObject extends DefaultClosureObject` — 提供 `super` 访问，track override 的闭包

---

### 8. `ProxiedThisObject`

**包**: `org.elite.eval.closure` (package-private)  
**继承**: `BasicThisObject`

**用途**: 实现从 Java 类的**继承**。使用 CGLIB `Enhancer` 创建代理对象（扩展 Java 超类 + 实现 `ClosureObject`），拦截 Java 方法调用并分发到 ELite 成员过程。

**关键字段**:
- `Class superclass` — Java 基类
- `SuperObject zuper` — Java 超类方法引用
- `boolean creatingProxy` — 防止构造期间循环调用

**内部类**:
- `SuperObject extends DefaultClosureObject` — Java 超类方法访问（通过 `MethodResolver`，使用 `invokeSuper` 语义）
- `ProxyInterceptor implements MethodInterceptor` — CGLIB 拦截器，将 Java 方法调用分发到 ELite

---

### 9. `DelegatedThisObject`

**包**: `org.elite.eval.closure` (package-private)  
**继承**: `ThisObject`（直接继承，非 BasicThisObject）

**用途**: 实现 `@delegate` 注解语义。包装一个主 `ThisObject` + 多个委托闭包。当成员在主对象中未找到时，依次搜索委托对象（支持 `ClosureObject` 委托和 Java 方法委托）。

**关键字段**:
- `ThisObject thisObj` — 主对象
- `Closure[] delegates` — 委托闭包列表

---

### 10. `DefaultClosureObject`

**包**: `org.elite.eval.closure` (package-private)  
**继承**: `AbstractClosure`  
**实现**: `ClosureObject`, `Serializable`

**用途**: `ThisObject` 的**公共外观**（public-facing wrapper）。提供带访问控制的成员访问。在属性 get/set 时检查 getter/setter 过程，在方法调用时使用 `invokeSpecial` 直接调用、失败后回退到 `invoke` 动态分发。

**关键行为**:
- `getValue(ELContext, Object)` — getter process → data member → `[]` operator
- `setValue(...)` — setter process → data member → `[]=` operator
- `invoke(ELContext, String, Closure[])` — member process → `invoke` dynamic dispatch
- `invoke(ELContext, Closure[])` — `__call__` 调用（使对象可被调用）
- `invokeSpecial(...)` — 直接调用成员过程（无动态分发回退）

---

### 11. `ClassDefinition`

**包**: `org.elite.eval.closure`  
**继承**: `AnnotatedClosure`  
**实现**: `PropertyResolvable`, `MethodResolvable`, `Serializable`

**用途**: ELite `class` 定义的 runtime 表示。管理类的完整生命周期：初始化、继承、mixin、静态成员、实例创建 (`_new()`)、模式匹配、访问控制作用域。

**关键方法**:
- `init(ELContext)` — 线程安全的懒初始化
- `getName()` / `getBaseClass()` / `isAssignableFrom()` / `isInstance()`
- `_new(ELContext, Closure...)` — 创建实例
- `createThisObject(...)` — 构建实例变量映射，选择 `BasicThisObject`/`DerivedThisObject`/`ProxiedThisObject`
- `attach(name, closure)` / `detach(name)` — expando 支持
- `getClosure(...)` / `getPrivateClosure(...)` — 静态成员访问
- `invokeInScope(...)` — 在 private 访问作用域中调用闭包
- `matches(...)` — 模式匹配

**内部类** (均继承 `AbstractClosure`):
- `InitProc` — 初始化器：重排命名/位置参数，分配 data slots
- `ToStringProc` — 默认 toString（逐 slot 比较）
- `EqualsProc` — 默认 equals
- `HashCodeProc` — 默认 hashCode
- `CompareToProc` — 默认 Comparable.compareTo

---

### 12. `DataClass`

**包**: `org.elite.eval.closure`  
**继承**: `AbstractClosure`

**用途**: 表示 `@data` 注解定义的**数据类**。包装一个 Java `Class` 和命名的数据槽位（slots）。

**关键字段**:
- `Class jclass` — 关联的 Java 类
- `String[] slots` — 数据槽位名称

---

### 13. `FieldClosure`

**包**: `org.elite.eval.closure`  
**继承**: `AbstractClosure`

**用途**: 包装 Java `java.lang.reflect.Field` 为闭包。提供对 Java 静态字段的读写访问。

---

## 方法调用相关

### 14. `MethodClosure` (abstract)

**包**: `org.elite.eval.closure`  
**继承**: `AbstractClosure`

**用途**: Java 方法包装的抽象基类。支持静态调用 (`invoke(elctx, (Closure[]) args)`) 和实例调用 (`invoke(elctx, Object base, Closure[] args)`)。`isProcedure()` 返回 `true`。

**关键方法**:
- `abstract String getName()` — 方法名
- `abstract Object invoke(ELContext, Object base, Closure[] args)` — 实例方法调用
- `Object invokeSuper(ELContext, Object base, Closure[] args)` — super 调用（默认抛异常）
- `Object invoke(ELContext, Closure[] args)` — 静态调用（委托给 `invoke(elctx, null, args)`）

---

### 15. `JavaMethodClosure` (abstract)

**包**: `org.elite.resolver`  
**继承**: `MethodClosure`

**用途**: 基于 Java 反射的 `MethodClosure`。子类处理单方法（`SingleMethodClosure`）和多方法/重载（`MultiMethodClosure`）两种情况。

---

### 16. `SingleMethodClosure`

**包**: `org.elite.resolver`  
**继承**: `JavaMethodClosure`

**用途**: 包装单个 Java `java.lang.reflect.Method`。调用时直接反射调用。

---

### 17. `MultiMethodClosure`

**包**: `org.elite.resolver`  
**继承**: `JavaMethodClosure`

**用途**: 包装多个 Java 重载方法（`Method[]`）。调用时通过 `MethodResolver` 进行**重载决议**，选择最匹配的方法。

---

### 18. `ExpandoMethodClosure`

**包**: `org.elite.resolver`  
**继承**: `MethodClosure`

**用途**: 动态附加到对象的方法闭包（expando method）。非反射，调用时直接执行闭包逻辑。

---

### 19. `TargetMethodClosure`

**包**: `org.elite.eval.closure`  
**继承**: `DelegatingClosure`

**用途**: 将 `MethodClosure` 绑定到特定的**目标对象**。`invoke()` 时自动传入 `target` 作为 base 对象。

---

## 惰性求值体系

### 20. `DelayClosure` (abstract)

**包**: `org.elite.eval.closure`  
**继承**: `AnnotatedClosure`

**用途**: **惰性求值**的抽象基类。实现 call-by-need 语义：值在首次 `getValue()` 时计算（`force()`），之后缓存。支持 `forget()` 清除缓存。

**关键字段**:
- `Object value = NO_VALUE` — 缓存的计算结果
- `ValueChangeListener listener` — 值变更监听器

**关键方法**:
- `abstract Object force(ELContext)` — 计算值
- `abstract void forget()` — 清除缓存
- `getValue(ELContext)` — 首次调用触发 `force()` 并缓存
- `setValue(ELContext, Object)` — 遗忘旧值，通知监听器
- `invoke(ELContext, Closure[])` — 先 force 值，再对其调用 invoke

---

### 21. `DelayEvalClosure`

**包**: `org.elite.eval.closure`  
**继承**: `DelayClosure`

**用途**: 通过求值另一个 `Closure`（`eval` 字段）来生成延迟值的 `DelayClosure`。首次求值后释放 `eval` 引用以节省内存。这是 `&param` 惰性参数和 `delay()` 的运行时基石。

**关键字段**:
- `Closure eval` — 用于求值的闭包（求值后置 null）

**`force()` 行为**:
1. 调用 `eval.getValue(elctx)` 取得值
2. 设置 `eval = null`（释放引用）
3. 返回结果

---

### 22. `VarClosure`

**包**: `org.elite.eval.closure`  
**继承**: `DelayEvalClosure`

**用途**: 标识符引用的延迟求值闭包。包装一个 `ELNode.IDENT`（标识符 AST 节点），存储变量名。在 AST 求值路径上用于变量引用的惰性解析。

---

### 23. `IRInterpreter.Thunk` (private)

**包**: `org.elite.ir`  
**继承**: `DelayClosure`

**用途**: IR 路径的惰性 thunk。包装 `IRClosure`，在首次 force 时通过 `IRInterpreter` 执行 IR 函数体。是从 `DELAY` opcode 创建的运行时对象。

---

### 24. `Builtin.DelayHead` (private)

**包**: `elite.lang`  
**继承**: `DelayClosure`

**用途**: 惰性 cons 链的头部。惰性序列的 tail 部分通过此闭包延迟求值。

---

### 25. `Builtin.FoldRightRec` (private)

**包**: `elite.lang`  
**继承**: `DelayClosure`

**用途**: fold-right 递归的惰性包装。避免无限序列上 fold-right 的栈溢出。

---

### 26. `Grammar.ExpressionClosure` (private)

**包**: `org.elite.parser`  
**继承**: `DelayClosure`

**用途**: 语法解析期间创建的惰性表达式闭包。延迟表达式的求值直到实际需要时。

---

## AST 求值相关

### 27. `EvalClosure`

**包**: `org.elite.eval.closure`  
**继承**: `AnnotatedClosure`

**用途**: 包装一个 `ELNode`（AST 节点）的闭包。维护自己的 `EvaluationContext`、`FunctionMapper` 和 `VariableMapper`。通过对 AST 节点的求值来实现所有 ValueExpression/Closure 操作。

**关键字段**:
- `ELNode node` — 被包装的 AST 节点
- `EvaluationContext context` — 独立的求值上下文
- `FunctionMapper fm` / `VariableMapper vm`

---

### 28. `Procedure`

**包**: `org.elite.eval.closure`  
**继承**: `EvalClosure`

**用途**: 代表一个**具名过程**（ELite lambda）。`getValue()` 返回 `this`（过程自身即其值），`isProcedure()` 返回 `true`。支持 `call_with(ELContext, Object scope, Closure... args)` 方法——在指定 scope 对象的作用域内调用过程，使 scope 的成员变量对过程可见。

---

## 字面量与类型

### 29. `LiteralClosure`

**包**: `org.elite.eval.closure`  
**继承**: `AnnotatedClosure`

**用途**: 将任意 Java `Object` 包装为 Closure。提供了对象到 Closure 系统的桥接——当需要把普通 Java 值（字符串、数字、Boolean 等）作为 Closure 传递时使用。

**关键字段**:
- `Object value` — 被包装的值
- `ValueChangeListener listener`

**特殊行为**:
- 如果 `value` 本身是 `Closure`，`arity()`/`invoke()` 会委托给它
- `invoke()` 使用 `ELEngine.invokeTarget()` 尝试调用该值
- `equals`/`hashCode` 基于值而非同一性

---

### 30. `TypedClosure`

**包**: `org.elite.eval.closure`  
**继承**: `DelegatingClosure`

**用途**: 带**类型检查/强制转换**的装饰器。在 get/set 时进行类型强制转换。通过静态工厂方法 `make()` 创建，根据目标类型返回 `JavaTypedClosure`（Java Class 类型）或 `ClosureTypedClosure`（ELite ClassDefinition 类型）。

---

### 31. `NamedClosure`

**包**: `org.elite.eval.closure`  
**继承**: `DelegatingClosure`

**用途**: 附加**字符串名称**到闭包上。用于过程调用中的 keyword arguments（命名参数）。

---

## 其他

### 32. `IRClosure`

**包**: `org.elite.ir`  
**继承**: `Closure`（直接继承）

**用途**: IR 编译路径的闭包实现。包装 `IRFunction` + 捕获的自由变量数组（`Object[] captured`）。与 `Procedure`（AST 路径）对等，但通过 `IRInterpreter` 执行字节码指令。支持 `call_with()`（scope 内调用）。

**关键字段**:
- `IRFunction function` — 编译后的 IR 函数
- `Object[] captured` — 捕获的自由变量值
- `EvaluationContext evalContext` — 创建时的作用域链（用于 PUSH_GLOBAL/STORE_GLOBAL 的作用域解析）

---

### 33. `EvaluationContext`

**包**: `org.elite.eval`  
**继承**: `AbstractClosure`  
**实现**: `PropertyDelegate`

**用途**: 表达式求值的上下文环境。继承 `AbstractClosure` 使其可作为 Closure 值用于变量解析（如顶层表达式中的 `this`）。管理 ELContext、FunctionMapper、VariableMapper、调用栈、类和命名空间解析。

---

### 34. `Namespace`

**包**: `elite.xml`  
**继承**: `AnnotatedClosure`  
**实现**: `Coercible`

**用途**: XML 命名空间表示。包含 prefix 和 URI，可作为闭包值使用。

---

## 关键接口

### `ClosureObject`

**包**: `org.elite.eval.closure`  
**继承**: `PropertyDelegate`

**用途**: ELite 闭包对象的公共协议接口。

| 方法 | 说明 |
|------|------|
| `get_class()` | 返回 `ClassDefinition` |
| `get_this()` | 返回内部 `ThisObject` |
| `get_owner()` | 返回公共外观对象 |
| `get_proxy()` | 返回 Java 代理对象 |
| `get_closure(ELContext, String)` | 查找成员闭包 |
| `get_closures(ELContext)` | 获取所有公共成员闭包 |
| `invoke(ELContext, String, Closure[])` | 调用成员过程 |
| `invokeSpecial(ELContext, String, Closure[])` | 调用特殊过程 (operator overload) |

### `MetaData`

**用途**: 注解和修饰符的数据容器。被 `AnnotatedClosure` 引用。

### `ValueChangeListener`

**用途**: 值变更回调接口。被 `LiteralClosure` 和 `DelayClosure` 用于通知值变化。

---

## 设计模式总结

| 模式 | 体现 |
|------|------|
| **Template Method** | `Closure` 定义协议，`AbstractClosure`/`AnnotatedClosure` 提供骨架 |
| **Decorator** | `DelegatingClosure` → `TypedClosure`, `NamedClosure`, `TargetMethodClosure` |
| **Proxy** | `DefaultClosureObject` 为 `ThisObject` 提供访问控制代理 |
| **Adapter** | `FieldClosure`, `LiteralClosure` 将 Java 概念适配到 Closure 体系 |
| **Lazy Initialization** | `DelayClosure`/`DelayEvalClosure` call-by-need 语义 |
| **Chain of Responsibility** | `DelegatedThisObject` 委托链；`DerivedThisObject` 继承链 |
| **Strategy** | `MethodClosure` → `SingleMethodClosure` / `MultiMethodClosure` 不同重载决议策略 |
| **Composite** | `BasicThisObject` + `ExpandoClosure` 将动态成员组合到对象中 |

---

## 对象实例化流程

```
ClassDefinition._new(args)
  │
  ├── createThisObject(env, args)
  │     ├── 分析 args 中的 vmap（实例变量闭包）
  │     ├── 判断继承类型：
  │     │    ├── 纯 ELite class → BasicThisObject
  │     │    ├── 继承 ELite class → DerivedThisObject
  │     │    └── 继承 Java class → ProxiedThisObject
  │     └── 返回 ThisObject
  │
  ├── setOwner(new DefaultClosureObject(thisObj))
  ├── init(elctx, args)   ← 调用 init 过程
  └── createProxy(elctx)   ← 创建 Java 代理（如有接口）
```

## 惰性求值流程

```
编译期                         运行期
───────                        ───────
buildThunk(expr)               DELAY opcode
  │                              │
  ├── 编译表达式为 IRFunction     ├── 创建 IRClosure(fn, captured)
  ├── 扫描自由变量                ├── 包装为 DelayEvalClosure(ThunkEvalAdapter(irClosure))
  ├── emit DELAY(idx, nCaptured)  └── push 到操作数栈
  │
  │                              PUSH_VAR 读取变量
  │                                │
  │                                ├── val instanceof DelayEvalClosure?
  │                                │    ├── yes → val.getValue(elctx)  // auto-force
  │                                │    └── no  → val
  │                                └── PUSH_VAR_RAW  // 不 force，传引用
```
