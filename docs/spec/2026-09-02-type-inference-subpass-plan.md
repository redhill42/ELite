# TypeInference 子 Pass 重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 TypeInference 重构为 Pass1(标注/常量/不可变变量,一次)+ Pass2(运算符)+ Pass3(反射复杂结点 + 语义检查)三阶段,新增 JavaMethodType/JavaFieldType/JavaConstructorType,收敛至全部结点非 null(含 Any)。

**Architecture:** 新子包 `org.elite.ir.infer`:`InferenceContext`(共享状态/helper)+ `Pass1/Pass2/Pass3`(DefaultVisitor)+ `TypeInference` 编排器(公开入口签名不变)。原 TypeInference.java 的规则按阶段迁移;Pass3 用 `MethodResolver`/`getField`/`getConstructors` 反射推导。IRBuilder 不动(语义检查暂时双份)。

**Tech Stack:** Java 17、JUnit 5.10.3、Maven;反射复用 `MethodResolver.resolveMethod/resolveStaticMethod(Class,String) → MethodClosure.getJavaMethod(int) → java.lang.reflect.Method`、`Class.getField/getDeclaredField`、`Class.getConstructors()`

## Global Constraints

- 规格:`docs/spec/2026-09-02-type-inference-subpass-design.md`(已批准,行为以它为准)
- 公开入口不变:`TypeInference.infer(ELContext, ELProgram) throws ParseException`(org.elite.ir 包)
- **不修改 IRBuilder**;语义检查与 IRBuilder 双份(用户已确认)
- Pass1/Pass2/Pass3 的前置条件不满足时**保持 inferredType == null**,绝不提前填 Any
- 最终强制 Any:循环结束(全部非 null / 停滞 / 50 轮上限)后,剩余 null → AnyType.ANY
- JavaConstructorType 落点(用户裁决):NEW/APPLY 类调用结点记构造器类型;值消费点经 `valueType` 解引用为声明类 JavaClassType;多义构造器回退 JavaClassType
- 现有 TypeInferenceTest 44 个测试行为等价(断言不变);全量回归 874+ 绿
- 迁移原则:方法整体搬移不改逻辑(除 Pass 归属与 valueTypeOf 消费点替换);每个任务 `mvn -q test -Dtest=<目标>` + `mvn -q compile` 门禁 + 单 commit(Co-Authored-By 尾注)
- 手工验证需 `export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home`
- 本机语法事实:`#'x'` 字符、`[1..10]` RANGE、字面量算式解析期折叠、`12b/3.5b/m/r` 后缀字面量

---

### Task 1: JavaMethodType / JavaFieldType / JavaConstructorType

**Files:**
- Create: `src/main/java/org/elite/type/JavaMethodType.java`、`JavaFieldType.java`、`JavaConstructorType.java`
- Test: `src/test/java/org/elite/type/TypeTest.java`(追加 2 个测试)

**Interfaces:**
- Consumes: Task 1 前无;`PrimitiveType.of`(既有)
- Produces(后续任务依赖):
  - `public final class JavaMethodType extends Type`:c'tor `(java.lang.reflect.Method method)`、`method()`、toString `JavaMethod[<ret> <DeclaringSimple>.<name>(<paramSimple>,...)]`(ret/param 显示名:void→"void",其余 `PrimitiveType.of` 的 toString;参数间 ", ")、equals/hashCode 按 `method`
  - `public final class JavaFieldType extends Type`:c'tor `(java.lang.reflect.Field field)`、`field()`、toString `JavaField[<typeSimple>]`(字段类型 `PrimitiveType.of(field.getType())` 显示名)、equals/hashCode 按 `field`
  - `public final class JavaConstructorType extends Type`:c'tor `(java.lang.reflect.Constructor<?> ctor)`、`ctor()`、`declaringClass()`、toString `JavaConstructor[<DeclaringSimple>(<paramSimple>,...)]`、equals/hashCode 按 `ctor`

- [ ] **Step 1: 追加失败测试**(TypeTest.java 类末尾追加)

```java
  @Test
  void javaReflectiveTypes() throws Exception {
    java.lang.reflect.Method println = java.io.PrintStream.class
      .getMethod("println", String.class);
    Type t = new JavaMethodType(println);
    assertEquals("JavaMethod[void PrintStream.println(String)]", t.toString());
    assertEquals(t, new JavaMethodType(println));

    java.lang.reflect.Field out = System.class.getField("out");
    Type f = new JavaFieldType(out);
    assertEquals("JavaField[PrintStream]", f.toString());
    assertEquals(f, new JavaFieldType(out));

    java.lang.reflect.Constructor<?> ctor =
      java.util.ArrayList.class.getConstructor();
    Type c = new JavaConstructorType(ctor);
    assertEquals("JavaConstructor[ArrayList()]", c.toString());
    assertEquals(c, new JavaConstructorType(ctor));
    assertNotEquals(c, t);
  }

  @Test
  void javaConstructorWithParams() throws Exception {
    java.lang.reflect.Constructor<?> ctor =
      java.awt.Point.class.getConstructor(int.class, int.class);
    assertEquals("JavaConstructor[Point(int, int)]",
      new JavaConstructorType(ctor).toString());
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=TypeTest`
Expected: 编译错误(类不存在)

- [ ] **Step 3: 实现三个类型类**

创建 `src/main/java/org/elite/type/JavaMethodType.java`:

```java
package org.elite.type;

import java.lang.reflect.Method;

/** A Java method resolved by reflection; the payload is the Method. */
public final class JavaMethodType extends Type {
  private final Method method;

  public JavaMethodType(Method method) {
    this.method = method;
  }

  public Method method() { return method; }

  @Override
  public String toString() {
    Class<?> ret = method.getReturnType();
    String retName = ret == void.class ? "void" : PrimitiveType.of(ret).toString();
    StringBuilder sb = new StringBuilder("JavaMethod[")
      .append(retName).append(' ')
      .append(method.getDeclaringClass().getSimpleName())
      .append('.').append(method.getName()).append('(');
    Class<?>[] params = method.getParameterTypes();
    for (int i = 0; i < params.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(PrimitiveType.of(params[i]));
    }
    return sb.append(")]").toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaMethodType that && method.equals(that.method);
  }

  @Override
  public int hashCode() { return method.hashCode(); }
}
```

