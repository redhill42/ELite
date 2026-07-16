/*
 * Copyright 2006-2026 Daniel Yuan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elite.script;

import java.io.Reader;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.el.ELContext;
import javax.el.ELException;
import javax.el.ValueExpression;
import javax.el.MethodNotFoundException;
import javax.script.AbstractScriptEngine;
import javax.script.ScriptException;
import javax.script.ScriptContext;
import javax.script.Bindings;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngine;
import javax.script.SimpleBindings;
import javax.script.Invocable;
import javax.script.Compilable;
import javax.script.CompiledScript;
import static javax.script.ScriptContext.ENGINE_SCOPE;

import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.eval.EvaluationException;
import org.elite.eval.ELUtils;
import org.elite.eval.closure.ClosureObject;
import org.elite.ir.BytecodeCompiler;
import org.elite.ir.CompilationError;
import org.elite.ir.IRFunction;
import org.elite.ir.IRProgram;
import org.elite.parser.Parser;
import org.elite.parser.ParseException;
import elite.lang.Closure;

class ELiteScriptEngine extends AbstractScriptEngine
    implements Invocable, Compilable
{
    private final ELiteScriptEngineFactory factory;
    private Parser parser;

    // the key used to give back ELContext from ScriptContext
    public static final String EL_CONTEXT_KEY = ELContext.class.getName();

    ELiteScriptEngine(ELiteScriptEngineFactory factory) {
        this.factory = factory;
        this.context = new ScriptContextImpl();
    }

    /**
     * Expose the ELContext.
     */
    @Override
    public Object get(String key) {
        if (EL_CONTEXT_KEY.equals(key)) {
            return getELContext(getContext());
        } else {
            return super.get(key);
        }
    }
    
    public Object eval(String script, ScriptContext ctx)
        throws ScriptException
    {
        CompiledScript cs = compile(script);
        return cs.eval();
    }

    public Object eval(Reader reader, ScriptContext context)
        throws ScriptException
    {
        try {
            String script = readScript(reader);
            return eval(script, context);
        } catch (IOException ex) {
            throw new ScriptException(ex);
        }
    }

    public Bindings createBindings() {
        return new SimpleBindings();
    }

    public ScriptEngineFactory getFactory() {
        return factory;
    }

    // Invocable implementation

    public Object invokeFunction(String name, Object... args)
        throws ScriptException, NoSuchMethodException
    {
        ELContext elctx = getELContext(getContext());
        ValueExpression exp = elctx.getVariableMapper().resolveVariable(name);
        if (exp instanceof Closure) {
            try {
                return ((Closure)exp).call(elctx, args);
            } catch (EvaluationException ex) {
                ScriptException ex2 = new ScriptException(ex.getMessage());
                ex2.initCause(ex.getCause());
                ex2.setStackTrace(ex.getStackTrace());
                throw ex2;
            } catch (ELException ex) {
                throw new ScriptException(ex);
            }
        } else {
            throw new NoSuchMethodException(name);
        }
    }

    public Object invokeMethod(Object thiz, String name, Object... args)
        throws ScriptException, NoSuchMethodException
    {
        if (!(thiz instanceof ClosureObject)) {
            throw new NoSuchMethodException("no such method: " + name);
        }

        Object result;

        try {
            ELContext elctx = getELContext(getContext());
            ClosureObject closure = (ClosureObject)thiz;
            result = closure.invoke(elctx, name, ELEngine.getCallArgs(args));
        } catch (MethodNotFoundException ex) {
            throw new NoSuchMethodException("no such method: " + name);
        } catch (EvaluationException ex) {
            ScriptException ex2 = new ScriptException(ex.getMessage());
            ex2.initCause(ex.getCause());
            ex2.setStackTrace(ex.getStackTrace());
            throw ex2;
        } catch (ELException ex) {
            throw new ScriptException(ex);
        }

        if (result == ELUtils.NO_RESULT) {
            throw new NoSuchMethodException("no such method: " + name);
        } else {
            return result;
        }
    }

    public <T> T getInterface(Class<T> iface) {
        if (iface == null || !iface.isInterface())
            throw new IllegalArgumentException("interface expected");

        return iface.cast(Proxy.newProxyInstance(
            iface.getClassLoader(), new Class[]{iface},
            new InterfaceImplementorInvocationHandler(null)));
    }

    public <T> T getInterface(Object thiz, Class<T> iface) {
        if (thiz == null)
            throw new IllegalArgumentException("script object can not be null");
        if (iface == null || !iface.isInterface())
            throw new IllegalArgumentException("interface expected");

        return iface.cast(Proxy.newProxyInstance(
            iface.getClassLoader(), new Class[]{iface},
            new InterfaceImplementorInvocationHandler(thiz)));
    }

    // Compilable implementation

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        try {
            ELProgram program = parse(script);
            program.setFilename((String)get(ScriptEngine.FILENAME));

            Boolean standalone = (Boolean)get("elite.standalone");
            if (standalone != null)
                program.setStandalone(standalone);

            switch (ELProgram.OPT_LEVEL) {
            case 0:
                return new ASTCompiledScript(this, program);

            case 1, 2: {
                ELContext elctx = getELContext(getContext());
                IRFunction fn = program.compile(elctx).entry();
                return new IRCompiledScript(this, fn);
            }

            case 3: default: {
                ELContext elctx = getELContext(getContext());
                IRProgram irProg = program.compile(elctx);
                try {
                    var cf = BytecodeCompiler.compile(irProg);
                    return new BytecodeCompiledScript(this, cf);
                } catch (CompilationError e) {
                    return new IRCompiledScript(this, irProg.entry());
                }
            }
            }
        } catch (ParseException ex) {
            ScriptException ex2 = new ScriptException(ex.getMessage());
            ex2.initCause(ex);
            ex2.setStackTrace(ex.getStackTrace());
            throw ex2;
        }
    }

    public CompiledScript compile(Reader script) throws ScriptException {
        try {
            return compile(readScript(script));
        } catch (IOException ex) {
            throw new ScriptException(ex);
        }
    }

    ELContext getELContext(final ScriptContext sctx) {
        ELContext elctx = (ELContext)sctx.getAttribute(EL_CONTEXT_KEY, ENGINE_SCOPE);

        if (elctx == null) {
            // Create an ELContext that connect to external variables defined in ScriptContext
            ContextVariableMapper vm = new ContextVariableMapper(sctx);
            elctx = ELEngine.createELContext(vm);
            elctx.putContext(ScriptContext.class, sctx);

            // Set context attributes
            sctx.setAttribute(EL_CONTEXT_KEY, elctx, ENGINE_SCOPE);
            sctx.setAttribute("context", sctx, ENGINE_SCOPE);
            String[] argv = (String[])sctx.getAttribute(ScriptEngine.ARGV);
            if (argv != null) {
                sctx.setAttribute("ARGV", argv, ENGINE_SCOPE);
            }

            // invoke callback method to initialize context
            factory.contextCreated(elctx, sctx);
        }

        return elctx;
    }

    private ELProgram parse(String script) throws ParseException {
        Parser p = new Parser(getELContext(context), script);
        p.setFileName((String)get(ScriptEngine.FILENAME));
        if (this.parser != null)
            p.importSyntaxRules(this.parser);
        this.parser = p;
        return p.parse();
    }
    
    private String readScript(Reader reader) throws IOException {
        StringBuilder buf = new StringBuilder();
        char[] cbuf = new char[8192];
        for (int len; (len = reader.read(cbuf)) != -1; ) {
            buf.append(cbuf, 0, len);
        }
        reader.close();
        return buf.toString();
    }

    private final class InterfaceImplementorInvocationHandler
        implements InvocationHandler
    {
        private final Object thiz;

        InterfaceImplementorInvocationHandler(Object thiz) {
            this.thiz = thiz;
        }

        public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable
        {
            final String name = method.getName();
            final Object[] a = args == null ? new Object[0] : args;
            if (thiz == null) {
                return invokeFunction(name, a);
            } else {
                return invokeMethod(thiz, name, a);
            }
        }
    }
}
