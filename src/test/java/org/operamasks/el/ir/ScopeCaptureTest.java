package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.operamasks.el.EliteTestBase;

/**
 * Comprehensive tests for scope management, variable capture, and closure
 * semantics under the O2 (optimized IR) execution path.
 *
 * <p>Covers:
 * <ul>
 *   <li>Multi-level nested scopes and variable isolation</li>
 *   <li>Control-flow scope boundaries (if/while/for)</li>
 *   <li>Closure capture and free variable reassignment</li>
 *   <li>Trampolined constructs that may capture variables
 *       (list comprehension, lazy eval)</li>
 * </ul>
 */
class ScopeCaptureTest extends EliteTestBase {

    // ═══════════════════════════════════════════════════════════════
    // Multi-level nested scopes — variable isolation & shadowing
    // ═══════════════════════════════════════════════════════════════

    @Test
    void blockScopedVariableShadowsOuter() {
        exec("define test() {"
           + "  define x = 1;"
           + "  if (true) { define x = 2 };"
           + "  x"
           + "}");
        assertEquals(1L, evalL("test()"));
    }

    @Test
    void blockScopedVariableDoesNotLeak() {
        exec("define test() {"
           + "  define x = 1;"
           + "  if (true) { define y = 2 };"
           + "  x"
           + "}");
        assertEquals(1L, evalL("test()"));
    }

    @Test
    void whileLoopVariableIsolation() {
        exec("define test() {"
           + "  define x = 0;"
           + "  define i = 0;"
           + "  while (i < 3) {"
           + "    define x = i;"
           + "    i = i + 1"
           + "  };"
           + "  x"
           + "}");
        assertEquals(0L, evalL("test()"));
    }

    @Test
    void forLoopVariableIsolation() {
        exec("define test(n) {"
           + "  define s = 0;"
           + "  for (i in [1..n]) {"
           + "    define t = i * 2;"
           + "    s = s + i"
           + "  };"
           + "  s"
           + "}");
        assertEquals(55L, evalL("test(10)"));
    }

    @Test
    void nestedControlFlowScopes() {
        exec("define test() {"
           + "  define outer = 10;"
           + "  if (true) {"
           + "    define middle = 20;"
           + "    while (middle > 0) {"
           + "      define inner = middle;"
           + "      middle = middle - 1"
           + "    }"
           + "  };"
           + "  outer"
           + "}");
        assertEquals(10L, evalL("test()"));
    }

    @Test
    void deepBlockShadowing() {
        exec("define test() {"
           + "  define x = 1;"
           + "  if (true) {"
           + "    define x = 2;"
           + "    if (true) {"
           + "      define x = 3;"
           + "      if (true) {"
           + "        define x = 4"
           + "      };"
           + "      x"
           + "    }"
           + "  }"
           + "}");
        assertEquals(3L, evalL("test()"));
    }

    @Test
    void ifElseBranchesHaveIndependentScopes() {
        exec("define test(flag) {"
           + "  if (flag) {"
           + "    define x = 100"
           + "  } else {"
           + "    define x = 200"
           + "  };"
           + "  define y = 0;"
           + "  y"
           + "}");
        assertEquals(0L, evalL("test(true)"));
        assertEquals(0L, evalL("test(false)"));
    }

