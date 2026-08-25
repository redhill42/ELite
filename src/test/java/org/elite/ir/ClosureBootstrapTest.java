package org.elite.ir;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.el.ELContext;

import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClosureBootstrapTest {

    private static final MethodType CALL_SITE_TYPE = MethodType.methodType(
        IRCompiledClosure.class, EvaluationContext.class, Object.class);

    private static ELContext elctx;

    @BeforeAll
    static void createEngine() {
        elctx = ELEngine.createELContext();
    }

    @MetaMethod(name = "addOne", arity = 1, varargs = false, keys = {"x"})
    public static Object addOne(EvaluationContext env, Object[] args) {
        return (Integer)args[0] + 1;
    }

    @Test
    void staticLambda() throws Throwable {
        MethodHandle impl = MethodHandles.lookup().findStatic(
            ClosureBootstrapTest.class, "addOne",
            MethodType.methodType(Object.class, EvaluationContext.class, Object[].class));

        CallSite cs = ClosureBootstrap.closureBootstrap(
            MethodHandles.lookup(), "closure", CALL_SITE_TYPE, impl);
        EvaluationContext ctx = new EvaluationContext(elctx);

        IRCompiledClosure closure = (IRCompiledClosure)
            cs.getTarget().invokeExact(ctx, (Object)null);

        assertEquals(1, closure.arity(elctx));
        assertEquals(42, closure.execute(ctx, new Object[]{41}));
        assertEquals("#<procedure: addOne/1>", closure.toString());
    }

    int value = 7;

    @MetaMethod(name = "getValue", arity = 1, varargs = false, keys = {"x"})
    public Object getValue(EvaluationContext env, Object[] args) {
        return (Integer)args[0] + this.value;
    }

    @Test
    void instanceLambda() throws Throwable {
        MethodHandle impl = MethodHandles.lookup().findVirtual(
            ClosureBootstrapTest.class, "getValue",
            MethodType.methodType(Object.class, EvaluationContext.class, Object[].class));

        CallSite cs = ClosureBootstrap.closureBootstrap(
            MethodHandles.lookup(), "closure", CALL_SITE_TYPE, impl);
        EvaluationContext ctx = new EvaluationContext(elctx);

        IRCompiledClosure closure = (IRCompiledClosure)
            cs.getTarget().invokeExact(ctx, (Object)this);   // owner = test instance

        assertEquals(1, closure.arity(elctx));
        assertEquals(12, closure.execute(ctx, new Object[]{5}));   // 5 + 7
    }

    @MetaMethod(name = "<lambda>", arity = 1, varargs = false, keys = {"$"})
    public static Object block(EvaluationContext env, Object[] args) {
        // Block lambdas receive the whole argument array as one element.
        Object[] wrapped = (Object[])args[0];
        return "block:" + wrapped[0] + ":" + wrapped[1];
    }

    @MetaMethod(name = "greet", arity = 2, varargs = false, keys = {"a", "b"},
                defaults = {@Value(kind = ValueKind.INT, intValue = 5),
                            @Value(kind = ValueKind.STRING, stringValue = "x")})
    public static Object greet(EvaluationContext env, Object[] args) {
        return args[0] + ":" + args[1];
    }

    @Test
    void blockLambda() throws Throwable {
        MethodHandle impl = MethodHandles.lookup().findStatic(
            ClosureBootstrapTest.class, "block",
            MethodType.methodType(Object.class, EvaluationContext.class, Object[].class));
        CallSite cs = ClosureBootstrap.closureBootstrap(
            MethodHandles.lookup(), "closure", CALL_SITE_TYPE, impl);
        EvaluationContext ctx = new EvaluationContext(elctx);
        IRCompiledClosure closure = (IRCompiledClosure)
            cs.getTarget().invokeExact(ctx, (Object)null);
        assertEquals("block:a:b", closure.execute(ctx, new Object[]{"a", "b"}));
    }

    @Test
    void defaultParameters() throws Throwable {
        MethodHandle impl = MethodHandles.lookup().findStatic(
            ClosureBootstrapTest.class, "greet",
            MethodType.methodType(Object.class, EvaluationContext.class, Object[].class));
        CallSite cs = ClosureBootstrap.closureBootstrap(
            MethodHandles.lookup(), "closure", CALL_SITE_TYPE, impl);
        EvaluationContext ctx = new EvaluationContext(elctx);
        IRCompiledClosure closure = (IRCompiledClosure)
            cs.getTarget().invokeExact(ctx, (Object)null);
        // args.length(1) != nvars(2): trailing default "x" is filled in.
        assertEquals("hello:x", closure.execute(ctx, new Object[]{"hello"}));
    }

    @Test
    void namedClosureToString() throws Throwable {
        MethodHandle impl = MethodHandles.lookup().findStatic(
            ClosureBootstrapTest.class, "addOne",
            MethodType.methodType(Object.class, EvaluationContext.class, Object[].class));
        CallSite cs = ClosureBootstrap.closureBootstrap(
            MethodHandles.lookup(), "closure", CALL_SITE_TYPE, impl);
        EvaluationContext ctx = new EvaluationContext(elctx);
        IRCompiledClosure closure = (IRCompiledClosure)
            cs.getTarget().invokeExact(ctx, (Object)null);
        assertEquals("#<procedure: addOne/1>", closure.toString());
    }
}