创建 `src/main/java/org/elite/type/JavaFieldType.java`:

```java
package org.elite.type;

import java.lang.reflect.Field;

/** A Java field resolved by reflection; the payload is the Field. */
public final class JavaFieldType extends Type {
  private final Field field;

  public JavaFieldType(Field field) {
    this.field = field;
  }

  public Field field() { return field; }

  @Override
  public String toString() {
    return "JavaField[" + PrimitiveType.of(field.getType()) + "]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaFieldType that && field.equals(that.field);
  }

  @Override
  public int hashCode() { return field.hashCode(); }
}
```

创建 `src/main/java/org/elite/type/JavaConstructorType.java`:

```java
package org.elite.type;

import java.lang.reflect.Constructor;

/**
 * A Java constructor resolved by reflection; the payload is the
 * Constructor. Value-consumption sites dereference it to the declaring
 * class's JavaClassType via InferenceContext.valueType.
 */
public final class JavaConstructorType extends Type {
  private final Constructor<?> ctor;

  public JavaConstructorType(Constructor<?> ctor) {
    this.ctor = ctor;
  }

  public Constructor<?> ctor() { return ctor; }

  public Class<?> declaringClass() { return ctor.getDeclaringClass(); }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("JavaConstructor[")
      .append(ctor.getDeclaringClass().getSimpleName()).append('(');
    Class<?>[] params = ctor.getParameterTypes();
    for (int i = 0; i < params.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(PrimitiveType.of(params[i]));
    }
    return sb.append(")]").toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaConstructorType that && ctor.equals(that.ctor);
  }

  @Override
  public int hashCode() { return ctor.hashCode(); }
}
```

注意:上述三个类引用 `PrimitiveType`(同包,无需 import)。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=TypeTest`
Expected: BUILD SUCCESS(8 个测试 = 6 既有 + 2 新)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/elite/type/ src/test/java/org/elite/type/TypeTest.java
git commit -m "feat(type): add JavaMethod/JavaField/JavaConstructor reflective types

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: InferenceContext + Pass1 + 编排骨架

**Files:**
- Create: `src/main/java/org/elite/ir/infer/InferenceContext.java`、`Pass1.java`、`Pass2.java`(空壳)、`Pass3.java`(空壳)
- Modify: `src/main/java/org/elite/ir/TypeInference.java`(瘦身为编排器,公开入口不变)
- Test: `src/test/java/org/elite/ir/infer/Pass1Test.java`(新)

**Interfaces:**
- Consumes: Task 1 类型;现有 TypeInference.java 的成员(本任务搬移)
- Produces(后续任务依赖,签名精确):
  - `public final class InferenceContext`:
    - c'tor `(ELContext elctx, ELProgram program)`;`public ELContext elctx(); public String filename();`
    - `public Type typeOf(ELNode node)` —— null 时返回 `AnyType.ANY`(与现状一致)
    - `public boolean hasType(ELNode node)` —— `node.inferredType != null`
    - `public Type valueType(Type t)` —— `JavaConstructorType` → `new JavaClassType(t.declaringClass())`,其余原样
    - `public Type valueTypeOf(ELNode node)` —— `valueType(typeOf(node))`
    - `public void setType(ELNode node, Type type)`(相等比较版,与现状一致)
    - `public void resetProgress(); public boolean takeProgress();`(setType 内部置 progress=true)
    - 迁移 helper(整体搬移,签名不变):`reportError/failIfErrors/registerEliteClasses/typeOfValue/merge/normalize/numericResult/floatSideResult/rank/isIntFamily/isFloatFamily/isNumeric/isConcreteBigInt/isConcreteBigDecimal/isAnyMarker/compatible/recomputeVarTypes/collectReturns/loadJavaClass/resolveJavaClass/loadClassAtCompileTime/resolveAnnotationType/numberType/varType/methodReturnType/staticAccessReturnType/ALIASES` 及状态 `varTypes/assigns/topDefs/forVars/eliteClasses/errors`
  - `public class Pass1 extends DefaultVisitor`:c'tor `(InferenceContext ctx)`;`public boolean run(ELProgram program)`(遍历 definitions+expressions,返回本轮是否有新类型)
  - `public class Pass2 extends DefaultVisitor`、`public class Pass3 extends DefaultVisitor`:同形(Task 3/4 填充)
  - `TypeInference.infer`(编排):`ctx = new InferenceContext(...)` → `ctx.registerEliteClasses(program)` → `new Pass1(ctx).run(program)` → 循环(Pass2, Pass3)直到 `!ctx.takeProgress()`(Task 5 加收敛/强制 Any;本任务循环用现状 MAX_PASSES=20 上限占位)

- [ ] **Step 1: 创建 InferenceContext**

创建 `src/main/java/org/elite/ir/infer/InferenceContext.java`,按以下方式组装:
1. 包声明与 imports(从现有 TypeInference.java 原样搬,去 TypeInference 特有的)
2. 字段:`elctx/filename/errors/changed(改名 progress)/assigns/varTypes/topDefs/forVars/eliteClasses/ALIASES`
3. 整体搬移方法(不改逻辑):`reportError`(89-94)、`failIfErrors`(96-101)、`registerEliteClasses`(103-124)、`setType`(126-131,**改为**:相等则跳过,变化时置 `progress=true`)、`typeOf`(133-136)、`typeOfValue`(138-150)、`merge`(152-160)、`NUMERIC_ORDER/rank`(162-169)、`isIntFamily`(171-180)、`isFloatFamily`(182-191)、`normalize`(193-206)、`numericResult`(208-247)、`floatSideResult`(249-253)、`isConcreteBigInt`(255-258)、`isConcreteBigDecimal`(260-263)、`isAnyMarker`(265-268)、`recomputeVarTypes`(270-304,内部调用 merge)、`collectReturns`(305-326)、`loadJavaClass/resolveJavaClass/loadClassAtCompileTime`(328-370)、`resolveAnnotationType`(372-407)、`numberType`(476-492)、`compatible`(652-669)、`isNumeric`(671-688)、`varType`(690-699)、`methodReturnType`(817-837)、`staticAccessReturnType`(787-815)
4. 新增成员:`valueType(Type)`、`valueTypeOf(ELNode)`、`hasType(ELNode)`、`resetProgress()/takeProgress()`、getter `elctx()/filename()`
5. `recomputeVarTypes` 由谁调用:Pass2 每轮结束后(现有语义:每轮 infer 循环后调用一次)——本任务先保留在编排器循环内(`ctx.recomputeVarTypes()` 置于 Pass2 之后)

- [ ] **Step 2: 创建 Pass1**

创建 `src/main/java/org/elite/ir/infer/Pass1.java`:

```java
package org.elite.ir.infer;

