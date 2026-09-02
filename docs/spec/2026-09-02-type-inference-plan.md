# 类型推导实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 ELite 实现类型推导:类型模型(org.elite.type)+ 推断遍(org.elite.ir.TypeInference),结果存 `ELNode.inferredType`,消费方仅 `--dump-ast`。

**Architecture:** 新包 `org.elite.type` 放类型模型(不可变类,结构相等);`TypeInference extends DefaultVisitor` 在 `SymbolTableBuilder.build` 之后多轮扫描收敛;`ASTDumper` 新增 `dumpTyped` 输出 ` :: <Type>` 后缀。不接入 IRBuilder。

**Tech Stack:** Java 17、JUnit 5.10.3、Maven;复用 `SymbolTable.Symbol`(org.elite.ir)、`TypeCoercion.getBoxedType`、`ClassResolver`(org.elite.resolver)、`DefaultVisitor`(org.elite.parser)

## Global Constraints

- 规格:`docs/spec/2026-09-02-type-inference-design.md`(已批准,行为以它为准)
- **不接入 IRBuilder**;唯一消费方 `CommandProvider.dump_ast` + `ASTDumper.dumpTyped`
- 推导尽力而为:解析不出 → `AnyType.ANY`,绝不抛错
- 标注类型权威且不提升(标注覆盖推导结果);NULL → Any(用户决定,null 处理后续再议)
- 不修改现有 `ASTDumper.dump()` 输出;不动 `elite.ast.*`
- JUnit 测试与现有 817 测试同框架(本功能需要测试,不同于 CLI 场景)
- 测试解析用 `ELEngine.createELContext()` + `new Parser(elctx, script).parse()`(dump_ast 模式)
- 关键字段速查:`ELNode.symbol`(ELNode.java:95,transient SymbolTable.Symbol)、`Symbol.immutable`(默认 true)、`Symbol.def`(→DEFINE)、`Symbol.isFunction()`(func!=null)、`Symbol.clazz`(ELite 类)、`NUMBER.value`(Number,int/float 靠 `value.getClass()` 区分)、二元节点 `left`/`right`(Binary.java:1655/Unary.java:1588)、`LAMBDA.rtype`/`vars(DEFINE[])`/`body`、`DEFINE.type`/`expr`(函数形 DEFINE 的 expr 是 LAMBDA)
- 每个任务结尾:`mvn test -Dtest=<新测试类>` + `mvn -q compile` 双门禁;commit 用 Conventional Commits + Co-Authored-By 尾注
- 收敛循环上限 20 轮;`Any` 吸收;类型单调(只从"未推导"变具体/Any,具体类型不改写为其他具体类型——标注覆盖除外)

---

### Task 1: 类型模型 org.elite.type

**Files:**
- Create: `src/main/java/org/elite/type/Type.java`、`PrimitiveType.java`、`JavaClassType.java`、`ELiteClassType.java`、`ListType.java`、`TupleType.java`、`MapType.java`、`ArrayType.java`、`FunctionType.java`、`AnyType.java`、`ErrorType.java`
- Test: `src/test/java/org/elite/type/TypeTest.java`

**Interfaces:**
- Consumes: `org.elite.eval.TypeCoercion.getBoxedType(Class)`(public static)、`org.elite.ir.SymbolTable.Symbol`
- Produces(后续任务依赖,签名精确如下):
  - `Type` 抽象基类:`public abstract class Type { public abstract String toString(); public abstract boolean equals(Object o); public abstract int hashCode(); }`(构造器包内可见)
  - `PrimitiveType`:`public static final PrimitiveType ANY_INT; ANY_FLOAT;`、`public static PrimitiveType of(Class<?> c)`(原始类型先装箱;显示名走别名表 `Integer→Int, Character→Char, BigInteger→BigInt, Void→void`,其余 simple name)、`public Class<?> javaClass()`(AnyInt/AnyFloat 为 null)、`public boolean isAnyInt(); isAnyFloat();`
  - `JavaClassType`:c'tor `(Class<?> javaClass)`、`javaClass()`、toString=simple name
  - `ELiteClassType`:c'tor `(SymbolTable.Symbol sym)`、`symbol()`、toString=`sym.name`
  - `ListType`:c'tor `(Type element)`、`element()`
  - `TupleType`:c'tor `(Type[] elems)`、`elems()`
  - `MapType`:c'tor `(Type key, Type value)`、`key()/value()`
  - `ArrayType`:c'tor `(Type element, int dims)`、`element()/dims()`
  - `FunctionType`:c'tor `(Type returnType, Type[] params)`、`returnType()/params()`
  - `AnyType`:`public static final AnyType ANY;`(单例,构造器私有)
  - `ErrorType`:`public static final ErrorType ERROR;`(单例,构造器私有,toString `Error`)
  - toString 格式:`Int`/`AnyInt`/`AnyFloat`/`Long`/`void`、`List[Int]`、`Tuple[String, Int]`、`Map[String, Int]`、`Array[Int, 2]`、`Function[Int, [Int, Int]]`、`Any`
  - equals/hashCode 为结构相等(JavaClassType/PrimitiveType 比 `javaClass()`;ELiteClassType 比 `symbol()` 恒等;AnyType/ANY_INT/ANY_FLOAT 各自单例恒等)

- [ ] **Step 1: 写失败测试 TypeTest**

创建 `src/test/java/org/elite/type/TypeTest.java`:

```java
package org.elite.type;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeTest {

  @Test
  void primitiveNames() {
    assertEquals("Int", PrimitiveType.of(Integer.class).toString());
    assertEquals("Int", PrimitiveType.of(int.class).toString());
    assertEquals("Char", PrimitiveType.of(Character.class).toString());
    assertEquals("BigInt", PrimitiveType.of(BigInteger.class).toString());
    assertEquals("Long", PrimitiveType.of(Long.class).toString());
    assertEquals("Double", PrimitiveType.of(Double.class).toString());
    assertEquals("void", PrimitiveType.of(void.class).toString());
  }

  @Test
  void primitiveEquality() {
    assertEquals(PrimitiveType.of(Integer.class), PrimitiveType.of(int.class));
    assertNotEquals(PrimitiveType.of(Integer.class), PrimitiveType.of(Long.class));
    assertNotEquals(PrimitiveType.of(Integer.class), PrimitiveType.ANY_INT);
    assertEquals(PrimitiveType.ANY_INT, PrimitiveType.ANY_INT);
  }

  @Test
  void anyIntAndAnyFloat() {
    assertEquals("AnyInt", PrimitiveType.ANY_INT.toString());
    assertEquals("AnyFloat", PrimitiveType.ANY_FLOAT.toString());
    assertTrue(PrimitiveType.ANY_INT.isAnyInt());
    assertFalse(PrimitiveType.ANY_INT.isAnyFloat());
    assertNotEquals(PrimitiveType.ANY_INT, PrimitiveType.ANY_FLOAT);
  }

  @Test
  void compositeTypes() {
    Type t = new ListType(PrimitiveType.of(Integer.class));
    assertEquals("List[Int]", t.toString());
    assertEquals(t, new ListType(PrimitiveType.of(int.class)));

    assertEquals("Tuple[String, Int]",
      new TupleType(new Type[]{ PrimitiveType.of(String.class),
                                PrimitiveType.of(Integer.class) }).toString());

    assertEquals("Map[String, Int]",
      new MapType(PrimitiveType.of(String.class),
                  PrimitiveType.of(Integer.class)).toString());

    assertEquals("Array[Int, 2]",
      new ArrayType(PrimitiveType.of(Integer.class), 2).toString());

    assertEquals("Function[Int, [Int, Int]]",
      new FunctionType(PrimitiveType.of(Integer.class),
        new Type[]{ PrimitiveType.of(Integer.class),
                    PrimitiveType.of(Integer.class) }).toString());
  }

  @Test
  void anyType() {
    assertEquals("Any", AnyType.ANY.toString());
    assertEquals(AnyType.ANY, AnyType.ANY);
  }

  @Test
  void errorType() {
    assertEquals("Error", ErrorType.ERROR.toString());
    assertEquals(ErrorType.ERROR, ErrorType.ERROR);
    assertNotEquals(ErrorType.ERROR, AnyType.ANY);
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=TypeTest`
Expected: 编译错误(类不存在)

- [ ] **Step 3: 实现类型模型**

创建 `src/main/java/org/elite/type/Type.java`:

```java
package org.elite.type;

/**
 * Base class of the ELite static type model.
 * All subclasses are immutable with structural equality.
 */
public abstract class Type {
  Type() {}

  @Override
  public abstract String toString();

  @Override
  public abstract boolean equals(Object o);

  @Override
  public abstract int hashCode();
}
```

创建 `src/main/java/org/elite/type/PrimitiveType.java`:

```java
package org.elite.type;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import org.elite.eval.TypeCoercion;

/**
 * A Java primitive/boxed type or an ELite exact numeric type, or one of
 * the two promotion-chain markers ANY_INT / ANY_FLOAT.
 */
public final class PrimitiveType extends Type {

  /** Int -> Long -> BigInt promotion chain marker. */
  public static final PrimitiveType ANY_INT = new PrimitiveType("AnyInt", null);

  /** Float -> Double -> BigDecimal promotion chain marker. */
  public static final PrimitiveType ANY_FLOAT = new PrimitiveType("AnyFloat", null);

  private static final Map<Class<?>, String> ALIASES = Map.of(
    Integer.class, "Int",
    Character.class, "Char",
    BigInteger.class, "BigInt",
    Void.class, "void");

  private final String name;
  private final Class<?> javaClass;   // null for ANY_INT / ANY_FLOAT

  private PrimitiveType(String name, Class<?> javaClass) {
    this.name = name;
    this.javaClass = javaClass;
  }

  public static PrimitiveType of(Class<?> c) {
    Class<?> boxed = TypeCoercion.getBoxedType(c);
    String display = ALIASES.getOrDefault(boxed, boxed.getSimpleName());
    return new PrimitiveType(display, boxed);
  }

  public Class<?> javaClass() { return javaClass; }

  public boolean isAnyInt() { return this == ANY_INT; }

  public boolean isAnyFloat() { return this == ANY_FLOAT; }

  @Override
  public String toString() { return name; }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof PrimitiveType that)) return false;
    if (javaClass == null || that.javaClass == null) {
      return false;   // ANY_INT / ANY_FLOAT are distinct singleton markers
    }
    return javaClass.equals(that.javaClass);
  }

  @Override
  public int hashCode() { return Objects.hashCode(javaClass); }
}
```

创建 `src/main/java/org/elite/type/JavaClassType.java`:

```java
package org.elite.type;

import java.util.Objects;

/** A reference to a Java class. */
public final class JavaClassType extends Type {
  private final Class<?> javaClass;

  public JavaClassType(Class<?> javaClass) {
    this.javaClass = javaClass;
  }

  public Class<?> javaClass() { return javaClass; }

  @Override
  public String toString() { return javaClass.getSimpleName(); }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaClassType that && javaClass == that.javaClass;
  }

  @Override
  public int hashCode() { return Objects.hashCode(javaClass); }
}
```

创建 `src/main/java/org/elite/type/ELiteClassType.java`:

```java
package org.elite.type;

import org.elite.ir.SymbolTable;

/** A reference to an ELite class definition. */
public final class ELiteClassType extends Type {
  private final SymbolTable.Symbol symbol;

  public ELiteClassType(SymbolTable.Symbol symbol) {
    this.symbol = symbol;
  }

  public SymbolTable.Symbol symbol() { return symbol; }

  @Override
  public String toString() { return symbol.name; }

  @Override
  public boolean equals(Object o) {
    return o instanceof ELiteClassType that && symbol == that.symbol;
  }

  @Override
  public int hashCode() { return System.identityHashCode(symbol); }
}
```

创建 `src/main/java/org/elite/type/ListType.java`:

```java
package org.elite.type;

/** An ELite list type, e.g. List[Int]. */
public final class ListType extends Type {
  private final Type element;

  public ListType(Type element) {
    this.element = element;
  }

  public Type element() { return element; }

  @Override
  public String toString() { return "List[" + element + "]"; }

  @Override
  public boolean equals(Object o) {
    return o instanceof ListType that && element.equals(that.element);
  }

  @Override
  public int hashCode() { return element.hashCode() * 31 + 1; }
}
```

创建 `src/main/java/org/elite/type/TupleType.java`:

```java
package org.elite.type;

import java.util.Arrays;

/** An ELite tuple type, e.g. Tuple[String, Int]. */
public final class TupleType extends Type {
  private final Type[] elems;

  public TupleType(Type[] elems) {
    this.elems = elems;
  }

  public Type[] elems() { return elems; }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Tuple[");
    for (int i = 0; i < elems.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(elems[i]);
    }
    return sb.append(']').toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TupleType that && Arrays.equals(elems, that.elems);
  }

  @Override
  public int hashCode() { return Arrays.hashCode(elems); }
}
```

创建 `src/main/java/org/elite/type/MapType.java`:

```java
package org.elite.type;

import java.util.Objects;

/** An ELite map type, e.g. Map[String, Int]. */
public final class MapType extends Type {
  private final Type key;
  private final Type value;

  public MapType(Type key, Type value) {
    this.key = key;
    this.value = value;
  }

  public Type key() { return key; }

  public Type value() { return value; }

  @Override
  public String toString() { return "Map[" + key + ", " + value + "]"; }

  @Override
  public boolean equals(Object o) {
    return o instanceof MapType that &&
           key.equals(that.key) && value.equals(that.value);
  }

  @Override
  public int hashCode() { return Objects.hash(key, value); }
}
```

创建 `src/main/java/org/elite/type/ArrayType.java`:

```java
package org.elite.type;

/** An array type, e.g. Array[Int, 2]. */
public final class ArrayType extends Type {
  private final Type element;
  private final int dims;

  public ArrayType(Type element, int dims) {
    this.element = element;
    this.dims = dims;
  }

  public Type element() { return element; }

  public int dims() { return dims; }

  @Override
  public String toString() { return "Array[" + element + ", " + dims + "]"; }

  @Override
  public boolean equals(Object o) {
    return o instanceof ArrayType that &&
           dims == that.dims && element.equals(that.element);
  }

  @Override
  public int hashCode() { return element.hashCode() * 31 + dims; }
}
```

创建 `src/main/java/org/elite/type/FunctionType.java`:

```java
package org.elite.type;

import java.util.Arrays;

/** An ELite function type, e.g. Function[Int, [Int, Int]]. */
public final class FunctionType extends Type {
  private final Type returnType;
  private final Type[] params;

  public FunctionType(Type returnType, Type[] params) {
    this.returnType = returnType;
    this.params = params;
  }

  public Type returnType() { return returnType; }

  public Type[] params() { return params; }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Function[")
      .append(returnType).append(", [");
    for (int i = 0; i < params.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(params[i]);
    }
    return sb.append("]]").toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof FunctionType that &&
           returnType.equals(that.returnType) &&
           Arrays.equals(params, that.params);
  }

  @Override
  public int hashCode() { return returnType.hashCode() * 31 + Arrays.hashCode(params); }
}
```

创建 `src/main/java/org/elite/type/AnyType.java`:

```java
package org.elite.type;

/** The unknown type; the absorbing element of all merges. */
public final class AnyType extends Type {
  public static final AnyType ANY = new AnyType();

  private AnyType() {}

  @Override
  public String toString() { return "Any"; }

  @Override
  public boolean equals(Object o) { return o instanceof AnyType; }

  @Override
  public int hashCode() { return 0; }
}
```

创建 `src/main/java/org/elite/type/ErrorType.java`:

```java
package org.elite.type;

/**
 * The error type: annotated when inference detected a type error.
 * Absorbs stronger than Any — computations based on an ErrorType
 * expression are also ErrorType.
 */
public final class ErrorType extends Type {
  public static final ErrorType ERROR = new ErrorType();

  private ErrorType() {}

  @Override
  public String toString() { return "Error"; }

  @Override
  public boolean equals(Object o) { return o instanceof ErrorType; }

  @Override
  public int hashCode() { return 1; }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=TypeTest`
Expected: BUILD SUCCESS(6 个测试通过)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/elite/type/ src/test/java/org/elite/type/
git commit -m "feat(type): add ELite static type model

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: ELNode.inferredType 字段 + 推断遍骨架与注解解析

**Files:**
- Modify: `src/main/java/org/elite/parser/ELNode.java`(symbol/scope 字段之后,约 101 行)
- Create: `src/main/java/org/elite/ir/TypeInference.java`(骨架 + 注解解析)
- Test: `src/test/java/org/elite/ir/TypeInferenceTest.java`(测试基架 + 注解解析用例)

**Interfaces:**
- Consumes: Task 1 类型模型;`org.elite.parser.DefaultVisitor`(scan/visit 全递归)、`ELNode.DEFINE.type`、`SymbolTableBuilder.build(ELProgram)`、`ELEngine.createELContext()`、`org.elite.resolver.ClassResolver.getInstance(ELContext).resolveClass(String) throws ClassNotFoundException`、`org.elite.eval.ELProgram.getDefinitions()/getExpressions()`、`ELNode.CLASS.getClassName()`
- Produces:
  - `ELNode` 新字段:`public transient Type inferredType;`
  - `public final class TypeInference extends DefaultVisitor`,静态入口:
    `public static void infer(ELContext elctx, ELProgram program) throws ParseException` —— 多轮收敛循环(上限 20 轮,轮间类型无变化即停);循环结束后 `failIfErrors()`:有累积错误则抛 `ParseException(program.getFilename(), errors)`
  - 报错机制(仿 IRBuilder.java:298-312):`private final List<ParseError> errors`、`private void reportError(int pos, String message)`(`errors.add(new ParseError(pos, message, null))`;`errors.size() >= 20` 时立即抛 `ParseException(program.getFilename(), errors)` 熔断)、`private void failIfErrors()`;`ParseError`/`ParseException` 来自 `org.elite.parser`
  - 内部状态:
    - `private final Map<String, SymbolTable.Symbol> eliteClasses`(第 0 遍登记:遍历 program 定义,`node.symbol != null && node.symbol.clazz != null` 时 `put(sym.name, sym)`)
    - `private Class<?> loadJavaClass(ELNode base)` —— **复制自 IRBuilder.loadJavaClass(IRBuilder.java:1283,私有,暂复制,以后重构共享)**,差异:加载失败 `reportError(pos, _T(EL_CLASS_NOT_FOUND, name))` 后返回 null(而非返回 Object.class)。连同其依赖 `resolveJavaClass(ELNode, boolean)`、`loadClassAtCompileTime(int pos, String name)` 一并复制
    - `private Type resolveAnnotationType(ELNode typeNode)` —— typeNode 是 IDENT/ACCESS 链;若 typeNode 是 IDENT,先查 `eliteClasses` → `ELiteClassType`;查别名表(Int/Char/BigInt)→ `Class.forName(别名)`;否则 `loadJavaClass(typeNode)`(编译期可见性加载,失败已报错并返回 null → `ErrorType.ERROR`);加载出的类命中 PrimitiveType 表(`TypeCoercion.typeof` 非 UNKNOWN)→ `PrimitiveType.of`,否则 `JavaClassType`;其他运行时异常 → `ErrorType.ERROR`
    - `protected boolean changed` + `protected void setType(ELNode node, Type t)`(仅当 `node.inferredType` 不为 t 时写并置 changed)
    - `protected Type typeOf(ELNode node)` → `node.inferredType != null ? node.inferredType : AnyType.ANY`
    - `protected Type merge(Type a, Type b)` —— 任一 ErrorType→ERROR;相等→a;任一 Any→**另一侧类型**(Any 是合并单位元,保证 `[1,2,3]` 从 NIL 的 `List[Any]` 收敛为 `List[Int]`);(本任务版本;Task 6 扩展数值归一化)
    - 本任务的 visit 规则:BOOLEANVAL→Boolean、CHARVAL→Char、STRINGVAL→String、NUMBER(整数按量级 Int/Long/BigInt,浮点 Double,BigDecimal→BigDecimal)、NULL→ANY、NIL→`List[Any]`、SYMBOL→ANY、CONST(value 的运行时类→PrimitiveType.of/JavaClassType,失败 ANY)、Composite→String、COMPOUND→最后子节点类型、LET→right 类型、EXPR→right 类型、METADATA/METASET→子节点类型、IDENT→`AnyType.ANY`(Task 5 补变量/函数)、COND/COALESCE→merge(分支)、其余未覆盖节点→ANY
- [ ] **Step 1: ELNode 加字段**

`ELNode.java` 中,在 `public transient SymbolTable.Scope scope;` 之后插入:

```java
  /**
   * The statically inferred type of this node, set by
   * {@code TypeInference} after symbol table construction.
   * Null until inference runs.
   */
  public transient Type inferredType;
```

并把 import 区加上 `import org.elite.type.Type;`(放在 `import org.elite.ir.SymbolTable;` 之后,import 块中按字母序)。

- [ ] **Step 2: 写失败测试 TypeInferenceTest(基架 + 注解解析)**

创建 `src/test/java/org/elite/ir/TypeInferenceTest.java`:

```java
package org.elite.ir;

import javax.el.ELContext;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.parser.ParseException;
import org.elite.parser.Parser;
import org.elite.type.PrimitiveType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeInferenceTest {

  private static ELContext elctx;

  @BeforeAll
  static void createContext() {
    elctx = ELEngine.createELContext();
  }

  /** Parse only; returns the program (no symbol table, no inference). */
  private static ELProgram parse(String script) {
    try {
      return new Parser(elctx, script).parse();
    } catch (ParseException e) {
      throw new RuntimeException("parse failed: " + script, e);
    }
  }

  /**
   * Parse + build symbol table + infer; returns the last top-level node
   * (the last expression if any, else the last definition).
   */
  private static ELNode infer(String script) {
    ELProgram program = parse(script);
    SymbolTableBuilder.build(program);
    try {
      TypeInference.infer(elctx, program);
    } catch (ParseException e) {
      throw new RuntimeException("inference failed: " + script, e);
    }
    var exps = program.getExpressions();
    if (!exps.isEmpty()) {
      return exps.get(exps.size() - 1);
    }
    var defs = program.getDefinitions();
    assertFalse(defs.isEmpty(), "no top-level node in: " + script);
    return defs.get(defs.size() - 1);
  }

  private static String typeOf(String script) {
    return infer(script).inferredType.toString();
  }

  /** Parse + build + infer; returns the top-level DEFINE with the given id. */
  private static ELNode.DEFINE def(String script, String id) {
    ELProgram program = parse(script);
    SymbolTableBuilder.build(program);
    try {
      TypeInference.infer(elctx, program);
    } catch (ParseException e) {
      throw new RuntimeException("inference failed: " + script, e);
    }
    for (ELNode n : program.getDefinitions()) {
      if (n instanceof ELNode.DEFINE d && id.equals(d.id)) {
        return d;
      }
    }
    for (ELNode n : program.getExpressions()) {
      if (n instanceof ELNode.DEFINE d && id.equals(d.id)) {
        return d;
      }
    }
    throw new AssertionError("define not found: " + id);
  }

  @Test
  void annotatedVariable() {
    assertEquals("Int", typeOf("define x::Int = 42"));
  }

  @Test
  void annotatedAliases() {
    assertEquals("Int", typeOf("define x::Int = 1 + 2"));
    assertEquals("Char", typeOf("define c::Char = 'a'"));
    assertEquals("BigInt", typeOf("define b::BigInt = 1"));
  }

  @Test
  void annotatedJavaClass() {
    assertEquals("Date", typeOf("define d::java.util.Date = null"));
  }

  @Test
  void annotatedEliteClass() {
    ELNode node = infer("class Point(x, y)\ndefine p::Point = new Point(1, 2)");
    assertEquals("Point", node.inferredType.toString());
  }

  @Test
  void unresolvableAnnotationIsError() throws Exception {
    // Class load failure reports an error and types the node ErrorType.
    ELProgram program = parse("define z::NoSuchClass = 1");
    SymbolTableBuilder.build(program);
    ParseException ex = assertThrows(ParseException.class,
      () -> TypeInference.infer(elctx, program));
    assertTrue(ex.getMessage().contains("Class not found: NoSuchClass"),
      "unexpected message: " + ex.getMessage());
    ELNode def = program.getExpressions().get(0);
    assertEquals("Error", def.inferredType.toString());
  }

  @Test
  void literals() {
    assertEquals("Boolean", typeOf("true"));
    assertEquals("Char", typeOf("'x'"));
    assertEquals("String", typeOf("\"hi\""));
    assertEquals("Int", typeOf("42"));
    assertEquals("Long", typeOf("3000000000"));
    assertEquals("Double", typeOf("1.5"));
    assertEquals("Any", typeOf("null"));
  }

  @Test
  void listLiteral() {
    assertEquals("List[Any]", typeOf("[]"));
  }

  @Test
  void conditionalMerge() {
    assertEquals("Int", typeOf("true ? 1 : 2"));
  }
}
```

注意:测试类需要额外 import `org.elite.eval.ELProgram`;`assertThrows` 由 `org.junit.jupiter.api.Assertions.*` 静态导入覆盖。`visitNode` 覆盖需小心——DefaultVisitor 的叶子 visit 不经过 visitNode,find 只用于非叶子节点(后续任务用)。

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: 编译错误(TypeInference 不存在)

- [ ] **Step 4: 实现 TypeInference 骨架 + 注解解析 + 字面量规则**

创建 `src/main/java/org/elite/ir/TypeInference.java`:

```java
package org.elite.ir;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import javax.el.ELContext;
import org.elite.eval.ELProgram;
import org.elite.eval.TypeCoercion;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.resolver.ClassResolver;
import org.elite.type.AnyType;
import org.elite.type.ELiteClassType;
import org.elite.type.ErrorType;
import org.elite.type.JavaClassType;
import org.elite.type.ListType;
import org.elite.type.PrimitiveType;
import org.elite.type.Type;

import static org.elite.resources.Resources.*;

/**
 * Static type inference over the ELite AST.
 *
 * Runs after {@code SymbolTableBuilder} and annotates every
 * {@code ELNode.inferredType} in multiple passes until convergence.
 * Inference is best-effort: anything that cannot be typed becomes
 * {@code AnyType.ANY}. Type errors (unloadable annotation classes,
 * annotation mismatches) are recorded and reported at the end via
 * {@code ParseException}, and the offending nodes are typed
 * {@code ErrorType.ERROR}.
 */
public class TypeInference extends DefaultVisitor {

  /** Pass limit: recursion/cycles settle as Any well before this. */
  private static final int MAX_PASSES = 20;

  /** Alias names for boxed classes used in type annotations. */
  private static final Map<String, String> ALIASES = Map.of(
    "Int", "java.lang.Integer",
    "Char", "java.lang.Character",
    "BigInt", "java.math.BigInteger");

  private final ELContext elctx;
  private final String filename;
  private final Map<String, SymbolTable.Symbol> eliteClasses = new HashMap<>();
  private final java.util.List<org.elite.parser.ParseError> errors =
    new java.util.ArrayList<>();

  private boolean changed;

  private TypeInference(ELContext elctx, ELProgram program) {
    this.elctx = elctx;
    this.filename = program.getFilename();
  }

  public static void infer(ELContext elctx, ELProgram program)
    throws org.elite.parser.ParseException
  {
    TypeInference infer = new TypeInference(elctx, program);
    infer.registerEliteClasses(program);

    int passes = 0;
    do {
      infer.changed = false;
      for (ELNode node : program.getDefinitions()) {
        infer.scan(node);
      }
      for (ELNode node : program.getExpressions()) {
        infer.scan(node);
      }
    } while (infer.changed && ++passes < MAX_PASSES);

    infer.failIfErrors();
  }

  // ---- error reporting (mirrors IRBuilder.reportError / failIfErrors) ----

  private void reportError(int pos, String message) {
    errors.add(new org.elite.parser.ParseError(pos, message, null));
    if (errors.size() >= 20) {
      failIfErrors();
    }
  }

  private void failIfErrors() throws org.elite.parser.ParseException {
    if (!errors.isEmpty()) {
      throw new org.elite.parser.ParseException(filename, errors);
    }
  }

  /** First pass: collect all ELite classes so annotations can resolve. */
  private void registerEliteClasses(ELProgram program) {
    DefaultVisitor collector = new DefaultVisitor() {
      @Override
      public void visit(ELNode.DEFINE e) {
        if (e.symbol != null && e.symbol.clazz != null) {
          eliteClasses.put(e.symbol.name, e.symbol);
        }
        super.visit(e);
      }
    };
    for (ELNode node : program.getDefinitions()) {
      collector.scan(node);
    }
    for (ELNode node : program.getExpressions()) {
      collector.scan(node);
    }
  }

  // ---- helpers ----

  protected final void setType(ELNode node, Type type) {
    if (type != node.inferredType) {
      node.inferredType = type;
      changed = true;
    }
  }

  protected final Type typeOf(ELNode node) {
    return node != null && node.inferredType != null
      ? node.inferredType : AnyType.ANY;
  }

  protected final Type typeOfValue(Object value) {
    if (value == null) return AnyType.ANY;
    Class<?> cls = value.getClass();
    if (TypeCoercion.typeof(cls) != TypeCoercion.UNKNOWN_TYPE) {
      return PrimitiveType.of(cls);
    }
    return new JavaClassType(cls);
  }

  /**
   * Merge two types: errors absorb, Any is the identity (so [1,2,3] builds
   * List[Int] from NIL's List[Any]), equal types merge to themselves,
   * numeric pairs normalize (Task 6), anything else degrades to Any.
   */
  protected Type merge(Type a, Type b) {
    if (a == ErrorType.ERROR || b == ErrorType.ERROR) return ErrorType.ERROR;
    if (a.equals(b)) return a;
    if (a == AnyType.ANY) return b;
    if (b == AnyType.ANY) return a;
    return AnyType.ANY;
  }

  /**
   * Copies of IRBuilder's private class-loading helpers (IRBuilder.java:1283
   * loadJavaClass / resolveJavaClass / loadClassAtCompileTime). Loads are
   * required because Java classes must be compile-time visible; a failed
   * load is an error: report it and return null (the caller types the node
   * ErrorType). TODO: refactor into a shared helper later.
   */
  private Class<?> loadJavaClass(ELNode base) {
    return resolveJavaClass(base, true);
  }

  private Class<?> resolveJavaClass(ELNode node, boolean load) {
    if (node instanceof ELNode.IDENT var && var.symbol != null &&
        var.symbol.def.expr instanceof ELNode.CLASS c) {
      return loadClassAtCompileTime(node.pos, c.getClassName());
    }

    StringBuilder buf = new StringBuilder();
    while (node instanceof ELNode.ACCESS acc) {
      if (!(acc.index instanceof ELNode.STRINGVAL str))
        return null;
      buf.insert(0, str.value);
      buf.insert(0, '.');
      node = acc.right;
    }

    if (node instanceof ELNode.IDENT var) {
      buf.insert(0, var.id);
      if (var.symbol == null) {
        return loadClassAtCompileTime(node.pos, buf.toString());
      }
    }
    return null;
  }

  private Class<?> loadClassAtCompileTime(int pos, String name) {
    try {
      return ClassResolver.getInstance(elctx).resolveClass(name);
    } catch (ClassNotFoundException ex) {
      reportError(pos, _T(EL_CLASS_NOT_FOUND, name));
      return null;
    }
  }

  /** Resolves a ::Type annotation node (IDENT/ACCESS chain). */
  private Type resolveAnnotationType(ELNode typeNode) {
    if (typeNode == null) return AnyType.ANY;

    // 1. ELite class? (simple-name annotations only in v1)
    if (typeNode instanceof ELNode.IDENT id) {
      SymbolTable.Symbol sym = eliteClasses.get(id.id);
      if (sym != null) {
        return new ELiteClassType(sym);
      }

      // 2. Alias?
      String alias = ALIASES.get(id.id);
      if (alias != null) {
        try {
          return PrimitiveType.of(Class.forName(alias));
        } catch (ClassNotFoundException e) {
          return ErrorType.ERROR;
        }
      }
    }

    // 3. Java class — must be compile-time visible; failure is an error.
    try {
      Class<?> cls = loadJavaClass(typeNode);
      if (cls == null) return ErrorType.ERROR;
      if (TypeCoercion.typeof(cls) != TypeCoercion.UNKNOWN_TYPE) {
        return PrimitiveType.of(cls);
      }
      return new JavaClassType(cls);
    } catch (RuntimeException e) {
      return ErrorType.ERROR;
    }
  }

  // ---- node rules (this task) ----

  @Override
  public void visit(ELNode.DEFINE e) {
    super.visit(e);
    Type t = resolveAnnotationType(e.type);
    if (t != AnyType.ANY) {
      // Annotation is authoritative: pin the variable and its initializer.
      setType(e, t);
      if (e.expr != null) {
        setType(e.expr, t);
      }
      return;
    }
    setType(e, typeOf(e.expr));
  }

  @Override
  public void visit(ELNode.BOOLEANVAL e) {
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CHARVAL e) {
    setType(e, PrimitiveType.of(Character.class));
  }

  @Override
  public void visit(ELNode.STRINGVAL e) {
    setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.NUMBER e) {
    setType(e, numberType(e.value));
  }

  private Type numberType(Number value) {
    if (value instanceof BigInteger bi) {
      return PrimitiveType.of(BigInteger.class);
    }
    if (value instanceof java.math.BigDecimal bd) {
      return PrimitiveType.of(java.math.BigDecimal.class);
    }
    if (value instanceof Float || value instanceof Double) {
      return PrimitiveType.of(Double.class);
    }
    long v = value.longValue();
    if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
      return PrimitiveType.of(Integer.class);
    }
    return PrimitiveType.of(Long.class);
  }

  @Override
  public void visit(ELNode.NULL e) {
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NIL e) {
    setType(e, new ListType(AnyType.ANY));
  }

  @Override
  public void visit(ELNode.SYMBOL e) {
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.CONST e) {
    setType(e, typeOfValue(e.value));
  }

  @Override
  public void visit(ELNode.Composite e) {
    super.visit(e);
    setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.COMPOUND e) {
    super.visit(e);
    setType(e, e.exps.length == 0 ? AnyType.ANY : typeOf(e.exps[e.exps.length - 1]));
  }

  @Override
  public void visit(ELNode.LET e) {
    super.visit(e);
    setType(e, typeOf(e.right));
  }

  @Override
  public void visit(ELNode.EXPR e) {
    super.visit(e);
    setType(e, typeOf(e.right));
  }

  @Override
  public void visit(ELNode.METADATA e) {
    super.visit(e);
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.METASET e) {
    super.visit(e);
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.IDENT e) {
    // Variable/function reference typing arrives in a later task.
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.COND e) {
    super.visit(e);
    setType(e, merge(typeOf(e.left), typeOf(e.right)));
  }

  @Override
  public void visit(ELNode.COALESCE e) {
    super.visit(e);
    setType(e, merge(typeOf(e.left), typeOf(e.right)));
  }

  @Override
  public void visitNode(ELNode e) {
    // Anything not covered above stays untyped (reads as Any).
  }
}
```

