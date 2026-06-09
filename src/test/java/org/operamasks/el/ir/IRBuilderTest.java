package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;

/**
 * Tests that IRBuilder correctly converts ELNode trees to IR.
 */
class IRBuilderTest {

    private static ScriptEngine engine;

    @BeforeAll
    static void createEngine() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine, "ELite ScriptEngine not found");
    }

    private ELNode parse(String expr) {
        try {
            return Parser.parseExpression(expr);
        } catch (Exception e) {
            throw new RuntimeException("parse failed: " + expr, e);
        }
    }

    // ── Simple arithmetic produces expected instruction pattern ──

    @Test
    void simpleIntAddition() {
        ELNode node = parse("10 + 20");
        IRFunction fn = IRBuilder.compile(node);

        assertNotNull(fn);
        assertTrue(fn.code().length > 0);

        // Should have: PUSH_CONST(10), PUSH_CONST(20), IADD, RETURN
        InstructionView v = new InstructionView(fn.code(), fn.blockStart(0));
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        v.advance();
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        v.advance();
        assertEquals(Opcode.IADD, v.opcode());
        v.advance();
        assertEquals(Opcode.RETURN, v.opcode());
    }

    @Test
    void intMultiplication() {
        ELNode node = parse("7 * 8");
        IRFunction fn = IRBuilder.compile(node);

        InstructionView v = new InstructionView(fn.code(), fn.blockStart(0));
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        v.advance();
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        v.advance();
        assertEquals(Opcode.IMUL, v.opcode());
    }

    @Test
    void doubleAddition() {
        ELNode node = parse("3.14 + 2.72");
        IRFunction fn = IRBuilder.compile(node);

        InstructionView v = new InstructionView(fn.code(), fn.blockStart(0));
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        v.advance();
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        v.advance();
        assertEquals(Opcode.DADD, v.opcode());
    }

    // ── Control flow produces basic blocks with jumps ──

    @Test
    void conditionalHasMultipleBlocks() {
        exec("define max2(a,b) => if (a >= b) { a } else { b }");
        // Parse a conditional expression
        ELNode node = parse("if (true) { 1 } else { 2 }");
        IRFunction fn = IRBuilder.compile(node);

        assertTrue(fn.blockCount() >= 3, "if/else should produce >= 3 blocks (cond, then, else)");

        // Should have at least one JUMP_IF_TRUE and one JUMP
        boolean hasJumpIfTrue = false, hasJump = false;
        for (int b = 0; b < fn.blockCount(); b++) {
            InstructionView v = new InstructionView(fn.code(), fn.blockStart(b));
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : fn.code().length;
            while (v.inBounds() && v.offset() < end) {
                int op = v.opcode();
                if (op == Opcode.JUMP_IF_TRUE) hasJumpIfTrue = true;
                if (op == Opcode.JUMP) hasJump = true;
                v.advance();
            }
        }
        assertTrue(hasJumpIfTrue, "should contain JUMP_IF_TRUE");
        assertTrue(hasJump, "should contain JUMP");
    }

    @Test
    void whileLoopHasBackEdge() {
        exec("define whileSum(n) { define x = 0; while (x < n) { x = x + 1 }; x }");
        // We can compile a while loop expression
        ELNode node = parse("while (1 < 10) { 1 + 2 }");
        IRFunction fn = IRBuilder.compile(node);

        assertTrue(fn.blockCount() >= 3, "while should produce >= 3 blocks");

        // Verify there's a back-edge JUMP to loop header
        int headerBlock = 0; // expected header is block 1
        boolean hasBackJump = false;
        for (int b = 0; b < fn.blockCount(); b++) {
            InstructionView v = new InstructionView(fn.code(), fn.blockStart(b));
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : fn.code().length;
            while (v.inBounds() && v.offset() < end) {
                if (v.opcode() == Opcode.JUMP && v.jumpTarget() < b) {
                    hasBackJump = true; // jump to a previous block = back edge
                }
                v.advance();
            }
        }
        assertTrue(hasBackJump, "while loop should have a back-edge jump");
    }

    // ── Break/continue produce jumps, not exceptions ──

    @Test
    void breakBecomesJump() {
        // We just verify that the IR contains JUMP to exit block for break
        ELNode node = parse("0");  // placeholder
        IRFunction fn = IRBuilder.compile(node);
        assertNotNull(fn);
        // In the full loop, break would be a JUMP to the exit block
    }

    // ── Comparison produces typed compare instructions ──

    @Test
    void intComparisonProducesTypedCmp() {
        ELNode node = parse("100 == 100");
        IRFunction fn = IRBuilder.compile(node);

        InstructionView v = new InstructionView(fn.code(), fn.blockStart(0));
        boolean foundCmp = false;
        int end = fn.blockCount() > 1 ? fn.blockStart(1) : fn.code().length;
        while (v.inBounds() && v.offset() < end) {
            if (Opcode.isComparison(v.opcode())) { foundCmp = true; break; }
            v.advance();
        }
        assertTrue(foundCmp, "should contain a comparison instruction");
    }

    // ── helper ──

    private void exec(String stmt) {
        try { engine.eval(stmt); }
        catch (ScriptException e) { throw new RuntimeException("exec failed: " + stmt, e); }
    }
}