import org.elite.eval.ELProgram;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.type.AnyType;
import org.elite.type.ELiteClassType;
import org.elite.type.ListType;
import org.elite.type.PrimitiveType;
import org.elite.type.Type;

/**
 * Pass 1: annotation types, constant types, and immutable variable types.
 * Runs once. Nodes it cannot type keep inferredType == null.
 */
public class Pass1 extends DefaultVisitor {

  private final InferenceContext ctx;

  public Pass1(InferenceContext ctx) {
    this.ctx = ctx;
  }

  public boolean run(ELProgram program) {
    ctx.resetProgress();
    for (ELNode node : program.getDefinitions()) {
      scan(node);
    }
    for (ELNode node : program.getExpressions()) {
      scan(node);
    }
    return ctx.takeProgress();
  }

  @Override
  public void visit(ELNode.DEFINE e) {
    super.visit(e);
    Type annotated = ctx.resolveAnnotationType(e.type);
    if (annotated != AnyType.ANY) {
      // Annotation is authoritative; keep the existing semantics
      // (compatibility check + ErrorType on mismatch) verbatim.
      if (e.expr != null && !ctx.compatible(annotated, ctx.typeOf(e.expr))) {
        ctx.reportError(e.pos, org.elite.resources.Resources._T(
          org.elite.resources.Resources.IR_TYPE_MISMATCH, annotated, ctx.typeOf(e.expr)));
        ctx.setType(e, org.elite.type.ErrorType.ERROR);
        if (e.expr != null) ctx.setType(e.expr, org.elite.type.ErrorType.ERROR);
        if (e.symbol != null) ctx.varTypes.put(e.symbol, org.elite.type.ErrorType.ERROR);
        return;
      }
      ctx.setType(e, annotated);
      if (e.expr != null) ctx.setType(e.expr, annotated);
      if (e.symbol != null) ctx.varTypes.put(e.symbol, annotated);
      return;
    }
    if (e.expr == null) {
      return;   // keep null; Pass 2 handles bare mutable defines
    }
    if (e.expr instanceof ELNode.LAMBDA || e.expr instanceof ELNode.CLASSDEF) {
      return;   // function/class definitions are Pass 2 / this pass respectively
    }
    if (e.symbol != null && e.symbol.immutable && ctx.hasType(e.expr)) {
      Type t = ctx.typeOf(e.expr);
      ctx.varTypes.put(e.symbol, t);
      ctx.setType(e, t);
    }
    // Mutable variables (and immutable ones whose initializer is not yet
    // typed) stay null here; Pass 2 collects assigns and merges.
  }

  @Override
  public void visit(ELNode.BOOLEANVAL e) {
    ctx.setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CHARVAL e) {
    ctx.setType(e, PrimitiveType.of(Character.class));
  }

  @Override
  public void visit(ELNode.STRINGVAL e) {
    ctx.setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.NUMBER e) {
    ctx.setType(e, ctx.numberType(e.value));
  }

  @Override
  public void visit(ELNode.NULL e) {
    ctx.setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NIL e) {
    ctx.setType(e, new ListType(AnyType.ANY));
  }

  @Override
  public void visit(ELNode.SYMBOL e) {
    ctx.setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.CONST e) {
    ctx.setType(e, ctx.typeOfValue(e.value));
  }

  @Override
  public void visit(ELNode.CLASSDEF e) {
    super.visit(e);
    if (e.symbol != null && e.symbol.clazz != null) {
      ctx.setType(e, new ELiteClassType(e.symbol));
    }
  }
}
```

注意:`IR_TYPE_MISMATCH` 常量在 `org.elite.resources.Resources` 已存在;若嫌 FQN 冗长可加 `import static org.elite.resources.Resources.*;`(与现文件一致,推荐)。现有 visit(DEFINE) 的 ErrorType 语义原样保留——对照现 TypeInference.java:409-455 逐行核对迁移正确性。

- [ ] **Step 3: 创建空壳 Pass2/Pass3**

```java
package org.elite.ir.infer;

import org.elite.eval.ELProgram;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;

/** Pass 2: operator and structural rules (filled in Task 3). */
public class Pass2 extends DefaultVisitor {

  private final InferenceContext ctx;

  public Pass2(InferenceContext ctx) {
    this.ctx = ctx;
  }

  public boolean run(ELProgram program) {
    ctx.resetProgress();
    for (ELNode node : program.getDefinitions()) {
      scan(node);
    }
    for (ELNode node : program.getExpressions()) {
      scan(node);
    }
    ctx.recomputeVarTypes();
    return ctx.takeProgress();
  }
}
```

(Pass3.java 同形,无 recomputeVarTypes,注释 "filled in Task 4"。)

- [ ] **Step 4: 重写 TypeInference 为编排器**

`src/main/java/org/elite/ir/TypeInference.java` 整体替换为:

```java
package org.elite.ir;

import javax.el.ELContext;
import org.elite.eval.ELProgram;
import org.elite.ir.infer.InferenceContext;
import org.elite.ir.infer.Pass1;
import org.elite.ir.infer.Pass2;
import org.elite.ir.infer.Pass3;
import org.elite.parser.ParseException;

