package org.elite.eval;

import org.elite.EliteTestBase;
import org.junit.jupiter.api.Test;

/**
 * Tests for expando method definition: {@code define String.shout() => ...}
 * dynamically attaches an extension method to a Java class or an ELite
 * class at runtime. The receiver is bound to an implicit {@code this}
 * parameter.
 *
 * <p>Cross-eval execution is no longer supported, so each test evaluates
 * one complete script and uses the ELite {@code assert} statement for
 * the assertions (same style as the new cases in SampleScriptTest).
 */
class ExpandoTest extends EliteTestBase {

    // ---- Basic dispatch on Java classes ---------------------------------

    @Test
    void basicExpandoOnJavaClass() throws Exception {
        engine.eval(
            """
            define String.shout() => toUpperCase().concat("!")
            assert "hello".shout() == "HELLO!"
            """);
    }

    @Test
    void expandoWithFullClassName() throws Exception {
        engine.eval(
          """
          define java.lang.String.shout() => toUpperCase().concat("!")
          assert "hello".shout() == "HELLO!"
          """);
    }

    @Test
    void expandoWithExplicitThis() throws Exception {
        engine.eval(
            """
            define String.shout() => this.toUpperCase().concat("!")
            assert "hello".shout() == "HELLO!"
            """);
    }

    @Test
    void expandoWithParameters() throws Exception {
        engine.eval(
            """
            define Integer.square() => this * this
            assert 6.square() == 36

            define String.repeat(n) => (0 < n) ? this ~ repeat(n - 1) : ""
            assert "ab".repeat(3) == "ababab"
            """);
    }

    @Test
    void expandoWithBlockBody() throws Exception {
        engine.eval(
            """
            define String.shout() {
              let s = toUpperCase()
              s.concat("!")
            }
            assert "hello".shout() == "HELLO!"
            """);
    }

    @Test
    void multipleExpandoMethodsOnSameClass() throws Exception {
        engine.eval(
            """
            define Integer.square() => this * this
            define Integer.cube() => this * this * this
            assert 3.square() == 9
            assert 3.cube() == 27
            """);
    }

    @Test
    void expandoWontOverridesBuiltinMethod() throws Exception {
        engine.eval(
            """
            define String.toUpperCase() => "overridden"
            assert "abc".toUpperCase() == "ABC"
            """);
    }

    @Test
    void redefinitionBeforeCall() throws Exception {
        engine.eval(
            """
            define String.shout() => "first"
            define String.shout() => "second"
            assert "x".shout() == "second"
            """);
    }

    @Test
    void redefinitionAfterCall() throws Exception {
        engine.eval(
            """
            define String.shout() => "first"
            assert "x".shout() == "first"
            define String.shout() => "second"
            assert "x".shout() == "second"
            """);
    }

    @Test
    void otherMethodsStillAttachableAfterCall() throws Exception {
        engine.eval(
            """
            define String.shout() => "first"
            assert "x".shout() == "first"
            // the freeze is per method — other methods can still be attached
            define String.whisper() => "psst"
            assert "x".whisper() == "psst"
            """);
    }

    @Test
    void expandoScopedToTargetClass() {
        assertEvalThrows(
            """
            define String.shout() => "!"
            1.shout()
            """);
    }

    @Test
    void expandoNotCallableStatically() {
        assertEvalThrows(
            """
            define String.shout() => "!"
            String.shout()
            """);
    }

    @Test
    void expandoUsedInsideFunction() throws Exception {
        engine.eval(
            """
            define String.shout() => toUpperCase().concat("!")
            define f(x) => x.shout()
            assert f("hello") == "HELLO!"
            """);
    }

    // ---- Arguments -------------------------------------------------------

    @Test
    void namedArguments() throws Exception {
        engine.eval(
            """
            define String.f(a, b) => a + b
            assert "x".f(a=1, b=2) == 3
            """);
    }

    @Test
    void defaultArguments() throws Exception {
        engine.eval(
            """
            define String.f(a, b=10) => a + b
            assert "x".f(1) == 11
            assert "x".f(1, 2) == 3
            """);
    }

    @Test
    void varargsExpando() throws Exception {
        engine.eval(
            """
            define String.count(args...) => 1 + args.length
            assert "x".count() == 1
            assert "x".count(1, 2, 3) == 4
            """);
    }

    @Test
    void quotedMethodName() throws Exception {
        engine.eval(
            """
            define String.'shout'() => "quoted"
            assert "x".shout() == "quoted"
            """);
    }

    // ---- ELite classes ---------------------------------------------------

    @Test
    void expandoOnEliteClass() throws Exception {
        engine.eval(
            """
            class Foo(a, b) {}
            define Foo.sum() => a + b
            assert Foo(1, 2).sum() == 3
            """);
    }

    @Test
    void eliteMemberTakesPrecedenceOverExpando() throws Exception {
        engine.eval(
            """
            // NOTE: unlike Java classes, where an expando overrides the
            // builtin method, an ELite class's own member wins over the
            // expando method.
            class Foo { define m() => 1 }
            define Foo.m() => 2
            assert Foo().m() == 1
            """);
    }

    @Test
    void expandoOnUndefinedClassFails() {
        // the attach call executes at runtime, so the class must exist
        assertEvalThrows("define Foo.bar() => 42");
    }

    @Test
    void expandoInsideBlockRejected() {
        // expando definitions are only recognized at the top level
        assertEvalThrows("{ define String.f() => 1 }");
    }

    // ---- Inheritance -----------------------------------------------------

    @Test
    void expandoInheritedBySubclass() throws Exception {
        engine.eval(
            """
            class A {}
            class B extends A {}
            define A.describe() => "a"
            assert B().describe() == "a"
            """);
    }

    @Test
    void subclassExpandoWinsOverSuperclass() throws Exception {
        engine.eval(
            """
            class A {}
            class B extends A {}
            define A.describe() => "a"
            define B.describe() => "b"
            assert B().describe() == "b"
            assert A().describe() == "a"
            """);
    }

    // ---- Modifiers and patterns ------------------------------------------

    @Test
    void publicModifierAllowed() throws Exception {
        engine.eval(
            """
            define public String.shout() => toUpperCase()
            assert "hi".shout() == "HI"
            """);
    }

    @Test
    void staticModifierRejected() {
        // 'static' is not a valid modifier for an expando method
        assertEvalThrows("define static String.f() => 1");
    }

    @Test
    void patternParameterExpando() throws Exception {
        engine.eval(
            """
            define String.f([a, b]) => a + b
            assert "x".f([1, 2]) == 3
            """);
    }

    @Test
    void multiplePatternParameterExpando() throws Exception {
        engine.eval(
            """
            define String.f([a, b]) => a + b
                        | f((a, b)) => a + b
            assert "x".f([1, 2]) == 3
            assert "x".f((1, 2)) == 3
            """);
    }

    @Test
    void curriedExpando() throws Exception {
        engine.eval(
            """
            define Integer.f(a)(b) => this + a + b
            assert 1.f(2)(3) == 6
            """);
    }

    @Test
    void expandoClosure() throws Exception {
        engine.eval(
            """
            define String.shout() => toUpperCase().concat("!");
            define trace(f) => "trace(${f()})";
            assert trace("hello".shout) == "trace(HELLO!)"
            """);
    }
}