注意:`visitNode` 覆盖后,未覆盖节点不再报 assert 失败(DefaultVisitor 默认 `assert false`);覆盖成空实现即"未覆盖节点保持无类型"。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: BUILD SUCCESS(8 个测试通过)。若 `define x::Int = 1 + 2` 用例失败,检查 `visit(DEFINE)` 对 expr 的标注覆盖是否生效(该用例中 1+2 尚未有规则,expr 类型由标注覆盖为 Int)。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/elite/parser/ELNode.java src/main/java/org/elite/ir/TypeInference.java src/test/java/org/elite/ir/TypeInferenceTest.java
git commit -m "feat(type): add ELNode.inferredType and inference pass skeleton

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 一元/比较/逻辑与复合表达式规则

**Files:**
- Modify: `src/main/java/org/elite/ir/TypeInference.java`
- Test: `src/test/java/org/elite/ir/TypeInferenceTest.java`(追加用例)

**Interfaces:**
- Consumes: Task 2 骨架;二元节点字段 `left`/`right`(Binary/Unary)
- Produces: 追加规则(后续任务不依赖新 API):NOT→Boolean、EMPTY→Boolean、INSTANCEOF→Boolean、EQ/NE/IDEQ/IDNE/LT/LE/GT/GE/CMP/IN→Boolean、OR/AND→Boolean(操作数非 Boolean 也按 Boolean,ELite 逻辑运算返回值语义)、Composite 已定 String、字符串比较等;本任务**不含**算术/位运算规则(Task 6)

- [ ] **Step 1: 追加失败测试(追加到 TypeInferenceTest 类内,插入到最后一个 @Test 之后、类结束 `}` 之前)**

```java
  @Test
  void unaryAndLogicalOps() {
    assertEquals("Boolean", typeOf("define b = true\n!b"));
    assertEquals("Boolean", typeOf("empty([])"));
    assertEquals("Boolean", typeOf("1 instanceof Int"));
  }

  @Test
  void comparisons() {
    assertEquals("Boolean", typeOf("define a = 1\ndefine b = 2\na < b"));
    assertEquals("Boolean", typeOf("define a = 1\ndefine b = 2\na == b"));
    assertEquals("Boolean", typeOf("define a = 1\ndefine b = 2\na <=> b"));   // CMP
    assertEquals("Boolean", typeOf("define a = 1\na in [1]"));
  }

  @Test
  void logicalOps() {
    assertEquals("Boolean", typeOf("define x = true\ndefine y = false\nx && y"));
    assertEquals("Boolean", typeOf("define x = true\ndefine y = false\nx || y"));
  }

  @Test
  void stringInterpolation() {
    assertEquals("String", typeOf("define n = 42\n\"x=\" ~ n"));
  }

  @Test
  void compoundLastExpression() {
    ELNode.DEFINE f = def("define f() { 1; 2 }", "f");
    ELNode body = ((ELNode.LAMBDA)f.expr).body;
    assertEquals("Int", body.inferredType.toString());
  }
```

注意(执行时判定依据,来自实际语法验证):字面量-only 的表达式会被 Parser 的 ConstantFolder 在解析期折叠成常量节点(`1 <=> 2` 折叠为 NUMBER),新规则根本不会执行——因此测试必须用变量作操作数(IDENT 类型为 Any 不影响比较/逻辑规则,它们无条件推导 Boolean);顶层 `{ ... }` 是 MAP 字面量语法,COMPOUND 只能经由函数体访问(用 `def()` helper 取 LAMBDA body)。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: 新用例失败(节点类型为 Any 或推断错误)

- [ ] **Step 3: 实现规则**

在 TypeInference 的 `visit(ELNode.COND)` 之前插入以下方法(注意 EQ/NE 等 Binary 子类共用规则,逐个实现;`IN`/`INSTANCEOF` 有额外字段但结果仍是 Boolean):

```java
  @Override
  public void visit(ELNode.NOT e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.EMPTY e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.INSTANCEOF e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.EQ e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.NE e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IDEQ e) { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IDNE e) { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.LT e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.LE e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.GT e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.GE e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.CMP e) { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IN e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }

  @Override
  public void visit(ELNode.OR e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.AND e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CAT e) {
    super.visit(e);
    setType(e, PrimitiveType.of(String.class));
  }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: BUILD SUCCESS(13 个测试 = 8 既有 + 5 新增)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/elite/ir/TypeInference.java src/test/java/org/elite/ir/TypeInferenceTest.java
git commit -m "feat(type): infer unary, comparison and logical operations

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: 容器类型(List/Tuple/Map/Array/Range/Cons/Access)

**Files:**
- Modify: `src/main/java/org/elite/ir/TypeInference.java`
- Test: `src/test/java/org/elite/ir/TypeInferenceTest.java`(追加用例)

**Interfaces:**
- Consumes: Task 2 骨架(merge/typeOf/setType);节点字段:`CONS.head/tail`、`TUPLE.elems`、`MAP.keys/values`、`ARRAY.type/dims`、`NEW.base`、`RANGE.begin/next/end`、`ACCESS.right/index`(右=对象,index=属性/下标)
- Produces: 追加规则:
  - `CONS` → `List[merge(元素类型, 尾部元素类型)]`(尾不是 List 类型 → `List[元素]`)
  - `TUPLE` → `Tuple[各元素类型]`;`MAP` → `Map[merge(各 key), merge(各 value)]`
  - `ARRAY` → `Array[type 注解解析或 Any, dims.length]`;`NEW`(数组,base 形如 `Int[]`)→ `Array[解析元素, 维数]`;`NEW`(类)→ Task 5
  - `RANGE` → `JavaClassType(elite.lang.Range.class)`
  - `ACCESS`:右操作数类型是 `ArrayType`/`ListType` → 元素类型;否则 Any
  - 本任务先不实现 APPLY(Task 5)

- [ ] **Step 1: 追加失败测试**

```java
  @Test
  void consList() {
    assertEquals("List[Int]", typeOf("[1, 2, 3]"));
  }

  @Test
  void tupleAndMap() {
    assertEquals("Tuple[String, Int]", typeOf("(\"Bob\", 42)"));
    assertEquals("Map[String, Int]", typeOf("{\"a\": 1, \"b\": 2}"));
  }

  @Test
  void arrayAndNewArray() {
    assertEquals("Array[Int, 2]", typeOf("new int[4][4]"));
    assertEquals("Array[Int, 1]", typeOf("new int[2](1, 2)"));
  }

  @Test
  void rangeType() {
    assertEquals("Range", typeOf("[1..10]"));
  }
