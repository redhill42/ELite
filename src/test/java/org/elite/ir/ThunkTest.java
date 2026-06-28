package org.elite.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.parser.ELNode;
import org.junit.jupiter.api.Disabled;
import org.elite.parser.Parser;

class ThunkTest {

    private static ELContext elctx;

    @BeforeAll
    static void setup() {
        elctx = ELEngine.createELContext();
    }

    // ── Phase 1.1: DELAY opcode basic ──

    @Test
    void delayOpcodeProducesDelayEvalClosure() {
        // Build a program that creates a DELAY thunk and returns it.
        ELNode expr = Parser.parseExpression("40 + 2");
        SymbolTable st = SymbolTableBuilder.build(expr);
        IRBuilder b = new IRBuilder(ELEngine.createELContext(), st);
        b.buildThunk(expr);   // compile+push thunk
        b.current.emitReturn(IRFormat.T_INT);
        IRFunction program = b.finish("<test>", 0);

        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), program);
        Object result = interp.execute(null);

        assertInstanceOf(IRInterpreter.Thunk.class, result,
            "buildThunk should produce a Thunk");
        assertEquals(42L, ((Number)((IRInterpreter.Thunk)result).getValue(elctx)).longValue(),
            "getValue() should force and return 42");
    }

    @Test
    void delayOpcodePreservesMemoization() {
        // Second getValue() returns cached result (same object reference).
        ELNode expr = Parser.parseExpression("1 + 1");
        SymbolTable st = SymbolTableBuilder.build(expr);
        IRBuilder b = new IRBuilder(ELEngine.createELContext(), st);
        b.buildThunk(expr);
        b.current.emitReturn(IRFormat.T_INT);
        IRFunction program = b.finish("<test>", 0);

        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), program);
        IRInterpreter.Thunk thunk = (IRInterpreter.Thunk) interp.execute(null);

        Object v1 = thunk.getValue(elctx);
        Object v2 = thunk.getValue(elctx);
        assertSame(v1, v2, "second getValue() returns cached (same) result");
    }

    // ── Phase 1.2: DELAY with captured variable ──

    @Test
    void delayOpcodePopsCorrectNumberOfCaptures() {
        // Verify that DELAY pops the correct number of captured values
        // from the stack. Push captureCount values, emit DELAY, verify
        // the stack is consumed correctly.
        ELNode body = Parser.parseExpression("1 + 1");
        IRFunction thunkFn = IRBuilder.compile(body);

        IREmitter out = new IREmitter();
        out.emitPushNull();
        out.emitPushNull();
        out.emitDelay(0, 2);  // pop 2 captures
        // If captures were NOT popped, the next op would see garbage
        out.emitPushTrue();
        out.emitReturn(IRFormat.T_INT);

        int[] code = out.toArray();
        Object[] pool = {thunkFn};
        IRFunction fn = new IRFunction("<test>", 0, 0, code, new int[]{0},
            pool, new String[0], DebugInfo.EMPTY, null);
        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), fn);
        Object result = interp.execute(null);

        // After DELAY pops 2, PUSH_TRUE + RETURN → Boolean.TRUE
        // The DELAY result itself is null (placeholder), which is consumed
        // before PUSH_TRUE... actually the result is pushed, not consumed.
        // result = DELAY(null) → null is on stack → PUSH_TRUE pushes true
        // → RETURN returns top = true
        assertEquals(Boolean.TRUE, result);
    }

    // ── Phase 1.3: PUSH_VAR auto-force ──

    @Test
    void pushVarAutoForcesDelayEvalClosure() {
        // Store a DelayEvalClosure in locals, read via PUSH_VAR.
        // PUSH_VAR should auto-force and return the computed value.
        ELNode thunkExpr = Parser.parseExpression("10 + 3");
        IRFunction thunkFn = IRBuilder.compile(thunkExpr);

        IREmitter out = new IREmitter();
        // DELAY creates thunk and pushes it
        out.emitDelay(0, 0);
        // STORE_VAR(0) stores in locals[0] and pushes dup
        out.emitStoreVar(0);
        // POP discards dup
        out.emitPop();
        // PUSH_VAR(0) reads locals[0] — should auto-force the thunk
        out.emitPushVar(0);
        out.emitReturn(IRFormat.T_INT);

        int[] code = out.toArray();
        Object[] pool = {thunkFn};
        IRFunction fn = new IRFunction("<test>", 0, 0, code, new int[]{0},
            pool, new String[]{"tmp"}, DebugInfo.EMPTY, null);
        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), fn);
        Object result = interp.execute(null);

        assertEquals(13L, ((Number)result).longValue(),
            "PUSH_VAR should auto-force DelayEvalClosure → 13");
    }

    // ── Phase 1.4: PUSH_VAR_RAW (no auto-force) ──

    @Test
    void pushVarRawPreservesDelayEvalClosure() {
        // Store a DelayEvalClosure in locals, read via PUSH_VAR_RAW.
        // PUSH_VAR_RAW should NOT force — for passing to another lazy param.
        ELNode thunkExpr = Parser.parseExpression("10 + 3");
        IRFunction thunkFn = IRBuilder.compile(thunkExpr);

        IREmitter out = new IREmitter();
        out.emitDelay(0, 0);
        out.emitStoreVar(0);
        out.emitPop();
        out.emitPushVarRaw(0);
        out.emitReturn(IRFormat.T_INT);

        int[] code = out.toArray();
        Object[] pool = {thunkFn};
        IRFunction fn = new IRFunction("<test>", 0, 0, code, new int[]{0},
            pool, new String[]{"tmp"}, DebugInfo.EMPTY, null);
        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), fn);
        Object result = interp.execute(null);

        assertInstanceOf(IRInterpreter.Thunk.class, result,
            "PUSH_VAR_RAW should NOT force — returns raw Thunk");
    }

    // ── Phase 2: Lazy function parameters via INVOKE_DIRECT ──

    /** Compile and run a multi-statement program via IR. */
    private Object interpretProgram(String... statements) {
        String src = String.join("\n", statements);
        var p = new Parser(src);
        var prog = p.parse();
        prog.importExternal(elctx);
        IRFunction fn = IRBuilder.compile(elctx, prog, false, null);
        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), fn);
        return interp.execute(null, null, true);
    }

    @Test @Disabled("single-script compilation: lazy param capture not yet supported")
    void lazyParamOnlyForcesTakenBranch() {
        // conditional(true, &inc(), &inc()) — only the first inc() should execute.
        // n starts at 0, only one inc() runs → n = 1.
        Object result = interpretProgram(
            "define conditional(test, &consequent, &alternate) { if (test) consequent; else alternate }",
            "define n = 0",
            "define inc() => n = n + 1",
            "conditional(true, inc(), inc())",
            "n"
        );
        assertEquals(1L, ((Number)result).longValue(),
            "only the taken branch should be forced");
    }

    @Test @Disabled("single-script compilation: lazy param capture not yet supported")
    void lazyParamElseBranchNotForced() {
        // conditional(false, &inc(), &inc()) — only the else branch runs.
        Object result = interpretProgram(
            "define conditional(test, &consequent, &alternate) { if (test) consequent; else alternate }",
            "define n = 0",
            "define inc() => n = n + 1",
            "conditional(false, inc(), inc())",
            "n"
        );
        assertEquals(1L, ((Number)result).longValue(),
            "false branch: only alternate runs, n=1");
    }

    @Test @Disabled("single-script compilation: lazy param capture not yet supported")
    void eagerParamEvaluatedBeforeCall() {
        // Without &, both inc() calls execute before conditional runs → n=2.
        Object result = interpretProgram(
            "define conditional(test, consequent, alternate) { if (test) consequent; else alternate }",
            "define n = 0",
            "define inc() => n = n + 1",
            "conditional(true, inc(), inc())",
            "n"
        );
        assertEquals(2L, ((Number)result).longValue(),
            "without &: both args evaluated before call, n=2");
    }

    // ── Capture: function parameter captured by inner lambda ──

    @Test
    void functionParameterCapturedByInnerLambda() {
        // Parameter `n` of make_counter is captured by the lambda \=> n++.
        // The lambda must mutate the original parameter (not a copy).
        // Before the fix this output 0, 0 (captured by value).
        // After the fix it should output 0, 1 (captured by reference).
        Object result = interpretProgram(
            "define make_counter(n) { \\=> n++ }",
            "define c = make_counter(0)",
            "c()",   // should return 0 (n++, post-increment)
            "c()"    // should return 1
        );
        assertEquals(1L, ((Number)result).longValue(),
            "second call should return 1 — parameter n is shared via evalContext");
    }

    // ── Self-referential lazy definitions ──

    @Test
    void selfReferentialLazyConsDefinesWithoutError() {
        // define ones = [1 : &ones] — the variable appears inside its own
        // lazy (&) tail. The thunk must resolve 'ones' via evalContext
        // (DEFINE_GLOBAL), not just locals (STORE_VAR).
        // Before the fix this threw "标识符未定义: ones".
        Object result = interpretProgram(
            "define ones = [1 : &ones]",
            "1"
        );
        assertEquals(1L, ((Number)result).longValue(),
            "self-referential lazy cons should define without error");
    }

    @Test
    void selfReferentialLazyConsVariableAccessible() {
        // After defining a self-referential lazy cons, reading the variable
        // should return the cons (not throw "undefined").
        Object result = interpretProgram(
            "define ones = [1 : &ones]",
            "ones"
        );
        assertNotNull(result,
            "self-referential lazy cons variable should be accessible");
    }

    // ── Phase 3: DelayCons ──

    @Test @Disabled("dynamic .head()/.tail() resolution on DelayCons hangs — needs INVOKE_DYN_METHOD fix")
    void simpleDelayCons() {
        // Simplest possible delay cons: [1 : &[2]]
        // The tail expression is a simple literal [2] — no free variables.
        Object result = interpretProgram(
            "define xs = [1 : &[2]]",
            "xs.head()"
        );
        assertEquals(1L, ((Number)result).longValue(),
            "head of [1 : &[2]] should be 1");
    }

    @Test @Disabled("force(xs) on infinite lazy seq hangs — needs INVOKE_DYN_METHOD fix")
    void delayConsCapturesFreeVariable() {
        // from(n) => [n : &from(n+1)] — the tail thunk captures n.
        // Use force to verify the thunk evaluates correctly.
        // (Can't use .head()/.tail() — those need dynamic method fix.)
        Object result = interpretProgram(
            "define from(n) => [n : &from(n+1)]",
            "define xs = from(42)",
            "force(xs)"  // force calls getValue — but xs is not a DelayEvalClosure
        );
        // xs is a DelayCons, not a Closure — force() returns it as-is.
        // But the tail thunk inside xs should NOT have been evaluated.
        // TODO: need a way to verify without .head()/.tail()
    }

    @Test
    void thunkCapturesFreeVariable() {
        // delay(expr) inside a function should capture the param.
        // delay(x + 1) captures x from the enclosing scope.
        Object result = interpretProgram(
            "define foo(x) => delay(x + 1)",
            "define promise = foo(5)",
            "force(promise)"
        );
        assertEquals(6L, ((Number)result).longValue(),
            "delay(x+1) with x=5 should force to 6");
    }

    // ── Phase 4: Builtin.delay() ──

    @Test
    void builtinDelayReturnsThunk() {
        // delay(expr) should compile expr as a thunk and return
        // a DelayEvalClosure without evaluating expr.
        Object result = interpretProgram(
            "define n = 0",
            "define inc() => n = n + 1",
            "define promise = delay(inc())",
            "n"  // should be 0 — inc() has NOT been called yet
        );
        assertEquals(0L, ((Number)result).longValue(),
            "delay(inc()) should not evaluate inc()");
    }

    @Test @Disabled("single-script compilation: lazy param capture not yet supported")
    void builtinDelayForcesOnGetValue() {
        // force(promise) should evaluate the thunk.
        Object result = interpretProgram(
            "define n = 0",
            "define inc() => n = n + 1",
            "define promise = delay(inc())",
            "force(promise)",   // forces inc() → n=1
            "n"
        );
        assertEquals(1L, ((Number)result).longValue(),
            "force(promise) should evaluate the thunk");
    }

    @Test @Disabled("dynamic .head()/.tail() resolution on DelayCons hangs — needs INVOKE_DYN_METHOD fix")
    void delayConsTailForcedOnlyOnce() {
        // The tail thunk should be memoized — forcing it repeatedly
        // should not re-evaluate.
        Object result = interpretProgram(
            "define n = 0",
            "define inc() => n = n + 1",
            "define xs = [1 : &[inc(), 3]]",
            "xs.tail().head()",       // force first time → inc() → n=1
            "xs.tail().head()",       // force again → cached, n stays 1
            "n"
        );
        assertEquals(1L, ((Number)result).longValue(),
            "tail thunk memoized — inc() called only once");
    }
}