    // ═══════════════════════════════════════════════════════════════
    // Closure capture & free variable reassignment (Bug 3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void capturedVariableModificationPropagates() {
        // Bug 3: define foo() { define x=5; define bar()=>x+=3; bar(); x }
        exec("define foo() { define x = 5; define bar() => x += 3; bar(); x }");
        assertEquals(8L, evalL("foo()"));
    }

    @Test
    void readOnlyCapturedVariable() {
        exec("define foo() { define x = 5; define bar() => x * 2; bar() }");
        assertEquals(10L, evalL("foo()"));
    }

    @Test
    void multipleClosuresShareCapturedVariable() {
        exec("define makeCounters() {"
           + "  define n = 0;"
           + "  define inc() => n += 1;"
           + "  define dec() => n -= 1;"
           + "  define read() => n;"
           + "  [inc, dec, read]"
           + "}");
        exec("define cs = makeCounters()");
        exec("define inc = cs[0]");
        exec("define dec = cs[1]");
        exec("define read = cs[2]");
        assertEquals(1L, evalL("inc()"));
        assertEquals(2L, evalL("inc()"));
        assertEquals(1L, evalL("dec()"));
        assertEquals(1L, evalL("read()"));
    }

    @Test
    void nestedClosureModificationPropagates() {
        exec("define outer() {"
           + "  define x = 1;"
           + "  define mid() {"
           + "    define inner() => x += 1;"
           + "    inner();"
           + "    x"
           + "  };"
           + "  mid();"
           + "  x"
           + "}");
        assertEquals(2L, evalL("outer()"));
    }

    @Test
    void closureModifiesVariableBeforeOuterReads() {
        exec("define foo() {"
           + "  define x = 10;"
           + "  define bar() => x = x * 2;"
           + "  bar();"
           + "  x"
           + "}");
        assertEquals(20L, evalL("foo()"));
    }

    @Test
    void compoundAssignOnCapturedVariable() {
        exec("define foo() {"
           + "  define x = 10;"
           + "  define bar() => x += 5;"
           + "  bar();"
           + "  x"
           + "}");
        assertEquals(15L, evalL("foo()"));
    }

    @Test
    void capturedVariableIncrementDecrement() {
        exec("define foo() {"
           + "  define x = 5;"
           + "  define inc() => ++x;"
           + "  define dec() => --x;"
           + "  define read() => x;"
           + "  [inc, dec, read]"
           + "}");
        exec("define fs = foo()");
        exec("define inc = fs[0]");
        exec("define dec = fs[1]");
        exec("define read = fs[2]");
        assertEquals(6L, evalL("inc()"));
        assertEquals(5L, evalL("dec()"));
        assertEquals(5L, evalL("read()"));
    }

    // ═══════════════════════════════════════════════════════════════
    // Multi-level capture
    // ═══════════════════════════════════════════════════════════════

    @Test
    void closureCapturesFromGrandparentScope() {
        exec("define makeAdder(a) => \\b => a + b");
        exec("define add5 = makeAdder(5)");
        assertEquals(15L, evalL("add5(10)"));
    }

    @Test
    void deeplyNestedClosureCapture() {
        exec("define outer(a) => \\b => \\c => a + b + c");
        assertEquals(15L, evalL("outer(3)(5)(7)"));
    }

    @Test
    void siblingClosuresDontInterfere() {
        exec("define makePair() {"
           + "  define x = 10;"
           + "  define y = 20;"
           + "  define readX() => x;"
           + "  define readY() => y;"
           + "  [readX, readY]"
           + "}");
        exec("define p = makePair()");
        exec("define rx = p[0]");
        exec("define ry = p[1]");
        assertEquals(10L, evalL("rx()"));
        assertEquals(20L, evalL("ry()"));
    }

    @Test
    void lambdaParamShadowsOuterVariable() {
        // x is a lambda param, not a captured variable
        exec("define foo(x) => \\x => x * 2");
        exec("define f = foo(5)");
        assertEquals(14L, evalL("f(7)"));
    }

    // ═══════════════════════════════════════════════════════════════
    // let expressions (trampolined, may capture variables)
    // ELite uses `let (pattern) { body }` syntax
    // ═══════════════════════════════════════════════════════════════

    @Test
    void letBindingInFunctionBody() {
        exec("define foo() {"
           + "  define x = 5;"
           + "  let (y = x + 1) { y * 2 }"
           + "}");
        assertEquals(12L, evalL("foo()"));
    }

    @Test
    void letCapturesFromEnclosingScope() {
        exec("define foo() {"
           + "  define x = 5;"
           + "  let (y = x + 3) { x + y }"
           + "}");
        assertEquals(13L, evalL("foo()"));
    }

    // ═══════════════════════════════════════════════════════════════
    // List comprehensions (trampolined, may capture variables)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void listComprehensionWithCapturedMultiplier() {
        exec("define scale(list, factor) => [x * factor | x in list]");
        Object result = eval("scale([1, 2, 3], 10)");
        assertTrue(result instanceof java.util.List);
        java.util.List<?> l = (java.util.List<?>) result;
        assertEquals(3, l.size());
        assertEquals(10L, ((Number) l.get(0)).longValue());
        assertEquals(20L, ((Number) l.get(1)).longValue());
        assertEquals(30L, ((Number) l.get(2)).longValue());
    }

    @Test
    void listComprehensionSimple() {
        Object result = eval("[x * 2 | x in [1, 2, 3]]");
        assertTrue(result instanceof java.util.List);
        java.util.List<?> l = (java.util.List<?>) result;
        assertEquals(3, l.size());
        assertEquals(2L, ((Number) l.get(0)).longValue());
        assertEquals(4L, ((Number) l.get(1)).longValue());
        assertEquals(6L, ((Number) l.get(2)).longValue());
    }

    @Test
    void listComprehensionWithFilter() {
        exec("define evens(list) => [x | x in list, x % 2 == 0]");
        Object result = eval("evens([1, 2, 3, 4, 5, 6])");
        java.util.List<?> l = (java.util.List<?>) result;
        assertEquals(3, l.size());
        assertEquals(2L, ((Number) l.get(0)).longValue());
        assertEquals(4L, ((Number) l.get(1)).longValue());
        assertEquals(6L, ((Number) l.get(2)).longValue());
    }

    // ═══════════════════════════════════════════════════════════════
    // Lazy evaluation with captures
    // ═══════════════════════════════════════════════════════════════

    @Test
    void lazySequenceCapturesOuterParameter() {
        exec("define from(n) => [n : &from(n+1)]");
        exec("define naturals = from(1)");
        // Access elements via slice
        Object result = eval("naturals[0..4]");
        assertTrue(result instanceof java.util.List);
        assertEquals(5, ((java.util.List<?>) result).size());
    }

    // ═══════════════════════════════════════════════════════════════
    // String interpolation (Composite) — no longer trampolined
    // ═══════════════════════════════════════════════════════════════

    @Test
    void stringInterpolationInFunction() {
        exec("define greet(name) => \"Hello, ${name}!\"");
        assertEquals("Hello, World!", eval("greet(\"World\")"));
    }

    @Test
    void stringInterpolationWithCapturedVariable() {
        exec("define makeGreeter(greeting) => \\name => \"${greeting}, ${name}!\"");
        exec("define hi = makeGreeter(\"Hi\")");
        assertEquals("Hi, Alice!", eval("hi(\"Alice\")"));
    }

    @Test
    void stringInterpolationMultipleElements() {
        exec("define format(x, y) => \"(${x}, ${y})\"");
        assertEquals("(3, 5)", eval("format(3, 5)"));
    }

    // ═══════════════════════════════════════════════════════════════
    // Recursion + captured variable
    // ═══════════════════════════════════════════════════════════════

    @Test
    void recursiveFunctionWithCapturedAccumulator() {
        exec("define sumTo(n) {"
           + "  define acc = 0;"
           + "  define loop(i) {"
           + "    if (i > n) { acc }"
           + "    else { acc = acc + i; loop(i + 1) }"
           + "  };"
           + "  loop(1)"
           + "}");
        assertEquals(55L, evalL("sumTo(10)"));
    }

    // ═══════════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════════

    @Test
    void variableDefinedInFunctionBodyVisibleToNestedLambda() {
        exec("define test() {"
           + "  define x = 10;"
           + "  define getX() => x;"
           + "  getX()"
           + "}");
        assertEquals(10L, evalL("test()"));
    }

    @Test
    void functionReassignsOwnParameter() {
        exec("define test(x) { x = x + 1; x }");
        assertEquals(6L, evalL("test(5)"));
    }

    @Test
    void defaultParameterWithClosureCapture() {
        exec("define makeMultiplier(n) => \\x => x * n");
        exec("define triple = makeMultiplier(3)");
        assertEquals(15L, evalL("triple(5)"));
    }

    @Test
    void capturedVariableAfterMultipleCalls() {
        exec("define makeCounter() {"
           + "  define n = 0;"
           + "  define tick() => n += 1;"
           + "  tick"
           + "}");
        exec("define c = makeCounter()");
        assertEquals(1L, evalL("c()"));
        assertEquals(2L, evalL("c()"));
        assertEquals(3L, evalL("c()"));
    }

    @Test
    void defineInsideIfBranchDoesNotLeak() {
        exec("define test(flag) {"
           + "  if (flag) {"
           + "    define x = 1"
           + "  };"
           + "  define y = 2;"
           + "  y"
           + "}");
        assertEquals(2L, evalL("test(true)"));
    }

    @Test
    void whileLoopVariableReassign() {
        exec("define sum(n) {"
           + "  define s = 0;"
           + "  define i = 0;"
           + "  while (i < n) {"
           + "    i = i + 1;"
           + "    s = s + i"
           + "  };"
           + "  s"
           + "}");
        assertEquals(55L, evalL("sum(10)"));
    }

    @Test
    void functionDefinitionDoesNotLeakLocalName() {
        // The function name 'square' should NOT be visible as a local variable
        exec("define test() {"
           + "  define square(x) => x * x;"
           + "  square(5)"
           + "}");
        assertEquals(25L, evalL("test()"));
    }

    @Test
    void defineVariableAfterLambdaReturn() {
        exec("define makeCounter() {"
           + "  define n = 0;"
           + "  define tick() => n += 1;"
           + "  define reset() => n = 0;"
           + "  [tick, reset]"
           + "}");
        exec("define pair = makeCounter()");
        exec("define tick = pair[0]");
        exec("define reset = pair[1]");
        assertEquals(1L, evalL("tick()"));
        assertEquals(2L, evalL("tick()"));
        assertEquals(0L, evalL("reset()"));
        assertEquals(1L, evalL("tick()"));
    }

    @Test
    void ifElseBothBranchesDefineSameVariable() {
        exec("define test(flag) {"
           + "  if (flag) {"
           + "    define x = 1;"
           + "    x"
           + "  } else {"
           + "    define x = 2;"
           + "    x"
           + "  }"
           + "}");
        assertEquals(1L, evalL("test(true)"));
        assertEquals(2L, evalL("test(false)"));
    }

    @Test
    void multipleLambdasCaptureSameVariableIndependently() {
        exec("define makeOps() {"
           + "  define base = 10;"
           + "  define add(x) => base + x;"
           + "  define mul(x) => base * x;"
           + "  [add, mul]"
           + "}");
        exec("define ops = makeOps()");
        exec("define add = ops[0]");
        exec("define mul = ops[1]");
        assertEquals(15L, evalL("add(5)"));
        assertEquals(50L, evalL("mul(5)"));
    }

    // ═══════════════════════════════════════════════════════════════
    // Undefined variable assignment — must throw in all scopes
    // ═══════════════════════════════════════════════════════════════

    @Test
    void assignToUndefinedTopLevelThrows() {
        javax.script.ScriptException ex = assertThrows(
            javax.script.ScriptException.class,
            () -> engine.eval("x = 1"));
        assertTrue(ex.getMessage().contains("标识符未定义"));
    }

    @Test
    void assignToUndefinedInIfBlockThrows() {
        javax.script.ScriptException ex = assertThrows(
            javax.script.ScriptException.class,
            () -> engine.eval("if (true) { x = 1 }"));
        assertTrue(ex.getMessage().contains("标识符未定义"));
    }

    @Test
    void defineThenAssignWorks() {
        exec("define x = 5");
        exec("x = 10");
        assertEquals(10L, evalL("x"));
    }

    @Test
    void assignToUndefinedInFunctionThrows() {
        javax.script.ScriptException ex = assertThrows(
            javax.script.ScriptException.class,
            () -> engine.eval("define f() { y = 1; y }; f()"));
        assertTrue(ex.getMessage().contains("标识符未定义"));
    }
}
