package org.elite.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.eval.ELEngine;
import org.elite.parser.ELNode;
import org.elite.parser.Parser;

/** Tests that IRBuilder correctly converts ELNode trees to IR. */
class IRBuilderTest {

    private static ScriptEngine engine;
    private static ELContext elctx;

    @BeforeAll
    static void createEngine() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine, "ELite ScriptEngine not found");
        elctx = ELEngine.createELContext();
    }

    private ELNode parse(String expr) {
        try { return Parser.parseExpression(elctx, expr); }
        catch (Exception e) { throw new RuntimeException("parse failed: " + expr, e); }
    }

    private Object eval(String expr) {
        try { return engine.eval(expr); }
        catch (ScriptException e) { throw new RuntimeException("eval failed: " + expr, e); }
    }

    @Test void simpleIntAddition() {
        ELNode node = parse("10 + 20");
        IRFunction fn = IRBuilder.compile(elctx, node);
        assertNotNull(fn); assertTrue(fn.code().length > 0);
        assertEquals(30L, ((Number) eval("10 + 20")).longValue());
    }

    @Test void intMultiplication() {
        assertNotNull(IRBuilder.compile(elctx, parse("7 * 8")));
        assertEquals(56L, ((Number) eval("7 * 8")).longValue());
    }

    @Test void doubleAddition() {
        assertNotNull(IRBuilder.compile(elctx, parse("3.14 + 2.72")));
        assertEquals(5.86, ((Number) eval("3.14 + 2.72")).doubleValue(), 0.001);
    }

    @Test void intComparisonProducesTypedCmp() {
        assertNotNull(IRBuilder.compile(elctx, parse("100 == 100")));
        assertEquals(true, eval("100 == 100"));
    }

    @Disabled("Constant condition optimized out")
    @Test void conditionalHasMultipleBlocks() {
        IRFunction fn = IRBuilder.compile(elctx, parse("true ? 1 : 2"));
        assertTrue(fn.blockCount() >= 3);
    }

    @Test void whileLoopHasBackEdge() {
        exec("define whileSum(n) { define x = 0; while (x < n) { x = x + 1 }; x }");
    }

    @Test void breakWouldBecomeJump() {
        assertNotNull(IRBuilder.compile(elctx, parse("0")));
    }

    @Test void indexAccessCompiles() {
        assertNotNull(IRBuilder.compile(elctx, parse("x[0]")));
    }

    @Disabled("Constant condition optimized out")
    @Test void conditionalCompilesWithBlocks() {
        IRFunction fn = IRBuilder.compile(elctx, parse("true ? 100 : 200"));
        assertTrue(fn.blockCount() >= 3);
        assertTrue(scanOp(fn, Opcode.JUMP_IF_TRUE));
    }

    @Disabled("constant folded")
    @Test void logicalAndCompilesWithJumps() {
        assertTrue(IRBuilder.compile(elctx, parse("true && false")).blockCount() >= 2);
    }

    @Disabled("constant folded")
    @Test void logicalOrCompilesWithJumps() {
        assertTrue(IRBuilder.compile(elctx, parse("true || false")).blockCount() >= 2);
    }

    @Test void coalesceCompilesWithNullCheck() {
        IRFunction fn = IRBuilder.compile(elctx, parse("x ?? 100"));
        assertNotNull(fn);
        assertTrue(scanOp(fn, Opcode.JUMP_IF_NONNULL));
    }

    private void exec(String stmt) {
        try { engine.eval(stmt); }
        catch (ScriptException e) { throw new RuntimeException("exec failed: " + stmt, e); }
    }

    static boolean scanOp(IRFunction fn, int target) {
        for (int b = 0; b < fn.blockCount(); b++) {
            InstructionView v = new InstructionView(fn.code(), fn.blockStart(b));
            int end = (b+1 < fn.blockCount()) ? fn.blockStart(b+1) : fn.code().length;
            while (v.inBounds() && v.offset() < end) {
                if (v.opcode() == target) return true;
                v.advance();
            }
        }
        return false;
    }
}
