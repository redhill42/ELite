package org.elite.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.parser.ELNode;
import org.elite.parser.Parser;

class IRInterpreterTest {

    private static ELContext elctx;

    @BeforeAll
    static void setup() {
        elctx = ELEngine.createELContext();
    }

    private Object interpret(String expr) {
        ELNode node = Parser.parseExpression(elctx, expr);
        IRFunction fn = IRBuilder.compile(elctx, node);
        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), fn);
        return interp.execute(null);
    }

    private Object eval(String expr) {
        ELNode node = Parser.parseExpression(elctx, expr);
        return node.getValue(new EvaluationContext(elctx));
    }

    // ── Arithmetic ──
    @Test void intAddition() { assertEquals(30L, ((Number)interpret("10 + 20")).longValue()); }
    @Test void intMultiplication() { assertEquals(56L, ((Number)interpret("7 * 8")).longValue()); }
    @Test void doubleAddition() { assertEquals(5.86, ((Number)interpret("3.14 + 2.72")).doubleValue(), 0.001); }
    @Test void doubleDivision() { assertEquals(2.5, ((Number)interpret("20.0 / 8.0")).doubleValue(), 0.001); }
    @Test void precedence() { assertEquals(14L, ((Number)interpret("2 + 3 * 4")).longValue()); }
    @Test void remainder() { assertEquals(1L, ((Number)interpret("10 % 3")).longValue()); }
    @Test void negation() { assertEquals(-5, ((Number)interpret("-5")).intValue()); }
    @Test void complexArith() { assertEquals(eval("((10 + 5) * 3 - 8) / 2 + 100 * 4"), interpret("((10 + 5) * 3 - 8) / 2 + 100 * 4")); }

    // ── Comparisons ──
    @Test void intEquality() { assertEquals(true, interpret("100 == 100")); assertEquals(false, interpret("5 == 6")); }
    @Test void intInequality() { assertEquals(true, interpret("5 != 6")); assertEquals(false, interpret("100 != 100")); }
    @Test void intLessThan() { assertEquals(true, interpret("50 < 100")); assertEquals(false, interpret("100 < 50")); }
    @Test void intLessEqual() { assertEquals(true, interpret("100 <= 100")); }

    @Test void irInequalityMatchesAst() {
        // Verify IR != matches AST eval for various type combinations
        String[] exprs = {
            "5 != 6", "100 != 100",
            "3.14 != 3.14", "2.72 != 3.14",
            "\"a\" != \"a\"", "\"a\" != \"b\"",
            "true != true", "true != false"
        };
        for (String expr : exprs) {
            assertEquals(eval(expr), interpret(expr), "IR != result must match AST for: " + expr);
        }
    }

    // ── Booleans / Conditional / Concat / Null ──
    @Test void booleanTrue() { assertEquals(true, interpret("true")); }
    @Test void booleanFalse() { assertEquals(false, interpret("false")); }
    @Test void conditionalTrue() { assertEquals(100L, ((Number)interpret("true ? 100 : 200")).longValue()); }
    @Test void conditionalFalse() { assertEquals(200L, ((Number)interpret("false ? 100 : 200")).longValue()); }
    @Test void stringConcat() { assertEquals("helloworld", interpret("\"hello\" ~ \"world\"")); }
    @Test void nullLiteral() { assertNull(interpret("null")); }

    // ── AST match ──
    @Test void irMatchesAst() {
        String[] exprs = {"10 + 20","7 * 8","100.0 / 3.0","1 + 2 * 3 + 4 * 5",
            "100 == 100","((10 + 5) * 3 - 8) / 2 + 100 * 4",
            "true ? 100 : 200","\"hello\" ~ \" \" ~ \"world\""};
        for (String expr : exprs) {
            Object a=eval(expr), b=interpret(expr);
            if (a instanceof Number x && b instanceof Number y) assertEquals(x.doubleValue(),y.doubleValue(),0.0001);
            else assertEquals(a,b);
        }
    }

    // ── helpers ──

    private static IRFunction buildFn(String name, long[] code, int paramCount) {
        IRFunction irf = new IRFunction(name, paramCount);
        irf.populate(code, 2, new int[]{0}, new Object[]{0,1}, null, null);
        return irf;
    }

    private static boolean scanOp(IRFunction fn, int op) {
        for (int b=0; b<fn.blockCount(); b++) {
            InstructionView v = new InstructionView(fn.code(), fn.blockStart(b));
            int end = (b+1<fn.blockCount()) ? fn.blockStart(b+1) : fn.code().length;
            while (v.inBounds() && v.offset()<end) { if(v.opcode()==op) return true; v.advance(); }
        }
        return false;
    }
}
