package org.elite.ir;

import static org.junit.jupiter.api.Assertions.*;

import org.elite.eval.ELEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.el.ELContext;

/**
 * Tests for the symbol table build pass (pure analysis, no IR emission).
 */
public class SymbolTableTest {

    private static ELContext elctx;

    @BeforeAll
    static void setup() {
        elctx = ELEngine.createELContext();
    }

    private String dumpTable(String source) {
        String result = IRPrinter.dumpSymbolTable(elctx, source);
        System.out.println("=== Source: " + source.replace("\n", "\\n"));
        System.out.println(result);
        return result;
    }

    @Test
    void simpleFunctionDefinitions() {
        String dump = dumpTable(
            "define f(x) => x + 1\n" +
            "define g(y) => f(y) * 2");
        assertTrue(dump.contains("fn:f"), "scope for f");
        assertTrue(dump.contains("fn:g"), "scope for g");
        assertTrue(dump.contains("x"), "param x");
        assertTrue(dump.contains("y"), "param y");
    }

    @Test
    void nestedFunctionWithCapture() {
        String dump = dumpTable(
            "define make_counter(n) {\n" +
            "  \\=> n++\n" +
            "}");
        assertTrue(dump.contains("make_counter"), "function name");
        assertTrue(dump.contains("n"), "param n");
        assertTrue(dump.contains("lambda"), "inner lambda scope");
    }

    @Test
    void shadowedVariableInIfBody() {
        String dump = dumpTable(
            "define foo(n) {\n" +
            "  if (n > 0) {\n" +
            "    define n = 0\n" +
            "  }\n" +
            "  n\n" +
            "}");
        // Outer n appears in fn:foo scope
        assertTrue(dump.contains("fn:foo"), "function scope");
    }

    @Test
    void patternMatchedFunction() {
        String dump = dumpTable(
            "define safe([], _, _) => true\n" +
            "define safe([x:xs], y, n) => x != y && x != y+n && safe(xs, y, n+1)");
        assertTrue(dump.contains("case"), "case scope");
    }

    @Test
    void repeatLoopCreatesScope() {
        String dump = dumpTable(
            "define test() {\n" +
            "  define n = 0\n" +
            "  repeat {\n" +
            "    n = n + 1\n" +
            "  } while (n < 10)\n" +
            "}");
        assertTrue(dump.contains("repeat"), "repeat scope");
    }

    @Test
    void whileLoopCreatesScope() {
        String dump = dumpTable(
            "define test() {\n" +
            "  define n = 0\n" +
            "  while (n < 10) {\n" +
            "    n = n + 1\n" +
            "  }\n" +
            "}");
        assertTrue(dump.contains("while"), "while scope");
    }

    @Test
    void multipleMatchCases() {
        // Pattern-matched function with three clauses
        String dump = dumpTable(
            "define describe(x) {\n" +
            "  | 1 => \"one\"\n" +
            "  | 2 => \"two\"\n" +
            "  | _ => \"other\"\n" +
            "}");
        assertTrue(dump.contains("case"), "case scope");
    }

    @Test
    void queensProgram() {
        String dump = dumpTable(
            "define queens(n) {\n" +
            "    define scan(0) => [[]]\n" +
            "         | scan(i) => [[q:qs] | qs <- scan(i-1), q <- [1..n], safe(qs, q, 1)]\n" +
            "    define safe([], _, _) => true\n" +
            "         | safe([x:xs], y, n) => x != y && x != y+n && x != y-n && safe(xs, y, n+1)\n" +
            "    scan(n)\n" +
            "}");
        // Should show the name shadowing: n in safe pattern vs n in queens param
        assertTrue(dump.contains("queens"), "queens function");
        assertTrue(dump.contains("case"), "case scope");
    }
}