/**
 * Static type inference over the ELite AST — orchestrator.
 *
 * Runs after {@code SymbolTableBuilder} and annotates every
 * {@code ELNode.inferredType} in a fixed sub-pass structure:
 * Pass 1 (annotations, constants, immutable variables, once), then
 * Pass 2 (operators) and Pass 3 (reflective complex nodes) repeated
 * until no progress or the iteration cap; remaining nulls become Any.
 * See docs/spec/2026-09-02-type-inference-subpass-design.md.
 */
public final class TypeInference {

  private static final int MAX_PASSES = 20;

  private TypeInference() {}

  public static void infer(ELContext elctx, ELProgram program)
    throws ParseException
  {
    InferenceContext ctx = new InferenceContext(elctx, program);
    ctx.registerEliteClasses(program);

    new Pass1(ctx).run(program);

    int passes = 0;
    boolean progress;
    do {
      progress = false;
      progress |= new Pass2(ctx).run(program);
      progress |= new Pass3(ctx).run(program);
    } while (progress && ++passes < MAX_PASSES);

    ctx.failIfErrors();
  }
}
```

注意:强制 Any 的收敛逻辑在 Task 5 加入;`registerEliteClasses` 返回 void(现有实现)。Pass1/2/3 的 run 各自 resetProgress 会互相覆盖——**修正**:run() 不 reset,由编排器在每轮开头 `ctx.resetProgress()`,run 只返回 `ctx.takeProgress()`;或 run 返回后编排器聚合。采用:Pass.run 不 reset、不 take,仅遍历;编排器:
```java
    do {
      ctx.resetProgress();
      new Pass2(ctx).run(program);
      new Pass3(ctx).run(program);
      progress = ctx.takeProgress();
    } while (progress && ++passes < MAX_PASSES);
```
(Pass1 的 run 同理改;把 Pass1/Pass2/Pass3 的 run 统一为"仅遍历,进度归编排器"。)

- [ ] **Step 5: 写 Pass1Test**

创建 `src/test/java/org/elite/ir/infer/Pass1Test.java`:

```java
package org.elite.ir.infer;

