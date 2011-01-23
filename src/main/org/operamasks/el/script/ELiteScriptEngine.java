/*
 * Copyright (c) 2006-2011 Daniel Yuan.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
 */

package org.operamasks.el.script;

import java.io.Reader;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
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

import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.ELProgram;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.ELUtils;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.parser.Parser;
import org.operamasks.el.parser.ParseException;
import elite.lang.Closure;

class ELiteScriptEngine extends AbstractScriptEngine
    implements Invocable, Compilable
{
    private ELiteScriptEngineFactory factory;
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
        try {
            ELProgram program = parse(script);
            ELContext elctx = getELContext(ctx);
            String filename = (String)get(ScriptEngine.FILENAME);
            return program.execute(elctx, filename, 1);
        } catch (ParseException ex) {
            ScriptException ex2 = new ScriptException(ex.getMessage(),
                                                      ex.getFileName(),
                                                      ex.getLineNumber(),
                                                      ex.getColumnNumber());
            ex2.initCause(ex);
            ex2.setStackTrace(ex.getStackTrace());
            throw ex2;
        } catch (EvaluationException ex) {
            ScriptException ex2 = new ScriptException(ex.getMessage());
            ex2.initCause(ex.getCause());
            ex2.setStackTrace(ex.getStackTrace());
            throw ex2;
        } catch (ELException ex) {
            throw new ScriptException(ex);
        }
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

    public CompiledScript compile(String script) throws ScriptException {
        try {
            return new ELiteCompiledScript(this, parse(script));
        } catch (ParseException ex) {
            ScriptException ex2 = new ScriptException(ex.getMessage(),
                                                      ex.getFileName(),
                                                      ex.getLineNumber(),
                                                      ex.getColumnNumber());
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
        Parser p = new Parser(script);
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
        private Object thiz;
        private AccessControlContext acc;

        InterfaceImplementorInvocationHandler(Object thiz) {
            this.thiz = thiz;
            this.acc = AccessController.getContext();
        }

        public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable
        {
            final String name = method.getName();
            final Object[] a = args == null ? new Object[0] : args;
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                public Object run() throws Exception {
                    if (thiz == null) {
                        return invokeFunction(name, a);
                    } else {
                        return invokeMethod(thiz, name, a);
                    }
                }
            }, acc);
        }
    }
}