```

注意(实际语法验证后的判定依据):`[1,2,3]` **和 `[1, 2]` 都**解析为 CONS 链(NIL 终止),靠 merge 的 Any-单位元语义收敛为 `List[Int]`;ARRAY 带初始化器的形态是 `new int[2](1, 2)`(元素合并走 init TUPLE);`cons(1, [2])` 是 APPLY 调用(Task 5 才处理),删去;RANGE 语法是 `[1..10]`(方括号区间字面量),裸 `1..10` 不能解析;列表元素 ACCESS(`l[0]`)依赖变量类型(Task 5),本任务的 `listIndexAccess` 用例移到 Task 5(变量 `define l = [1, 2, 3]` 后 `l[0]` → `Int`)。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: 新用例失败

- [ ] **Step 3: 实现规则**

在 TypeInference 中插入(放在 `visit(ELNode.CAT)` 之后):

```java
  @Override
  public void visit(ELNode.CONS e) {
    super.visit(e);
    Type head = typeOf(e.head);
    Type tail = typeOf(e.tail);
    if (tail instanceof ListType lt) {
      setType(e, new ListType(merge(head, lt.element())));
    } else {
      setType(e, new ListType(head));
    }
  }

  @Override
  public void visit(ELNode.TUPLE e) {
    super.visit(e);
    Type[] elems = new Type[e.elems.length];
    for (int i = 0; i < e.elems.length; i++) {
      elems[i] = typeOf(e.elems[i]);
    }
    setType(e, new TupleType(elems));
  }

  @Override
  public void visit(ELNode.MAP e) {
    super.visit(e);
    Type key = AnyType.ANY;
    Type value = AnyType.ANY;
    boolean first = true;
    for (int i = 0; i < e.keys.length; i++) {
      if (first) {
        key = typeOf(e.keys[i]);
        value = typeOf(e.values[i]);
        first = false;
      } else {
        key = merge(key, typeOf(e.keys[i]));
        value = merge(value, typeOf(e.values[i]));
      }
    }
    setType(e, new MapType(key, value));
  }

  @Override
  public void visit(ELNode.ARRAY e) {
    super.visit(e);
    if (e.init != null) {
      // [1, 2] style: array literal with initializer tuple
      Type[] elems = new Type[e.init.elems.length];
      Type merged = AnyType.ANY;
      for (int i = 0; i < e.init.elems.length; i++) {
        elems[i] = typeOf(e.init.elems[i]);
        merged = i == 0 ? elems[i] : merge(merged, elems[i]);
      }
      setType(e, new ArrayType(merged, 1));
      return;
    }
    Type element = e.type != null ? resolveAnnotationType(e.type) : AnyType.ANY;
    setType(e, new ArrayType(element, e.dims.length));
  }

  @Override
  public void visit(ELNode.RANGE e) {
    super.visit(e);
    setType(e, new JavaClassType(elite.lang.Range.class));
  }

  @Override
  public void visit(ELNode.ACCESS e) {
    super.visit(e);
    Type target = typeOf(e.right);
    if (target instanceof ArrayType at) {
      setType(e, at.element());
    } else if (target instanceof ListType lt) {
      setType(e, lt.element());
    } else {
      setType(e, AnyType.ANY);
    }
  }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: BUILD SUCCESS(17 个测试 = 13 既有 + 4 新增)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/elite/ir/TypeInference.java src/test/java/org/elite/ir/TypeInferenceTest.java
git commit -m "feat(type): infer container types (list/tuple/map/array/range)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 函数与变量(Function/DEFINE/APPLY/RETURN/不可变与可变变量)

**Files:**
- Modify: `src/main/java/org/elite/ir/TypeInference.java`
- Test: `src/test/java/org/elite/ir/TypeInferenceTest.java`(追加用例)

**Interfaces:**
- Consumes: `Symbol.immutable`、`Symbol.isFunction()`、`Symbol.def`、`LAMBDA.rtype/vars/body`、`APPLY.right/args/keys`、`ASSIGN.left/right`、`ASSIGNOP.binary`、`FOREACH.var/range`、`RETURN.right`
- Produces(内部状态,本任务即终点):
  - `private final Map<SymbolTable.Symbol, java.util.List<Type>> assigns` —— 可变变量收集的 ASSIGN 右值类型
  - `private final Map<SymbolTable.Symbol, Type> varTypes` —— 上一轮合并出的变量类型;每轮结束时 `recomputeVarTypes()`:`assigns` 为空 → 无(初始值在 DEFINE 处处理);对所有 assigns 逐对 merge;全相等且非 Any → 该类型,否则 Any;有标注的变量在 `visit(DEFINE)` 直接 `varTypes.put(sym, 标注)`
  - `visit(IDENT)`:`symbol == null` → Any;`symbol.isFunction()` → `typeOf(symbol.def)`;否则 `varTypes.get(symbol)`(无则 Any)
  - `visit(DEFINE)`(变量,expr 非 LAMBDA/CLASSDEF):有标注 → 标注(且覆盖 expr);无标注且 `symbol.immutable` → expr 类型(并 `varTypes.put(sym, t)`);可变 → `varTypes.get(sym)`(初始并入 assigns 由 ASSIGN 收集;无 ASSIGN 的可变变量回退 expr 类型)
  - `visit(ASSIGN)`:left 是 IDENT 且 symbol 非函数/类 → `assigns.computeIfAbsent(sym,...).add(typeOf(right))`;setType → right 类型
  - `visit(ASSIGNOP)`:`super.visit`;setType → typeOf(left);**不写入 assigns**(约定不改变变量类型)
  - `visit(LAMBDA)`:先 super;返回类型 = rtype 标注解析,无标注则 body 类型;参数类型 = 各 `vars[i].type` 标注解析,无标注 Any;`setType(e, new FunctionType(ret, params))`;有 rtype 标注时同时 `setType(e.body, ret)`(标注不提升的向下传播)
  - `visit(RETURN)`:setType → typeOf(right);多 RETURN 的合并由 LAMBDA 的 body 合并驱动——注意:body 是 COMPOUND 时取最后表达式,故在 `visit(LAMBDA)` 中返回类型取 `typeOf(e.body)`;若 body 内存在 RETURN 且其类型与最后表达式不同,v1 取 body 类型(RETURN 合并为后续增强,规格已含"所有 RETURN 归一化合并",本任务实现:**收集 LAMBDA 子树内所有 RETURN.right 类型与 body 类型做 merge**,用内部 `findReturns(e.body)` 遍历收集)
  - `visit(APPLY)`:target = typeOf(right);FunctionType → returnType;目标是 DEFINE(LAMBDA)节点的 IDENT 时走 IDENT 规则已得 FunctionType;target 是 JavaClassType → 反射:`javaClass().getMethods()` 中找 method name(ACCESS.index 为 STRINGVAL 取 value,IDENT 取 id)+ 参数个数 == args.length 且无 keys → 返回类型 PrimitiveType.of/JavaClassType;找不到/有 keys → Any;其余 → Any
  - `visit(FOREACH)`:range 类型 ListType/ArrayType → 元素类型 setType 到 var DEFINE(和其 symbol 的 varTypes);var 节点类型与 body 类型均设置;`visit(WHILE/FOR/FOR`)`→ body 类型;`visit(MATCH)`→ merge(各 CASE bodies + deflt);`visit(CASE)`→ merge(bodies)
- [ ] **Step 1: 追加失败测试**

```java
  @Test
  void functionType() {
    assertEquals("Function[Int, [Int, Int]]",
      typeOf("define add(x::Int, y::Int)::Int => x + y\ndefine _ = add"));
  }

  @Test
  void functionReturnAnnotationPinsBody() {
    ELNode.DEFINE add = def("define add(x::Int, y::Int)::Int => x + y", "add");
    ELNode body = ((ELNode.LAMBDA)add.expr).body;
    assertEquals("Int", body.inferredType.toString());
  }

  @Test
  void applyUserFunction() {
    assertEquals("Int", typeOf("define add(x::Int, y::Int)::Int => x + y\nadd(1, 2)"));
  }

  @Test
  void immutableVariableFixedByInitializer() {
    assertEquals("Int", typeOf("define x = 42\nx + 1"));
  }

  @Test
  void mutableVariableMergedFromAssigns() {
    assertEquals("Int", typeOf("define x = 0\nx = 1\nx = 2\nx"));
  }

  @Test
  void mutableVariableConflictIsAny() {
    assertEquals("Any", typeOf("define x = 0\nx = 1\nx = \"s\"\nx"));
  }

  @Test
  void assignopDoesNotChangeType() {
    assertEquals("Int", typeOf("define x = 1\nx += 2\nx"));
  }

  @Test
  void foreachElementType() {
    assertEquals("Int", typeOf("for (i in [1, 2]) print(i)\n\"done\""));
  }

  @Test
  void javaMethodReflection() {
    assertEquals("Int", typeOf("java.lang.Integer.parseInt(\"1\")"));
    assertEquals("String", typeOf("\"abc\".substring(1)"));
  }

  @Test
  void newEliteClass() {
    assertEquals("Point", typeOf("class Point(x, y)\nnew Point(1, 2)"));
  }

  @Test
  void listIndexAccess() {
    assertEquals("Int", typeOf("define l = [1, 2, 3]\nl[0]"));
  }

  @Test
  void annotatedAssignMismatchIsError() {
    ELProgram program = parse("define x::Int\nx = \"hello\"");
    SymbolTableBuilder.build(program);
    ParseException ex = assertThrows(ParseException.class,
      () -> TypeInference.infer(elctx, program));
    assertTrue(ex.getMessage().contains("Type mismatch"),
      "unexpected message: " + ex.getMessage());
    // exps[0] = define x::Int, exps[1] = the ASSIGN
    ELNode assign = program.getExpressions().get(1);
    assertEquals("Error", assign.inferredType.toString());
  }

  @Test
  void annotatedInitializerMismatchIsError() {
    ELProgram program = parse("define x::Int = \"hello\"");
    SymbolTableBuilder.build(program);
    assertThrows(ParseException.class, () -> TypeInference.infer(elctx, program));
    assertEquals("Error", program.getExpressions().get(0).inferredType.toString());
  }
```

注意:`"abc".substring(1)` 的目标是 ACCESS(STRINGVAL 的 `.substring`),String 字面量的 ACCESS 类型目前是 Any(Task 4 的 ACCESS 规则只处理 Array/List)→ Java 反射需要 ACCESS 的右操作数类型是 JavaClassType;本用例若失败,检查是否需要在 `visit(ACCESS)` 中把 `typeOf(e.right) instanceof PrimitiveType pt` 时给 ACCESS 节点标目标类型……不——反射在 `visit(APPLY)` 中应基于 `e.right`(APPLY 的 target)类型;`"abc".substring(1)` 的 APPLY.right 是 ACCESS 节点,其类型为 Any。**实现时**:`visit(APPLY)` 中若 target 是 ACCESS acc 且 `typeOf(acc.right)` 是 PrimitiveType/JavaClassType,则用 `typeOf(acc.right)` 的类做反射。此注释是执行时判定依据,若测试失败按此实现。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: 新用例失败

- [ ] **Step 3: 实现规则**

对 TypeInference 做以下修改:

**(a) 类字段与状态**(加在 `private boolean changed;` 之后):

```java
  private final Map<SymbolTable.Symbol, java.util.List<Type>> assigns = new HashMap<>();
  private final Map<SymbolTable.Symbol, Type> varTypes = new HashMap<>();