import javax.el.ELContext;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.ir.SymbolTableBuilder;
import org.elite.ir.TypeInference;
import org.elite.parser.ELNode;
import org.elite.parser.ParseException;
import org.elite.parser.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pass1Test {

  private static ELContext elctx;

  @BeforeAll
  static void createContext() {
    elctx = ELEngine.createELContext();
  }

  private static ELProgram parse(String script) {
    try {
      return new Parser(elctx, script).parse();
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void pass1TypesConstantsAndAnnotationsAndImmutable() throws Exception {
    ELProgram program = parse("define x = 42\ndefine y::Int = 1");
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);

    ELNode xDef = (ELNode)program.getExpressions().get(0);
    ELNode yDef = (ELNode)program.getExpressions().get(1);
    // Constants and annotated/immutable defines are typed in Pass 1.
    assertEquals("Int", xDef.inferredType.toString());
    assertEquals("Int", yDef.inferredType.toString());
  }

  @Test
  void pass2OnlyNodesStayNullAfterPass1() throws Exception {
    // x + 1 needs Pass 2 (operator); after the full pipeline it is typed,
    // but Pass 1 alone must leave it null. Run Pass 1 directly.
    ELProgram program = parse("define x = 42\nx + 1");
    SymbolTableBuilder.build(program);
    InferenceContext ctx = new InferenceContext(elctx, program);
    ctx.registerEliteClasses(program);
    new Pass1(ctx).run(program);

    ELNode add = (ELNode)program.getExpressions().get(1);
    assertNull(add.inferredType, "operator node must stay null after Pass 1 only");
  }
}
```

注意第一个测试走完整 pipeline(编排器暂含空 Pass2/Pass3 循环),此时 `x + 1` 类 Pass2 结点在完整 pipeline 后仍为 null——因为 Pass2 空壳,而强制 Any 尚未实现,所以第一个测试只断言 Pass1 能推导的结点;第二个测试单独跑 Pass1 验证 null 保持语义。

- [ ] **Step 6: 运行确认通过**

Run: `mvn -q test -Dtest=Pass1Test && mvn -q compile`
Expected: 2 个测试通过;compile 干净(旧 TypeInferenceTest 此时会挂——Pass2 尚未迁移,预期行为,Task 3 恢复;不要在本任务运行全量)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/elite/ir/infer/ src/main/java/org/elite/ir/TypeInference.java src/test/java/org/elite/ir/infer/
git commit -m "refactor(type): split inference into context + Pass1 with orchestrator

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: Pass2 迁移(现有规则归类)

**Files:**
- Modify: `src/main/java/org/elite/ir/infer/Pass2.java`(填充)、`Pass3.java`(移入现有 ACCESS/APPLY/NEW 规则)、`InferenceContext.java`(如有遗漏 helper 补齐)
- Test: `src/test/java/org/elite/ir/TypeInferenceTest.java`(44 个现有测试恢复全绿,断言不变)

**Interfaces:**
- Consumes: Task 2 的 Context/编排;现有 TypeInference.java 中尚未搬移的 visit 方法(本任务全部搬完)
- Produces: Pass2/Pass3 的完整规则集(Task 4 在 Pass3 上扩展)

- [ ] **Step 1: Pass2 迁入规则**

将 TypeInference.java 中以下 visit 方法**整体搬移**(逻辑逐字保留;凡读取子结点类型的消费点改用 `ctx.valueTypeOf` 替代 `ctx.typeOf`——当前 JavaConstructorType 尚未出现,替换为等价操作,行为不变):`visit(Composite/COMPOUND/LET/EXPR/METADATA/METASET/IDENT/NOT/EMPTY/INSTANCEOF/EQ/NE/IDEQ/IDNE/LT/LE/GT/GE/CMP/IN/OR/AND/CAT/ASSIGN/ASSIGNOP/LAMBDA/RETURN/FOREACH/WHILE/INFIX/ADD/SUB/MUL/DIV/REM/POW/BITOR/BITAND/XOR/SHL/SHR/USHR/NEG/POS/BITNOT/INC/DEC/FOR/MATCH/CASE/CONS/TUPLE/MAP/ARRAY/RANGE/COND/COALESCE)`(对应现文件 514-623、626-650、701-736、741 之前的 APPLY 部分除外、839-1147 各行),以及 `visitArithmetic`(915-919)。

另在 Pass2 增加**可变/未定型 DEFINE 规则**(原 visit(DEFINE) 的剩余分支,409-455 中 Pass1 未覆盖部分):
- 无标注、expr 非 LAMBDA/CLASSDEF、`!symbol.immutable`:初始值类型并入 assigns(现状行为)、类型 = varTypes 合并结果(未合并出则保持 null)
- 无标注、immutable 但 expr 在 Pass1 时未定型、本轮已定型 → 类型 = expr 类型
- 函数形 DEFINE(expr 是 LAMBDA)→ 类型 = LAMBDA 的类型(已有则取)
(逐字对照现 409-455 迁移,勿改语义。)

- [ ] **Step 2: Pass3 迁入现有复杂结点规则**

将 TypeInference.java 中 `visit(APPLY)`(741-785)、`visit(NEW)`(1051-1058)、`visit(ACCESS)`(1123-1134)整体搬入 Pass3(逻辑逐字保留,含 staticAccessReturnType/methodReturnType 依赖——这两个 helper 已在 Context)。`visitNode` 空覆盖(1148-1150)两个 Pass 都保留。

- [ ] **Step 3: 清空旧 TypeInference.java 的规则残留并编译**

删除 TypeInference.java 中已搬移的所有方法(该类只剩编排器)。Run: `mvn -q compile`。若报缺 import/成员,补齐对应 import。

- [ ] **Step 4: 现有测试全绿**

Run: `mvn -q test -Dtest=TypeInferenceTest`
Expected: BUILD SUCCESS,44/44(断言零改动;若个别用例因 Pass 顺序差异失败——例如依赖"同轮内先推导兄弟再推导父"的用例——检查该规则是否漏迁或是否应在 Pass2 内递归子结点后用 valueTypeOf,修复归位,不改测试断言)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/elite/ir/infer/ src/main/java/org/elite/ir/TypeInference.java
git commit -m "refactor(type): migrate operator rules into Pass2, complex nodes into Pass3

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: Pass3 反射推导 + 语义检查

**Files:**
- Modify: `src/main/java/org/elite/ir/infer/Pass3.java`(扩展)、`InferenceContext.java`(新增 resolveJavaMethod/resolveJavaField/resolveJavaConstructor helper)
- Test: `src/test/java/org/elite/ir/infer/Pass3Test.java`(新)

**Interfaces:**
- Consumes: Task 3 的 Pass3;`MethodResolver.getInstance(elctx).resolveMethod(Class,String)`/`resolveStaticMethod(Class,String)` → `MethodClosure`(org.elite.resolver / org.elite.eval.closure);`MethodClosure.getJavaMethod(int)` → `java.lang.reflect.Method`(arity 不匹配返回 null);`Class.getField/getDeclaredField/getConstructors`;`Constructor.isVarArgs()/getParameterCount()`;`ClassResolver.getInstance(elctx).resolveClass(String)`
- Produces:Pass3 最终规则(后续无扩展):IDENT-as-class、ACCESS 字段/方法引用、APPLY 方法/构造器调用、NEW 构造器解析、语义检查(EL_PROPERTY_NOT_FOUND/EL_ILLEGAL_ACCESS/EL_METHOD_NOT_FOUND/EL_FN_BAD_ARG_COUNT/EL_CONSTRUCTOR_NOT_FOUND + ErrorType)

- [ ] **Step 1: 写失败测试 Pass3Test**

创建 `src/test/java/org/elite/ir/infer/Pass3Test.java`(基架同 Pass1Test:parse/build/infer helpers;infer 用完整 `TypeInference.infer`):

```java
  @Test
  void systemOutPrintlnFullyStatic() throws Exception {
    ELProgram program = parse("System.out.println(\"hello\")");
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);

    ELNode apply = (ELNode)program.getExpressions().get(0);
    ELNode access = (ELNode)((ELNode.APPLY)apply).right;   // System.out.println
    ELNode inner  = (ELNode)((ELNode.ACCESS)access).right; // System.out
    ELNode system = (ELNode)((ELNode.ACCESS)inner).right;  // System

    assertEquals("JavaClass[System]", system.inferredType.toString());
    assertEquals("JavaField[PrintStream]", inner.inferredType.toString());
    assertEquals("JavaMethod[void PrintStream.println(String)]",
      access.inferredType.toString());
    assertEquals("void", apply.inferredType.toString());
  }

  @Test
  void arrayListConstructor() throws Exception {
    ELProgram program = parse("define p = new java.util.ArrayList()");
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);

    ELNode def = (ELNode)program.getExpressions().get(0);
    ELNode newExpr = (ELNode)((ELNode.DEFINE)def).expr;
    assertEquals("JavaConstructor[ArrayList()]", newExpr.inferredType.toString());
    // Value dereference: the variable is the instance type.
    assertEquals("JavaClass[ArrayList]", def.inferredType.toString());
  }

  @Test
  void missingFieldIsError() {
    ELProgram program = parse("System.noSuchField");
    SymbolTableBuilder.build(program);
    ParseException ex = assertThrows(ParseException.class,
      () -> TypeInference.infer(elctx, program));
    assertTrue(ex.getMessage().contains("Property not found"),
      "unexpected: " + ex.getMessage());
  }

  @Test
  void missingMethodIsError() {
    ELProgram program = parse("System.out.noSuchMethod(\"x\")");
    SymbolTableBuilder.build(program);
    ParseException ex = assertThrows(ParseException.class,
      () -> TypeInference.infer(elctx, program));
    assertTrue(ex.getMessage().contains("Method not found"),
      "unexpected: " + ex.getMessage());
  }

  @Test
  void missingConstructorIsError() {
    ELProgram program = parse("new java.util.ArrayList(\"nope\")");
    SymbolTableBuilder.build(program);
    ParseException ex = assertThrows(ParseException.class,
      () -> TypeInference.infer(elctx, program));
    assertTrue(ex.getMessage().contains("Constructor not found"),
      "unexpected: " + ex.getMessage());
  }