```

**(b) infer() 主循环改为在每轮结束后重算变量类型**(把 `do { ... } while (...)` 中的循环体后追加 `infer.recomputeVarTypes();`——即循环体内 scan 完 definitions/expressions 后调用;并把循环条件保留):

```java
    int passes = 0;
    do {
      infer.changed = false;
      for (ELNode node : program.getDefinitions()) {
        infer.scan(node);
      }
      for (ELNode node : program.getExpressions()) {
        infer.scan(node);
      }
      infer.recomputeVarTypes();
    } while (infer.changed && ++passes < MAX_PASSES);
```

**(c) 新增方法**(放在 `merge` 之后):

```java
  /** Merges the collected ASSIGN right-hand types into per-variable types. */
  private void recomputeVarTypes() {
    for (Map.Entry<SymbolTable.Symbol, java.util.List<Type>> e : assigns.entrySet()) {
      Type merged = AnyType.ANY;
      boolean first = true;
      for (Type t : e.getValue()) {
        merged = first ? t : merge(merged, t);
        first = false;
      }
      Type old = varTypes.get(e.getKey());
      if (old == null || !old.equals(merged)) {
        varTypes.put(e.getKey(), merged);
        changed = true;
      }
    }
  }

  /** Collects every RETURN node under the given subtree. */
  private static void collectReturns(ELNode node, java.util.List<ELNode.RETURN> out) {
    if (node == null) return;
    if (node instanceof ELNode.RETURN r) {
      out.add(r);
      return;
    }
    if (node instanceof ELNode.LAMBDA) {
      return;   // do not descend into nested lambdas
    }
    new DefaultVisitor() {
      @Override
      public void visit(ELNode.RETURN r) {
        out.add(r);
      }

      @Override
      public void visit(ELNode.LAMBDA l) {
        // skip nested lambdas
      }
    }.scan(node);
  }
```

**(d) 改写 visit(DEFINE)**(替换 Task 2 版本):

```java
  @Override
  public void visit(ELNode.DEFINE e) {
    super.visit(e);

    Type annotated = resolveAnnotationType(e.type);
    if (annotated != AnyType.ANY) {
      if (e.expr != null && !compatible(annotated, typeOf(e.expr))) {
        reportError(e.pos, _T(IR_TYPE_MISMATCH, annotated, typeOf(e.expr)));
        setType(e, ErrorType.ERROR);
        setType(e.expr, ErrorType.ERROR);
        if (e.symbol != null) {
          varTypes.put(e.symbol, ErrorType.ERROR);
        }
        return;
      }
      setType(e, annotated);
      if (e.expr != null) {
        setType(e.expr, annotated);
      }
      if (e.symbol != null) {
        varTypes.put(e.symbol, annotated);
      }
      return;
    }

    if (e.expr instanceof ELNode.LAMBDA || e.expr instanceof ELNode.CLASSDEF) {
      // Function / class definition: type comes from the definition body.
      setType(e, typeOf(e.expr));
      return;
    }

    // Plain variable.
    Type t = typeOf(e.expr);
    if (e.symbol != null) {
      if (e.symbol.immutable) {
        varTypes.put(e.symbol, t);
      } else {
        // Mutable: initializer participates as one "assignment".
        assigns.computeIfAbsent(e.symbol, k -> new java.util.ArrayList<>());
        if (!assigns.get(e.symbol).contains(t)) {
          assigns.get(e.symbol).add(t);
        }
        t = varTypes.containsKey(e.symbol) ? varTypes.get(e.symbol) : t;
      }
    }
    setType(e, t);
  }
```

**(e) 改写 visit(IDENT)**:

```java
  @Override
  public void visit(ELNode.IDENT e) {
    SymbolTable.Symbol sym = e.symbol;
    if (sym == null) {
      setType(e, AnyType.ANY);
    } else if (sym.isFunction()) {
      setType(e, typeOf(sym.def));
    } else {
      Type t = varTypes.get(sym);
      setType(e, t != null ? t : AnyType.ANY);
    }
  }
```

**(f) 新增 ASSIGN/ASSIGNOP/LAMBDA/RETURN/APPLY/FOREACH/WHILE/FOR/MATCH/CASE/NEW 规则**(插入到 `visit(ELNode.CAT)` 之后):

```java
  @Override
  public void visit(ELNode.ASSIGN e) {
    super.visit(e);
    Type t = typeOf(e.right);
    if (e.left instanceof ELNode.IDENT id && id.symbol != null &&
        !id.symbol.isFunction() && id.symbol.clazz == null) {
      Type expected = varTypes.get(id.symbol);
      if (expected != null && !compatible(expected, t)) {
        reportError(e.pos, _T(IR_TYPE_MISMATCH, expected, t));
        varTypes.put(id.symbol, ErrorType.ERROR);
        setType(e, ErrorType.ERROR);
        return;
      }
      assigns.computeIfAbsent(id.symbol, k -> new java.util.ArrayList<>());
      if (!assigns.get(id.symbol).contains(t)) {
        assigns.get(id.symbol).add(t);
      }
    }
    setType(e, t);
  }

  /**
   * Whether a value of type actual may be stored into a variable of the
   * annotated type expected. Cannot judge Any / promotion markers /
   * errors; both-numeric is allowed here and refined (widening check)
   * in the numeric task.
   */
  private boolean compatible(Type expected, Type actual) {
    if (actual == AnyType.ANY || actual == PrimitiveType.ANY_INT ||
        actual == PrimitiveType.ANY_FLOAT || actual == ErrorType.ERROR) {
      return true;   // cannot judge
    }
    if (expected.equals(actual)) {
      return true;
    }
    if (isNumeric(expected) && isNumeric(actual)) {
      return true;   // refined in Task 6
    }
    return false;
  }

  private boolean isNumeric(Type t) {
    if (t == PrimitiveType.ANY_INT || t == PrimitiveType.ANY_FLOAT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return Number.class.isAssignableFrom(pt.javaClass()) ||
             pt.javaClass() == Character.class;
    }
    return false;
  }

  @Override
  public void visit(ELNode.ASSIGNOP e) {
    super.visit(e);
    // By convention a compound assignment does not change the variable type.
    setType(e, typeOf(e.left));
  }

  @Override
  public void visit(ELNode.LAMBDA e) {
    super.visit(e);

    Type[] params = new Type[e.vars.length];
    for (int i = 0; i < e.vars.length; i++) {
      Type p = resolveAnnotationType(e.vars[i].type);
      params[i] = p != AnyType.ANY ? p : AnyType.ANY;
    }

    Type ret = resolveAnnotationType(e.rtype);
    if (ret == AnyType.ANY) {
      // Unannotated: merge the body type with every RETURN expression.
      java.util.List<ELNode.RETURN> returns = new java.util.ArrayList<>();
      collectReturns(e.body, returns);
      ret = typeOf(e.body);
      for (ELNode.RETURN r : returns) {
        ret = merge(ret, typeOf(r.right));
      }
    } else {
      // Annotated return type pins the body (no promotion).
      setType(e.body, ret);
    }

    setType(e, new FunctionType(ret, params));
  }

  @Override
  public void visit(ELNode.RETURN e) {
    super.visit(e);
    setType(e, typeOf(e.right));
  }

  @Override
  public void visit(ELNode.APPLY e) {
    super.visit(e);

    Type target = typeOf(e.right);
    if (target instanceof FunctionType ft) {
      setType(e, ft.returnType());
      return;
    }

    // Java method call: obj.method(args) — target is an ACCESS whose
    // receiver type carries the class.
    Type receiver = target;
    String method = null;
    if (e.right instanceof ELNode.ACCESS acc) {
      receiver = typeOf(acc.right);
      method = acc.index instanceof ELNode.STRINGVAL s
        ? s.value : acc.index instanceof ELNode.IDENT id ? id.id : null;
    }
    Class<?> cls = receiver instanceof JavaClassType jc ? jc.javaClass()
      : receiver instanceof PrimitiveType pt && pt.javaClass() != null
        ? pt.javaClass() : null;
    if (cls != null && method != null && (e.keys == null || e.keys.length == 0)) {
      Type rt = methodReturnType(cls, method, e.args.length);
      if (rt != null) {
        setType(e, rt);
        return;
      }
    }
    setType(e, AnyType.ANY);
  }

  /** Reflectively finds the return type of the named method; null if ambiguous. */
  private Type methodReturnType(Class<?> cls, String name, int argCount) {
    java.lang.reflect.Method match = null;
    for (java.lang.reflect.Method m : cls.getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == argCount) {
        if (match != null) {
          return null;   // ambiguous overload — give up
        }
        match = m;
      }
    }
    if (match == null) return null;
    Class<?> rt = match.getReturnType();
    if (rt == void.class || rt == Void.class) {
      return PrimitiveType.of(void.class);
    }
    if (TypeCoercion.typeof(rt) != TypeCoercion.UNKNOWN_TYPE) {
      return PrimitiveType.of(rt);
    }
    return new JavaClassType(rt);
  }

  @Override
  public void visit(ELNode.FOREACH e) {
    super.visit(e);
    Type range = typeOf(e.range);
    Type element = AnyType.ANY;
    if (range instanceof ListType lt) {
      element = lt.element();
    } else if (range instanceof ArrayType at) {
      element = at.element();
    }
    if (e.var != null) {
      setType(e.var, element);
      if (e.var.symbol != null) {
        varTypes.put(e.var.symbol, element);
      }
    }
    setType(e, typeOf(e.body));
  }

  @Override
  public void visit(ELNode.WHILE e) {
    super.visit(e);
    setType(e, typeOf(e.body));
  }

  @Override
  public void visit(ELNode.FOR e) {
    super.visit(e);
    setType(e, typeOf(e.body));
  }

  @Override
  public void visit(ELNode.MATCH e) {
    super.visit(e);
    Type t = AnyType.ANY;
    boolean first = true;
    for (ELNode.CASE alt : e.alts) {
      t = first ? typeOf(alt) : merge(t, typeOf(alt));
      first = false;
    }
    if (e.deflt != null) {
      t = first ? typeOf(e.deflt) : merge(t, typeOf(e.deflt));
    }
    setType(e, t);
  }

  @Override
  public void visit(ELNode.CASE e) {
    super.visit(e);
    Type t = AnyType.ANY;
    boolean first = true;
    for (ELNode body : e.bodies) {
      t = first ? typeOf(body) : merge(t, typeOf(body));
      first = false;
    }
    setType(e, t);
  }

  @Override
  public void visit(ELNode.NEW e) {
    super.visit(e);
    // NEW.base is an annotated type node: resolve it (ELite class or
    // compile-time-visible Java class); unresolvable falls back to Any.
    setType(e, resolveAnnotationType(e.base));
  }