```

注意:错误消息断言可能受 locale 影响——参照 TypeInferenceTest 的 per-test Locale.ENGLISH try/finally 模式包裹;`EL_CONSTRUCTOR_NOT_FOUND` 的消息资源为 "Constructor not found on {0} type."(消息 key 现有),assert 用 contains("Constructor not found") 即可。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=Pass3Test`
Expected: 失败(类型为 Any / 不抛异常)

- [ ] **Step 3: InferenceContext 新增反射 helper**

在 InferenceContext 追加(方法整体搬移后新增):

```java
  /**
   * Resolves a named public Java method (static first, then instance)
   * on the given class with exact arity; null when absent or ambiguous.
   */
  public Method resolveJavaMethod(Class<?> cls, String name, int arity) {
    MethodClosure mc = MethodResolver.getInstance(elctx).resolveStaticMethod(cls, name);
    if (mc == null) {
      mc = MethodResolver.getInstance(elctx).resolveMethod(cls, name);
    }
    if (mc == null) return null;
    return mc.getJavaMethod(arity);
  }

  /** Resolves a public Java field by name; null when absent or non-public. */
  public Field resolveJavaField(Class<?> cls, String name) {
    try {
      Field f = cls.getField(name);
      return Modifier.isPublic(f.getModifiers()) ? f : null;
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  /** True when a declared field exists but is not public. */
  public boolean hasNonPublicField(Class<?> cls, String name) {
    try {
      cls.getDeclaredField(name);
      return cls.getField(name) == null ? false : false;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  /**
   * Resolves a public non-varargs constructor with exact arity; null when
   * absent or ambiguous (multiple candidates).
   */
  public Constructor<?> resolveJavaConstructor(Class<?> cls, int arity) {
    Constructor<?> match = null;
    for (Constructor<?> c : cls.getConstructors()) {
      if (!c.isVarArgs() && c.getParameterCount() == arity) {
        if (match != null) return null;   // ambiguous
        match = c;
      }
    }
    return match;
  }
```

(imports:`org.elite.eval.closure.MethodClosure`、`org.elite.resolver.MethodResolver`、`java.lang.reflect.Field/Method/Constructor/Modifier`。`hasNonPublicField` 改为正确实现:`try { cls.getDeclaredField(name); return true; } catch (NoSuchFieldException e) { return false; }` 且调用方先确认 public 解析失败。)

- [ ] **Step 4: Pass3 扩展规则**

Pass3 追加/扩展(前置条件不满足保持 null):

**(a) IDENT 按类名解析**(`visit(ELNode.IDENT)`):
```java
  @Override
  public void visit(ELNode.IDENT e) {
    if (e.symbol != null || ctx.hasType(e)) {
      return;   // variables/functions are Pass 2's concern
    }
    try {
      Class<?> cls = ClassResolver.getInstance(ctx.elctx()).resolveClass(e.id);
      ctx.setType(e, new JavaClassType(cls));
    } catch (ClassNotFoundException ex) {
      // stays null; forced Any at pipeline end
    }
  }
```

**(b) ACCESS 字段/方法引用**(扩展现有 visit(ELNode.ACCESS),在 Array/List 分支之后):
```java
    if (e.index instanceof ELNode.STRINGVAL key) {
      Type target = ctx.valueTypeOf(e.right);
      if (target instanceof JavaClassType jc) {
        String name = key.value;
        Field f = ctx.resolveJavaField(jc.javaClass(), name);
        if (f != null) {
          ctx.setType(e, new JavaFieldType(f));
          return;
        }
        if (ctx.hasNonPublicField(jc.javaClass(), name)) {
          ctx.reportError(e.pos, _T(EL_ILLEGAL_ACCESS, name, jc.javaClass().getName()));
          ctx.setType(e, ErrorType.ERROR);
          return;
        }
        // Method reference: System.out::println-style capture (mirrors
        // IRBuilder 847-864).
        MethodClosure mc = MethodResolver.getInstance(ctx.elctx())
          .resolveStaticMethod(jc.javaClass(), name);
        if (mc == null) {
          mc = MethodResolver.getInstance(ctx.elctx()).resolveMethod(jc.javaClass(), name);
        }
        if (mc != null && mc.getJavaMethod(1) == null && mc.getJavaMethod(0) == null) {
          // Not a zero/one-arg method either; treat as plain method ref.
        }
        if (mc != null) {
          // Record as JavaMethodType when arity is determinable (0 or 1 arg
          // is the method-reference convention); otherwise keep null.
          Method m = mc.getJavaMethod(0);
          if (m == null) m = mc.getJavaMethod(1);
          if (m != null) {
            ctx.setType(e, new JavaMethodType(m));
            return;
          }
        }
        // Static field/property not found.
        ctx.reportError(e.pos, _T(EL_PROPERTY_NOT_FOUND, name));
        ctx.setType(e, ErrorType.ERROR);
        return;
      }
      if (!(target instanceof AnyType) && ctx.hasType(e.right)) {
        // Instance field on a typed non-class receiver.
        // (v1: only public fields; no getter inference — omissions allowed.)
        if (target instanceof JavaFieldType jf) {
          target = ctx.valueType(jf);
        }
        if (target instanceof JavaClassType jc2) {
          Field f = ctx.resolveJavaField(jc2.javaClass(), name);
          if (f != null) {
            ctx.setType(e, new JavaFieldType(f));
            return;
          }
        }
      }
    }
```

注意:`JavaFieldType` 的实例字段场景(接收者本身是 JavaField 如 `System.out` 再 .println 时,上面的 ACCESS 走的是 method-ref 分支;本例里 `System.out.println` 的右操作数是 `System.out` ACCESS 节点,其类型 JavaFieldType——需在解析方法引用前先 `valueType` 解引用 JavaFieldType → JavaClassType,再走方法引用解析。把 (b) 中 target 解引用处统一:`target = ctx.valueType(target); if (target instanceof JavaClassType jc) {...}` 且 JavaFieldType 与 JavaConstructorType 都先 valueType。**实现时以此为准**,并保证 `System.out.println` 走查用例通过。)