```

**(g) 注意**:`visit(FOREACH)` 中 `e.index` 也可能存在(index DEFINE)→ 同样按 `Int` 处理(范围索引);若 `e.index != null` 则 `setType(e.index, PrimitiveType.of(Integer.class))`。补进 (f) 的 FOREACH 实现里。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: BUILD SUCCESS(31 个测试)。Step 1 注释中的 Java 反射用例若不通过,按注释的判定依据修正 `visit(APPLY)`。Task 6 将把 `isNumeric` 替换为带归一化的版本,本任务的 `isNumeric` 是临时最简版(两者行为一致,Task 6 只是把"both-numeric"细化成"加宽检查")。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/elite/ir/TypeInference.java src/test/java/org/elite/ir/TypeInferenceTest.java
git commit -m "feat(type): infer functions, variables and calls

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 数值提升与归一化(AnyInt/AnyFloat)

**Files:**
- Modify: `src/main/java/org/elite/ir/TypeInference.java`
- Test: `src/test/java/org/elite/ir/TypeInferenceTest.java`(追加用例)

**Interfaces:**
- Consumes: 算术节点 ADD/SUB/MUL/DIV/REM/POW/IDIV(`Arithmetic` 子类,left/right)、位运算 BITOR/BITAND/XOR/SHL/SHR/USHR、一元 NEG/POS/BITNOT/INC/DEC
- Produces:
  - `private Type numericResult(Type l, Type r, boolean constantFold)` —— 常量折叠与归一化的统一入口(见规则)
  - `private Type normalize(Type a, Type b)` —— 混合类型归一化(替换 Task 2 的 merge 语义中数值部分:merge 保持原样用于分支/变量,数值归一化单独走 normalize)
  - `protected Type merge(Type a, Type b)` **扩展**:相等→a;任一 Any→ANY;两者都是"数值族"(PrimitiveType 具体数值或 ANY_INT/ANY_FLOAT)→ `normalize(a, b)`;否则 Any
  - 规则(所有"具体数值"指 PrimitiveType 且 javaClass 为 Number 子类或 Character):
    - 常量折叠(两操作数都是 NUMBER 且 op 是 ADD/SUB/MUL):用 long 运算,溢出(long 范围超界用 BigInteger 重算)时沿 Int→Long→BigInt 取最窄可容纳者;浮点常量按 Double 算(溢出→BigDecimal 不实现,Double 即可)
    - 非折叠算术(ADD/SUB/MUL/DIV/REM/POW/IDIV):任一 String → String(仅 ADD/CAT);否则整型族链 Int/Long/BigInt 与 AnyInt:结果含 AnyInt → AnyInt;BigInt+BigInt → BigInt;整型族具体+具体 → AnyInt;整型族 × 浮点族(Float/Double/BigDecimal/AnyFloat)→ 浮点侧(BigDecimal → BigDecimal,否则 AnyFloat);Decimal/Rational 参与 → normalize 取距离序更宽者(具体);Double+Double → AnyFloat;BigDecimal → BigDecimal
    - 位运算:Boolean 操作数 → Boolean;否则按整型族规则(BITOR/BITAND/XOR → 同算术整型族;SHL/SHR/USHR → 左操作数的整型族类型,具体则 AnyInt)
    - 一元:NEG/POS → 数值操作数规则(NEG 具体整型 → AnyInt,BigInt → BigInt,浮点 → AnyFloat/BigDecimal);BITNOT → 同 NEG 的整型族部分;INC/DEC → 操作数整型族规则;NOT 已在 Task 3
  - `normalize` 距离序(依据 TypeCoercion DISTANCE):Byte<Short<Char<Int<Long<Float<Double<Decimal<Rational<BigInt<BigDecimal;ANY_INT 与整型族 → ANY_INT;ANY_INT 与浮点族 → 浮点侧(AnyFloat 或具体);ANY_FLOAT 与任何数值 → ANY_FLOAT;非数值 → Any

- [ ] **Step 1: 追加失败测试**

```java
  @Test
  void intArithmeticIsAnyInt() {
    assertEquals("AnyInt", typeOf("1 + 2"));
    assertEquals("AnyInt", typeOf("1 * 2"));
  }

  @Test
  void constantFoldWithinInt() {
    // 1+2 both constant: still AnyInt per "未标注的整型算术" —
    // constant folding only widens on overflow.
    assertEquals("AnyInt", typeOf("1 + 2"));
  }

  @Test
  void annotatedArithmeticDoesNotPromote() {
    assertEquals("Int", typeOf("define add(x::Int, y::Int)::Int => x + y\nadd(1, 2)"));
  }

  @Test
  void bigintArithmeticIsBigInt() {
    assertEquals("BigInt", typeOf("define b::BigInt = 1\nb + 2"));
  }

  @Test
  void mixedIntLong() {
    assertEquals("Long", typeOf("define l::Long = 1\nl + 1"));
  }

  @Test
  void floatArithmeticIsAnyFloat() {
    assertEquals("AnyFloat", typeOf("1.5 + 2.5"));
  }

  @Test
  void intFloatMixIsAnyFloat() {
    assertEquals("AnyFloat", typeOf("define d::Double = 1.5\n1 + d"));
  }

  @Test
  void decimalStaysDecimal() {
    assertEquals("Decimal", typeOf("define m::elite.lang.Decimal = 1.5M\nm + 1"));
  }

  @Test
  void numericNarrowingAssignIsError() {
    ELProgram program = parse("define x::Int\nx = 2.5");
    SymbolTableBuilder.build(program);
    ParseException ex = assertThrows(ParseException.class,
      () -> TypeInference.infer(elctx, program));
    assertTrue(ex.getMessage().contains("Type mismatch"),
      "unexpected message: " + ex.getMessage());
  }

  @Test
  void numericWideningAssignIsOk() {
    assertEquals("Long", typeOf("define x::Long = 1\nx"));
  }
```

注意:`1.5M` 的字面量语法若不存在,把 Decimal 用例改为注解变量 + 算术(如上已写);若 `Decimal` 名解析失败(需 import 或全名),测试按实际语法调整。**执行时以解析成功为准,失败则调整语法而非实现。**

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: 新用例失败

- [ ] **Step 3: 实现规则**

**(a) 扩展 merge 与 compatible**(替换 Task 2 版 merge;`isNumeric` 保持 Task 5 版;`compatible` 替换 Task 5 版,数值改为加宽检查):

```java
  protected Type merge(Type a, Type b) {
    if (a == ErrorType.ERROR || b == ErrorType.ERROR) return ErrorType.ERROR;
    if (a.equals(b)) return a;
    if (a == AnyType.ANY) return b;
    if (b == AnyType.ANY) return a;
    if (isNumeric(a) && isNumeric(b)) return normalize(a, b);
    return AnyType.ANY;
  }

  private boolean compatible(Type expected, Type actual) {
    if (actual == AnyType.ANY || actual == PrimitiveType.ANY_INT ||
        actual == PrimitiveType.ANY_FLOAT || actual == ErrorType.ERROR) {
      return true;   // cannot judge
    }
    if (expected.equals(actual)) {
      return true;
    }
    // Numeric assignment is allowed only when the value widens to the
    // annotated type (e.g. Int -> Long ok, Double -> Int rejected).
    if (isNumeric(expected) && isNumeric(actual)) {
      return normalize(actual, expected).equals(expected);
    }
    return false;
  }
```

**(b) 新增归一化与数值规则**(插在 merge 之后):

```java
  /** Widening rank per TypeCoercion's numeric distance order. */
  private static final java.util.List<String> NUMERIC_ORDER = java.util.List.of(
    "Byte", "Short", "Char", "Int", "Long", "Float", "Double",
    "Decimal", "Rational", "BigInt", "BigDecimal");

  private int rank(PrimitiveType pt) {
    int i = NUMERIC_ORDER.indexOf(pt.toString());
    return i >= 0 ? i : -1;
  }

  private boolean isIntFamily(Type t) {
    if (t == PrimitiveType.ANY_INT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return pt.javaClass() == Byte.class || pt.javaClass() == Short.class ||
             pt.javaClass() == Character.class ||
             pt.javaClass() == Integer.class || pt.javaClass() == Long.class ||
             pt.javaClass() == java.math.BigInteger.class;
    }
    return false;
  }

  private boolean isFloatFamily(Type t) {
    if (t == PrimitiveType.ANY_FLOAT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return pt.javaClass() == Float.class || pt.javaClass() == Double.class ||
             pt.javaClass() == java.math.BigDecimal.class;
    }
    return false;
  }

  /** Normalizes two concrete numeric types to the wider one per the
   *  TypeCoercion distance order; ANY_INT/ANY_FLOAT follow family rules. */
  private Type normalize(Type a, Type b) {
    if (a == PrimitiveType.ANY_INT && isIntFamily(b)) return PrimitiveType.ANY_INT;
    if (b == PrimitiveType.ANY_INT && isIntFamily(a)) return PrimitiveType.ANY_INT;
    if (a == PrimitiveType.ANY_FLOAT || b == PrimitiveType.ANY_FLOAT) {
      return PrimitiveType.ANY_FLOAT;
    }
    if (isFloatFamily(a) && (isFloatFamily(b) || isIntFamily(b))) return a;
    if (isFloatFamily(b) && (isFloatFamily(a) || isIntFamily(a))) return b;
    int ra = rank((PrimitiveType)a);
    int rb = rank((PrimitiveType)b);
    if (ra < 0 || rb < 0) return AnyType.ANY;
    return ra >= rb ? a : b;
  }

  /** Types an arithmetic-like binary node with promotion semantics. */
  private Type numericResult(ELNode.Binary e, Type lt, Type rt) {
    if (lt == ErrorType.ERROR || rt == ErrorType.ERROR) {
      return ErrorType.ERROR;
    }
    if (lt.toString().equals("String") || rt.toString().equals("String")) {
      return PrimitiveType.of(String.class);
    }
    if (!isNumeric(lt) || !isNumeric(rt)) return AnyType.ANY;

    // Int-family arithmetic promotes (may overflow at runtime).
    if (isIntFamily(lt) && isIntFamily(rt)) {
      if (lt == PrimitiveType.of(java.math.BigInteger.class) &&
          rt == PrimitiveType.of(java.math.BigInteger.class)) {
        return lt;   // chain top: no further promotion
      }
      return PrimitiveType.ANY_INT;
    }
    if (isFloatFamily(lt) && isFloatFamily(rt)) {
      if (lt == PrimitiveType.of(java.math.BigDecimal.class) &&
          rt == PrimitiveType.of(java.math.BigDecimal.class)) {
        return lt;
      }
      return PrimitiveType.ANY_FLOAT;
    }
    if (isFloatFamily(lt) && isIntFamily(rt)) return isAnyMarker(lt) ? lt : lt;
    if (isIntFamily(lt) && isFloatFamily(rt)) return rt;
    // Decimal / Rational and any other numeric: widen by distance order.
    return normalize(lt, rt);
  }

  private boolean isAnyMarker(Type t) {
    return t == PrimitiveType.ANY_INT || t == PrimitiveType.ANY_FLOAT;
  }