**(c) APPLY 方法/构造器调用**(扩展):
```java
    Type target = ctx.valueTypeOf(e.right);
    if (target instanceof JavaMethodType jm) {
      ctx.setType(e, returnTypeOf(jm.method()));
      return;
    }
    if (target instanceof JavaClassType jc &&
        e.right instanceof ELNode.ACCESS acc &&
        acc.index instanceof ELNode.STRINGVAL key) {
      Method m = ctx.resolveJavaMethod(jc.javaClass(), key.value, e.args.length);
      if (m != null) {
        ctx.setType(e, returnTypeOf(m));
        return;
      }
      // Distinguish missing vs arity mismatch for the error message.
      MethodClosure mc = MethodResolver.getInstance(ctx.elctx())
        .resolveStaticMethod(jc.javaClass(), key.value);
      if (mc == null) {
        mc = MethodResolver.getInstance(ctx.elctx()).resolveMethod(jc.javaClass(), key.value);
      }
      if (mc != null) {
        ctx.reportError(e.pos, _T(EL_FN_BAD_ARG_COUNT, key.value, mc.arity(), e.args.length));
      } else {
        ctx.reportError(e.pos, _T(EL_METHOD_NOT_FOUND, key.value));
      }
      ctx.setType(e, ErrorType.ERROR);
      return;
    }
    if (target instanceof JavaClassType jc2 && e.right instanceof ELNode.IDENT id
        && id.symbol == null) {
      // Constructor call on a class name: File("x") form.
      Constructor<?> c = ctx.resolveJavaConstructor(jc2.javaClass(), e.args.length);
      if (c != null) {
        ctx.setType(e, new JavaConstructorType(c));
      } else {
        ctx.reportError(e.pos, _T(EL_CONSTRUCTOR_NOT_FOUND, jc2.javaClass().getName()));
        ctx.setType(e, ErrorType.ERROR);
      }
      return;
    }
```
(注意保留现有 Task 3 迁入的 APPLY 用户函数/FunctionType 分支在前;`returnTypeOf(Method)` 复用 context 中 `methodReturnType` 的语义:`void.class → PrimitiveType.of(void.class)`,数值/其余同现有。`EL_METHOD_NOT_FOUND` 现有消息 "Method ''{1}'' not found on {0} type." 参数序为 {0}=type? 查现有调用:IRBuilder 1183 用 `_T(EL_METHOD_NOT_FOUND, ...)` —— 以 `Resources.getText` 参数序为准,实现时核对 Messages.properties 该 key 的占位符个数,传参匹配,保证测试断言 contains 通过。)

**(d) NEW 构造器解析**(替换现有 visit(NEW) 的 Java 分支):
```java
  @Override
  public void visit(ELNode.NEW e) {
    super.visit(e);
    Type annotated = ctx.resolveAnnotationType(e.base);
    if (annotated instanceof ELiteClassType) {
      ctx.setType(e, annotated);
      return;
    }
    if (annotated instanceof JavaClassType jc) {
      Constructor<?> c = ctx.resolveJavaConstructor(jc.javaClass(), e.args.length);
      if (c != null) {
        ctx.setType(e, new JavaConstructorType(c));
        return;
      }
      ctx.reportError(e.pos, _T(EL_CONSTRUCTOR_NOT_FOUND, jc.javaClass().getName()));
      ctx.setType(e, ErrorType.ERROR);
    }
    // Unresolvable base stays null here (forced Any at pipeline end), or
    // ErrorType when the annotation resolution already reported.
    if (annotated == ErrorType.ERROR) {
      ctx.setType(e, ErrorType.ERROR);
    }
  }
```

**(e) 值解引用**:Pass2 已改 valueTypeOf;Pass1 的 immutable 分支同样用 `ctx.valueTypeOf(e.expr)` 与 `valueType`(回改 Task 2 的 Pass1 两处)。

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q test -Dtest=Pass3Test && mvn -q test -Dtest=TypeInferenceTest`
Expected: Pass3Test 5 个通过;TypeInferenceTest 44 个保持通过

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/elite/ir/infer/
git commit -m "feat(type): reflective Java member typing in Pass3 with semantic checks

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 编排收敛 + 强制 Any

**Files:**
- Modify: `src/main/java/org/elite/ir/TypeInference.java`(收敛循环 + 强制 Any)、`InferenceContext.java`(addAllNullsToAny visitor 或放编排器)
- Test: `src/test/java/org/elite/ir/infer/Pass3Test.java`(追加后置条件测试)

**Interfaces:**
- Consumes: Task 4;`DefaultVisitor`
- Produces: 最终编排语义(后续无扩展)

- [ ] **Step 1: 追加失败测试**

Pass3Test 追加:

```java
  @Test
  void everyNodeTypedAfterInference() throws Exception {
    // Unknown identifiers stay null through Pass 1-3 and must be forced
    // to Any so the postcondition holds.
    ELProgram program = parse("define x = noSuchVariable");
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);

    ELNode def = (ELNode)program.getExpressions().get(0);
    assertEquals("Any", def.inferredType.toString());
    ELNode id = (ELNode)((ELNode.DEFINE)def).expr;
    assertEquals("Any", id.inferredType.toString());
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=Pass3Test`
Expected: 新用例失败(null 或 AssertionError)

- [ ] **Step 3: 实现收敛与强制 Any**

TypeInference.java 替换为:

```java
package org.elite.ir;

import javax.el.ELContext;
import org.elite.eval.ELProgram;
import org.elite.ir.infer.InferenceContext;
import org.elite.ir.infer.Pass1;
import org.elite.ir.infer.Pass2;
import org.elite.ir.infer.Pass3;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.parser.ParseException;
import org.elite.type.AnyType;

/**
 * Static type inference over the ELite AST — orchestrator.
 *
 * Pass 1 (annotations, constants, immutable variables, once), then
 * Pass 2 (operators) and Pass 3 (reflective complex nodes) repeated
 * until no progress or the iteration cap; remaining nulls become Any so
 * every node carries a type afterwards.
 * See docs/spec/2026-09-02-type-inference-subpass-design.md.
 */
public final class TypeInference {

  /** Iteration cap: the reflective chain in Pass 3 can span a few levels. */
  private static final int MAX_PASSES = 50;

  private TypeInference() {}

  public static void infer(ELContext elctx, ELProgram program)
    throws ParseException
  {
    InferenceContext ctx = new InferenceContext(elctx, program);
    ctx.registerEliteClasses(program);

    Pass1 pass1 = new Pass1(ctx);
    Pass2 pass2 = new Pass2(ctx);
    Pass3 pass3 = new Pass3(ctx);

    pass1.run(program);

    int passes = 0;
    boolean progress;
    do {
      ctx.resetProgress();
      pass2.run(program);
      pass3.run(program);
      progress = ctx.takeProgress();
    } while (progress && ++passes < MAX_PASSES);

    // Postcondition: every node carries a type (unknown ones become Any).
    DefaultVisitor filler = new DefaultVisitor() {
      @Override
      public void visitNode(ELNode e) {
        if (e.inferredType == null) {
          e.inferredType = AnyType.ANY;
        }
      }
    };
    for (ELNode node : program.getDefinitions()) {
      filler.scan(node);
    }
    for (ELNode node : program.getExpressions()) {
      filler.scan(node);
    }

    ctx.failIfErrors();
  }
}
```

注意:`DefaultVisitor.visitNode` 只对无专属 visit 的结点触发——强制 Any 的遍历必须覆盖所有结点。改用可靠的遍历:创建内嵌 visitor 覆盖 `visitNode` 且**所有**常见结点经 DefaultVisitor 的递归会到达?不——DefaultVisitor 的叶子 visit(IDENT/NUMBER 等)不经过 visitNode。**实现时**:filler 直接遍历用 `ELNode.Visitor` 基类?不可(默认 assert false)。正确做法:filler 继承 DefaultVisitor 并覆盖叶子类别(或最简单——写一个 `fill(ELNode)` 递归:先 `if (node == null) return;` 再 `if (node.inferredType == null) node.inferredType = AnyType.ANY;` 然后用 ASTDumper.collectChildren?不可(私有)。**最终方案**:filler 覆盖 `visit(ELNode.IDENT)`、`visit(ELNode.NUMBER)` 等 Pass1 叶子 + `visitNode`,共约 10 个覆盖;或更简单——利用现有 `Pass1/2/3` 已覆盖所有结点类型的事实,写一个覆盖全部 `visit(X)` 的 visitor 不现实。**推荐最简实现**:在 `TypeInference` 内用 `DefaultVisitor` 覆盖 `visitNode` + `visitConstant`(NUMBER/BOOLEANVAL/CHARVAL/STRINGVAL/SYMBOL/CONST/NULL/NIL 都经 visitConstant)+ `visit(IDENT)` + `visit(BREAK)` + `visit(CONTINUE)`(DefaultVisitor 中无子结点的叶子),实现时以 DefaultVisitor.java 实际叶子清单为准(org.elite.parser.DefaultVisitor,叶子 visit 为空的几个)。若仍有漏网,补对应覆盖——目标是"全树覆盖"。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=Pass3Test && mvn -q test -Dtest=TypeInferenceTest && mvn -q test -Dtest=Pass1Test`
Expected: 全部通过(Pass3Test 6、TypeInferenceTest 44、Pass1Test 2)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/elite/ir/TypeInference.java src/test/java/org/elite/ir/infer/Pass3Test.java
git commit -m "feat(type): iterate Pass2/Pass3 to convergence and force Any for the rest

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 全量回归与手工验证

**Files:**
- 无代码改动(除非回归暴露问题)
- 手工验证走查

**Interfaces:** 无新增

- [ ] **Step 1: 全量测试**

Run: `mvn test`
Expected: BUILD SUCCESS,874+ 绿(新增 Pass1Test 2 + Pass3Test 6 后总数 ≈ 882)

- [ ] **Step 2: 手工验证 System.out.println 走查**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home
mvn -q clean package -DskipTests
BIN=target/elite-2.0.0-bin/elite-2.0.0/bin/xel
$BIN --dump-ast -e 'System.out.println("hello")'
```

Expected: dump 中 `System` IDENT 行含 ` :: JavaClass[System]`、内层 ACCESS 含 ` :: JavaField[PrintStream]`、外层 ACCESS 含 ` :: JavaMethod[void PrintStream.println(String)]`、APPLY 含 ` :: void`。

另跑坏用例(预期 stderr 报错、无 dump):
`$BIN --dump-ast -e 'System.noSuchField'` → "Property not found" 类消息。

- [ ] **Step 3: 文档同步**

`docs/spec/2026-09-02-type-inference-design.md` 顶部加一行指针:"推断遍的子 Pass 结构见 2026-09-02-type-inference-subpass-design.md";TypeInference 类注释已指向 subpass 规格(无需再改)。

- [ ] **Step 4: Commit(如有改动)**

```bash
git add -A && git commit -m "docs: link sub-pass design from type inference design

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## 计划自审记录

- 规格覆盖:三个反射类型(Task 1)、Context+Pass1+编排骨架(Task 2)、Pass2 迁移(Task 3)、Pass3 反射+语义检查含构造器(Task 4)、收敛/强制 Any(Task 5)、回归+手工+文档(Task 6)——规格全部条目有对应任务;valueType 解引用在 Task 2(定义)/Task 3(Pass2 消费点)/Task 4(Pass1 回改)落实;IRBuilder 不动、双份检查、44 测试等价均入 Global Constraints。
- 无占位符:新代码全部完整给出;迁移操作用精确方法名/行号清单;两处"实现时以 X 为准"的判定点(ACCESS 解引用次序、强制 Any 的 DefaultVisitor 叶子覆盖)均给出具体方案与验收依据,非占位。
- 一致性:`InferenceContext` 的 API 在 Task 2 定义,Task 3/4/5 按同签名消费;`valueType/valueTypeOf/hasType/takeProgress/resolveJavaMethod/resolveJavaField/resolveJavaConstructor` 命名全程一致;Pass.run 不 reset 的约定在 Task 2 注明并在 Task 5 编排器使用;测试数随任务递进(8/2/44/5+44/6+2+44/全量)。