```

**(c) 算术节点规则**(插到 `visit(ELNode.CAT)` 之后):

```java
  private void visitArithmetic(ELNode.Binary e) {
    Type lt = typeOf(e.left);
    Type rt = typeOf(e.right);
    setType(e, numericResult(e, lt, rt));
  }

  @Override public void visit(ELNode.ADD e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.SUB e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.MUL e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.DIV e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.REM e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.POW e) { super.visit(e); visitArithmetic(e); }

  @Override
  public void visit(ELNode.BITOR e) {
    super.visit(e);
    Type lt = typeOf(e.left), rt = typeOf(e.right);
    if (lt.toString().equals("Boolean") && rt.toString().equals("Boolean")) {
      setType(e, PrimitiveType.of(Boolean.class));
    } else {
      setType(e, numericResult(e, lt, rt));
    }
  }

  @Override public void visit(ELNode.BITAND e) {
    super.visit(e);
    Type lt = typeOf(e.left), rt = typeOf(e.right);
    if (lt.toString().equals("Boolean") && rt.toString().equals("Boolean")) {
      setType(e, PrimitiveType.of(Boolean.class));
    } else {
      setType(e, numericResult(e, lt, rt));
    }
  }

  @Override public void visit(ELNode.XOR e) {
    super.visit(e);
    setType(e, numericResult(e, typeOf(e.left), typeOf(e.right)));
  }

  @Override
  public void visit(ELNode.SHL e) {
    super.visit(e);
    Type lt = typeOf(e.left);
    setType(e, isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override public void visit(ELNode.SHR e) {
    super.visit(e);
    Type lt = typeOf(e.left);
    setType(e, isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override public void visit(ELNode.USHR e) {
    super.visit(e);
    Type lt = typeOf(e.left);
    setType(e, isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NEG e) {
    super.visit(e);
    Type t = typeOf(e.right);
    if (isIntFamily(t)) setType(e, t == PrimitiveType.of(java.math.BigInteger.class) ? t : PrimitiveType.ANY_INT);
    else if (isFloatFamily(t)) setType(e, t == PrimitiveType.of(java.math.BigDecimal.class) ? t : PrimitiveType.ANY_FLOAT);
    else setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.POS e) {
    super.visit(e);
    setType(e, typeOf(e.right));
  }

  @Override
  public void visit(ELNode.BITNOT e) {
    super.visit(e);
    Type t = typeOf(e.right);
    setType(e, isIntFamily(t) ? t : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.INC e) {
    super.visit(e);
    Type t = typeOf(e.right);
    setType(e, isIntFamily(t) ? PrimitiveType.ANY_INT : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.DEC e) {
    super.visit(e);
    Type t = typeOf(e.right);
    setType(e, isIntFamily(t) ? PrimitiveType.ANY_INT : AnyType.ANY);
  }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: BUILD SUCCESS(38 个测试)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/elite/ir/TypeInference.java src/test/java/org/elite/ir/TypeInferenceTest.java
git commit -m "feat(type): infer numeric promotion with AnyInt/AnyFloat

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: 消费方 ASTDumper.dumpTyped + CommandProvider.dump_ast 接入

**Files:**
- Modify: `src/main/java/org/elite/parser/ASTDumper.java`
- Modify: `src/main/java/org/elite/shell/command/CommandProvider.java:95-107`
- Test: `src/test/java/org/elite/parser/ASTDumperTest.java`(新)

**Interfaces:**
- Consumes: `ELNode.inferredType`、`TypeInference.infer(ELContext, ELProgram)`
- Produces:
  - `public static String dumpTyped(ELProgram program)`、`public static String dumpTyped(ELNode node)` —— 输出与 `dump()` 相同,但每个有 `inferredType` 的节点行追加 ` :: <type>`
  - `CommandProvider.dump_ast` 改为 parse → SymbolTableBuilder.build → **TypeInference.infer(elctx, program)** → `System.out.println(ASTDumper.dumpTyped(program))`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/org/elite/parser/ASTDumperTest.java`:

```java
package org.elite.parser;

import javax.el.ELContext;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.ir.SymbolTableBuilder;
import org.elite.ir.TypeInference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ASTDumperTest {

  private static ELContext elctx;

  @BeforeAll
  static void createContext() {
    elctx = ELEngine.createELContext();
  }

  private static String dumpTyped(String script) throws ParseException {
    ELProgram program = new Parser(elctx, script).parse();
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);
    return ASTDumper.dumpTyped(program);
  }

  @Test
  void typedDumpShowsInferredTypes() throws ParseException {
    String out = dumpTyped("define x::Int = 42");
    assertTrue(out.contains(" :: Int"), "missing type suffix, got:\n" + out);
  }

  @Test
  void plainDumpUnchanged() throws ParseException {
    ELProgram program = new Parser(elctx, "define x::Int = 42").parse();
    SymbolTableBuilder.build(program);
    String out = ASTDumper.dump(program);
    assertFalse(out.contains(" :: "), "plain dump must not contain type suffixes");
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=ASTDumperTest`
Expected: 编译错误(dumpTyped 不存在)

- [ ] **Step 3: 实现 dumpTyped**

`ASTDumper.java` 修改(现有 `dump(ELNode)`/`dump(ELProgram)` 保持原样,新增):

```java
  public static String dumpTyped(ELNode node) {
    StringBuilder sb = new StringBuilder();
    dumpNode(node, sb, "", true, true, true);
    return sb.toString();
  }

  public static String dumpTyped(ELProgram program) {
    StringBuilder sb = new StringBuilder();
    for (ELNode node : program.getDefinitions()) {
      dumpNode(node, sb, "", true, true, true);
      sb.append('\n');
    }
    for (ELNode node : program.getExpressions()) {
      dumpNode(node, sb, "", true, true, true);
      sb.append('\n');
    }
    return sb.toString();
  }
```

并把内部方法签名扩展:`dumpNode` 增加参数 `boolean withTypes`,原 `dump()` 两个方法调用处传 `false`;`dumpNode` 内部调用 `appendInlineFields(node, sb, withTypes)` 与递归 `dumpNode(...)`/`dumpNodeList(...)` 时透传 `withTypes`(dumpNodeList 同样加参数透传)。`appendInlineFields` 在 DEFINE immutable 块之后追加:

```java
    if (withTypes && node.inferredType != null) {
      sb.append(" :: ").append(node.inferredType);
    }
```

- [ ] **Step 4: 接入 CommandProvider.dump_ast**

将 `CommandProvider.java` 第 95-107 行的方法体改为(注意 `TypeInference.infer` 会抛 `ParseException`——推导错误消息打到 stderr,不打印 dump):

```java
  @Command("dump-ast")
  public static void dump_ast(ShellContext shellContext, String script) {
    if (script.isBlank())
      script = shellContext.getLastScript();
    if (script.isBlank())
      return;
    ScriptEngine engine = shellContext.getEngine();
    ELContext elctx = (ELContext)engine.get(ELContext.class.getName());
    Parser parser = new Parser(elctx, script);
    try {
      ELProgram program = parser.parse();
      program.setStandalone(true);
      SymbolTableBuilder.build(program);
      TypeInference.infer(elctx, program);
      System.out.println(ASTDumper.dumpTyped(program));
    } catch (ParseException e) {
      System.err.println(e.getMessage());
    }
  }
```

补 import:`import org.elite.ir.TypeInference;` 与 `import org.elite.parser.ASTDumper;`(`ParseException` 已是现有 import)。

- [ ] **Step 5: 运行测试确认通过 + 全量回归**

Run: `mvn -q test -Dtest=ASTDumperTest && mvn test`
Expected: ASTDumperTest 2 个通过;全套 817+ 测试(含新增 TypeTest/TypeInferenceTest/ASTDumperTest)全绿

- [ ] **Step 6: 手工验证**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home
BIN=target/elite-2.0.0-bin/elite-2.0.0/bin/xel
$BIN --dump-ast -e 'define add(x::Int, y::Int)::Int => x + y
define lst = [1, 2, 3]
add(1, 2)'
```

Expected: AST dump 中 DEFINE/IDENT/NUMBER/APPLY 等节点行出现 ` :: Int`、` :: List[Int]`、` :: AnyInt`(1+2 的字面量节点——注意 `1+2` 在标注函数体内被覆盖为 `:: Int`)等后缀;不带 `--dump-ast` 的行为不变。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/elite/parser/ASTDumper.java src/main/java/org/elite/shell/command/CommandProvider.java src/test/java/org/elite/parser/ASTDumperTest.java
git commit -m "feat(shell): show inferred types in --dump-ast

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## 计划自审记录

- 规格覆盖:类型模型含 ErrorType(Task 1)、inferredType 字段 + 注解解析 + 报错机制(Task 2)、骨架与收敛(Task 2)、字面量/一元/比较/逻辑(Task 2/3)、容器(Task 4)、函数与变量含不可变/可变/ASSIGNOP/标注兼容性(Task 5)、数值提升 AnyInt/AnyFloat 与归一化含数值加宽检查(Task 6)、消费方 dump-ast 捕获 ParseException(Task 7)——规格全部条目有对应任务;标注不提升由 Task 2/5 的标注覆盖机制实现;NULL→Any 在 Task 2;20 轮收敛上限在 Task 2 骨架;类加载失败报错(EL_CLASS_NOT_FOUND)在 Task 2;类型不匹配报错(IR_TYPE_MISMATCH)在 Task 5/6。
- 无占位符:所有步骤含完整代码或精确命令与预期。
- 一致性:`TypeInference.infer(ELContext, ELProgram) throws ParseException` 在 Task 2 定义,Task 7 按同签名调用并捕获;`typeOf/merge/setType/resolveAnnotationType/compatible/isNumeric/normalize` 内部签名各任务一致(isNumeric 于 Task 5 引入,Task 6 复用并细化 compatible);ErrorType 在 Task 1 定义,Task 2(merge/注解失败)、Task 5(标注不匹配)、Task 6(numericResult/兼容性)消费;Java 类加载走复制的 `loadJavaClass`(Task 2 Interfaces 注明复制来源与 TODO,失败 reportError EL_CLASS_NOT_FOUND),NEW.base 在 Task 5 复用 `resolveAnnotationType`;测试类总数与各任务预期数一致(6+8+5+5+12+10+2=48 个新测试)。
- 执行时判定点(明示,不属占位符):`[1,2,3]` 的 AST 形态(ARRAY 或 CONS 链)、`1.5M` Decimal 字面量语法、`"abc".substring(1)` 的 ACCESS 反射路径——测试失败时按计划内注释的判定依据调整,而非猜测实现。
