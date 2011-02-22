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

package org.operamasks.el.parser;

import javax.el.ValueExpression;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import javax.el.ELException;
import javax.el.PropertyNotFoundException;
import javax.el.PropertyNotWritableException;
import javax.el.MethodInfo;
import javax.el.MethodNotFoundException;
import javax.el.ELContext;
import javax.el.ELResolver;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.io.Serializable;

import elite.lang.Closure;
import elite.lang.Annotation;
import elite.lang.Range;
import elite.lang.Seq;
import elite.lang.Decimal;
import elite.lang.Rational;
import elite.lang.Symbol;
import elite.lang.annotation.Data;
import elite.ast.Expression;
import elite.xml.XmlNode;

import org.operamasks.el.eval.*;
import org.operamasks.el.eval.closure.*;
import org.operamasks.el.eval.seq.*;
import org.operamasks.el.resolver.MethodResolver;
import static org.operamasks.el.eval.TypeCoercion.*;
import static org.operamasks.el.eval.ELUtils.*;
import static org.operamasks.el.resources.Resources.*;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.DOMException;
import javax.xml.XMLConstants;

@SuppressWarnings("unchecked")
public abstract class ELNode implements Serializable
{
    public final int op;
    public final int pos;

    // Operator precedence
    public static final int
        THEN_PREC       = 0,
        ASSIGN_PREC     = 10,
        ASSIGNOP_PREC   = 20,
        COND_PREC       = 30,
        COALESCE_PREC   = 40,
        OR_PREC         = 50,
        AND_PREC        = 60,
        BITOR_PREC      = 70,
        XOR_PREC        = 80,
        BITAND_PREC     = 90,
        EQ_PREC         = 100,
        ORD_PREC        = 110,
        SHIFT_PREC      = 120,
        ADD_PREC        = 130,
        MUL_PREC        = 140,
        PREFIX_PREC     = 150,
        XFORM_PREC      = 160,
        POW_PREC        = 170,
        POSTFIX_PREC    = 180,
        DEFAULT_PREC    = 180,
        NO_PREC         = 500;

    // Operator overriden identifiers
    public static final String opIdentifiers[] = new String[Token.MAX_TOKEN];
    static {
        opIdentifiers[Token.POS]    = "__pos__";
        opIdentifiers[Token.NEG]    = "__neg__";
        opIdentifiers[Token.NOT]    = "!";
        opIdentifiers[Token.BITNOT] = ":!:";
        opIdentifiers[Token.INC]    = "++";
        opIdentifiers[Token.DEC]    = "--";
        opIdentifiers[Token.BITOR]  = ":|:";
        opIdentifiers[Token.BITAND] = ":&:";
        opIdentifiers[Token.XOR]    = ":^:";
        opIdentifiers[Token.SHL]    = "<<";
        opIdentifiers[Token.SHR]    = ">>";
        opIdentifiers[Token.USHR]   = ">>>";
        opIdentifiers[Token.CAT]    = "~";
        opIdentifiers[Token.ADD]    = "+";
        opIdentifiers[Token.SUB]    = "-";
        opIdentifiers[Token.MUL]    = "*";
        opIdentifiers[Token.DIV]    = "/";
        opIdentifiers[Token.REM]    = "%";
        opIdentifiers[Token.POW]    = "^";
        opIdentifiers[Token.EQ]     = "==";
        opIdentifiers[Token.NE]     = "!=";
        opIdentifiers[Token.LT]     = "<";
        opIdentifiers[Token.LE]     = "<=";
        opIdentifiers[Token.GT]     = ">";
        opIdentifiers[Token.GE]     = ">=";
    }

    ELNode(int op, int pos) {
        this.op = op;
        this.pos = pos;
    }

    /**
     * Get the operator
     */
    public final int op() {
        return op;
    }

    /**
     * Get the position
     */
    public final int pos() {
        return pos;
    }

    /**
     * Set the position in the frame.
     */
    final ELNode pos(Frame f) {
        if (f != null)
            f.setPos(pos);
        return this;
    }

    /**
     * Return the precedence of the operator
     */
    public int precedence() {
        return NO_PREC;
    }

    /**
     * Order the expression based on precedence
     */
    ELNode order() {
        return this;
    }

    /**
     * The interface implemented by an AST node that support pattern matching.
     */
    public static interface Pattern {
        public boolean matches(EvaluationContext context, Object value);
    }

    /**
     * Evaluate value.
     */
    public abstract Object getValue(EvaluationContext context);

    /**
     * Evaluate type.
     */
    public abstract Class getType(EvaluationContext context);

    /**
     * Evaluate readonly
     */
    public boolean isReadOnly(EvaluationContext context) {
        return true;
    }

    /**
     * Evaluate lvalue.
     */
    public void setValue(EvaluationContext context, Object value) {
        throw propertyNotWritable(context.getELContext(), null, null);
    }

    /**
     * Utility.
     */
    boolean getBoolean(EvaluationContext context) {
        Object value = getValue(context);
        if (value instanceof Boolean) {
            return (Boolean)value;
        } else {
            try {
                return coerceToBoolean(value);
            } catch (ELException ex) {
                throw runtimeError(context.getELContext(), ex);
            }
        }
    }

    /**
     * Evaluate method info.
     */
    public MethodInfo getMethodInfo(EvaluationContext context) {
        return ELEngine.getTargetMethodInfo(context.getELContext(), getValue(context));
    }

    /**
     * Evaluate method invocation (called by MethodExpression).
     */
    public Object invokeMethod(EvaluationContext context, Object[] args) {
        return invoke(context, ELEngine.getCallArgs(args));
    }

    /**
     * Apply function call.
     */
    public Object invoke(EvaluationContext context, Closure[] args) {
        ELContext elctx = context.getELContext();
        Object target = getValue(context);

        try {
            return ELEngine.invokeTarget(elctx, target, args);
        } catch (MethodNotFoundException ex) {
            throw methodNotFound(elctx, target, null, ex);
        } catch (RuntimeException ex) {
            throw runtimeError(elctx, ex);
        }
    }

    Object resolveTarget(EvaluationContext context, String id) {
        ELContext elctx = context.getELContext();

        ValueExpression expr = context.resolveVariable(id);
        if (expr != null) {
            return (expr instanceof Closure) ? expr : expr.getValue(elctx);
        }

        MethodClosure method = MethodResolver.getInstance(elctx)
            .resolveGlobalMethod(context.getFunctionMapper(), id);
        if (method != null) {
            return method;
        }

        elctx.setPropertyResolved(false);
        return elctx.getELResolver().getValue(elctx, null, id);
    }

    static class TailCall extends Procedure {
        Closure[] args;
        Object result;

        TailCall(EvaluationContext context, LAMBDA node, Closure[] args) {
            super(context, node);
            this.args = args;
        }
    }

    /**
     * Tail-recursion optimization.
     */
    boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
        if (args == null) {
            call.result = getValue(context);
        } else {
            call.result = invoke(context, args);
        }
        return false;
    }

    /**
     * Create a closure object that encapsulate this node.
     */
    public Closure closure(EvaluationContext context) {
        return new DelayEvalClosure(context, this);
    }

    /**
     * Apply function mapper.
     */
    public final void applyFunctionMapper(final FunctionMapper fnm) {
        Visitor v = new DefaultVisitor() {
            public void visit(APPLY apply) {
                if (apply.right instanceof IDENT) {
                    String id = ((IDENT)apply.right).id;
                    int i = id.indexOf(':');
                    if (i != -1) {
                        fnm.resolveFunction(id.substring(0, i), id.substring(i+1));
                    }
                }
            }
        };
        accept(v);
    }

    /**
     * Apply variable mapper to copy global variables into private variable mapper.
     */
    public final void applyVariableMapper(final VariableMapper vm) {
        Visitor v = new DefaultVisitor() {
            public void visit(IDENT node) {
                vm.resolveVariable(node.id);
            }
            public void visit(NEW node) {
                vm.resolveVariable(node.base);
            }
        };
        accept(v);
    }
    
    public abstract void accept(Visitor v);

    /**
     * Throws an evaluation exception.
     */
    ELException runtimeError(ELContext elctx, String message) {
        return new EvaluationException(elctx, message);
    }

    /**
     * Throws an evaluation exception with cause.
     */
    ELException runtimeError(ELContext elctx, Throwable cause) {
        if (cause instanceof EvaluationException) {
            throw (EvaluationException)cause;
        } else if (cause instanceof Control) {
            throw (Control)cause;
        } else if (cause instanceof ELException) {
            throw new EvaluationException(elctx, cause.getMessage(), cause.getCause());
        } else {
            throw new EvaluationException(elctx, cause);
        }
    }

    /**
     * Throws an evaluation exception with message and cause.
     */
    ELException runtimeError(ELContext elctx, String message, Throwable cause) {
        return new EvaluationException(elctx, message, cause);
    }

    /**
     * Throws a property not found exception.
     */
    ELException propertyNotFound(ELContext elctx, Object base, Object property) {
        String message;
        if (base != null && property == null) {
            if (base instanceof String) {
                message = _T(EL_UNDEFINED_IDENTIFIER, base);
            } else {
                message = _T(EL_PROPERTY_NOT_FOUND, base, property);
            }
        } else {
            if (base instanceof ClosureObject) {
                base = ((ClosureObject)base).get_class().getName();
            } else if (base instanceof Closure) {
                base = base.toString();
            } else if (base != null) {
                base = base.getClass().getName();
            }
            message = _T(EL_PROPERTY_NOT_FOUND, base, property);
        }
        throw new EvaluationException(elctx, message);
    }

    /**
     * Throws a property not writable exception.
     */
    ELException propertyNotWritable(ELContext elctx, Object base, Object property) {
        String message;
        if (base != null && property == null) {
            if (base instanceof String) {
                message = _T(EL_VARIABLE_NOT_WRITABLE, base);
            } else {
                message = _T(EL_READONLY_EXPRESSION);
            }
        } else {
            if (base instanceof ClosureObject) {
                base = ((ClosureObject)base).get_class().getName();
            } else if (base instanceof Closure) {
                base = base.toString();
            } else if (base != null) {
                base = base.getClass().getName();
            }
            message = _T(EL_PROPERTY_NOT_WRITABLE, base, property);
        }
        throw new EvaluationException(elctx, message);
    }

    /**
     * Throws a method not found exception.
     */
    ELException methodNotFound(ELContext elctx, Object base, Object property, MethodNotFoundException cause) {
        String msg = (cause == null) ? null : cause.getMessage();
        if (msg == null) {
            if (base instanceof ClosureObject) {
                base = ((ClosureObject)base).get_class().getName();
            } else if (base instanceof Closure) {
                base = base.toString();
            } else if (base != null) {
                base = base.getClass().getName();
            }
            msg = _T(EL_METHOD_NOT_FOUND, base, property);
        }
        throw new EvaluationException(elctx, msg, cause);
    }

    // -----------------------------------------------------------------------

    /**
     * Composite of EL expression and constant string.
     */
    public static class Composite extends ELNode {
        public final ELNode[] elems;

        public Composite(int pos, ELNode[] elems) {
            super(Token.EXPR, pos);
            this.elems = elems;
        }

        public Object getValue(EvaluationContext context) {
            StringBuilder buf = new StringBuilder();
            for (ELNode e : elems) {
                Object v = e.getValue(context);
                buf.append(coerceToString(v));
            }
            return buf.toString();
        }

        public Class getType(EvaluationContext context) {
            return String.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Lambda expression.
     */
    public static class LAMBDA extends ELNode {
        public final String    file;
        public final String    name;
        public final String    rtype;
        public final DEFINE[]  vars;
        public final boolean   varargs;
        public final ELNode    body;

        private boolean dvals;

        public LAMBDA(int pos, String file, DEFINE[] vars, ELNode body) {
            this(pos, file, null, null, vars, false, body);
        }
        
        public LAMBDA(int      pos,
                      String   file,
                      String   name,
                      String   rtype,
                      DEFINE[] vars,
                      boolean  varargs,
                      ELNode   body)
        {
            super(Token.LAMBDA, pos);
            this.file = file;
            this.name = name;
            this.rtype = rtype;
            this.vars = vars;
            this.varargs = varargs;
            this.body = body;

            for (DEFINE var : vars) {
                if (var.expr != null) {
                    dvals = true;
                    break;
                }
            }
        }

        public Object getValue(EvaluationContext context) {
            return new Procedure(context, this);
        }

        public Class getType(EvaluationContext context) {
            return Procedure.class;
        }

        public MethodInfo getMethodInfo(EvaluationContext context) {
            Class returnType = Object.class;
            if (rtype != null) {
                Object cls = ELEngine.resolveClass(context, rtype);
                if (cls instanceof Class) {
                    returnType = (Class)cls;
                }
            }

            Class[] paramTypes = new Class[vars.length];
            for (int i = 0; i < vars.length; i++) {
                if (vars[i].type != null) {
                    Object cls = ELEngine.resolveClass(context, vars[i].type);
                    if (cls instanceof Class) {
                        paramTypes[i] = (Class)cls;
                    } else {
                        paramTypes[i] = Object.class;
                    }
                } else {
                    paramTypes[i] = Object.class;
                }
            }

            return new MethodInfo(name, returnType, paramTypes);
        }

        public Object invoke(EvaluationContext context, Closure[] args) {
            if (body == null) {
                throw runtimeError(context.getELContext(), _T(EL_INVOKE_ABSTRACT_METHOD));
            }

            Frame frame = StackTrace.addFrame(context.getELContext(), name, file, pos);
            try {
                TailCall call = new TailCall(context, this, args);
                EvaluationContext env;
                do {
                    env = context.pushContext();
                    init_call(env, call);
                } while (body.pos(frame).invokeTail(env, call, null));
                return cast_result(context, call.result);
            } catch (Control.Return ret) {
                return cast_result(context, ret.getResult());
            } finally {
                StackTrace.removeFrame(context.getELContext());
            }
        }

        private void init_call(EvaluationContext env, TailCall call) {
            ELContext elctx = env.getELContext();

            // copy argument list
            Closure[] args = copyCallArgs(env, call.args);
            call.args = null;

            // evaluate argument values and set local variable
            for (int i = 0; i < args.length; i++) {
                DEFINE var = this.vars[i];
                if (var.type != null) {
                    args[i] = TypedClosure.make(env, var.type, args[i]);
                }
                if (var.immediate) {
                    // force to evaluate the argument value
                    Object value = args[i].getValue(elctx);
                    if (value instanceof VarArgList) {
                        ((VarArgList)value).force(elctx);
                    }
                }
                env.setVariable(var.id, args[i]);
            }

            // the procedure is also a local variable which is the tail call
            if (this.name != null) {
                env.setVariable(this.name, call);
            }
        }

        private Closure[] copyCallArgs(EvaluationContext context, Closure[] args) {
            ELContext elctx = context.getELContext();

            int argc = args.length;
            int nvars = vars.length;
            Closure[] xargs = null;

            if (argc < nvars && dvals) {
                // pad with default values
                xargs = new Closure[nvars];
            } else if (varargs ? (argc < nvars-1) : (argc != nvars)) {
                throw runtimeError(elctx, _T(EL_FN_BAD_ARG_COUNT, name, nvars, argc));
            }

            // rearrange named arguments
            for (int i = 0; i < argc; i++) {
                if (args[i] instanceof NamedClosure) {
                    NamedClosure c = (NamedClosure)args[i];
                    int j = indexOfVar(c.name());
                    if (j == -1)
                        throw runtimeError(elctx, _T(EL_UNKNOWN_ARG_NAME, c.name()));
                    if (xargs == null)
                        xargs = new Closure[argc];
                    xargs[j] = c.getDelegate();
                }
            }

            if (xargs != null) {
                // rearrange non-named arguments
                int j = 0;
                for (int i = 0; i < argc; i++) {
                    if (!(args[i] instanceof NamedClosure)) {
                        while (xargs[j] != null)
                            j++;
                        xargs[j++] = args[i];
                    }
                }
                // assign default values
                args = xargs;
                argc = xargs.length;
                for (; j < argc; j++) {
                    if (args[j] == null) {
                        if (vars[j].expr == null) {
                            throw runtimeError(elctx, _T(EL_MISSING_ARG_VALUE, vars[j].id));
                        } else {
                            args[j] = vars[j].expr.closure(context);
                        }
                    }
                }
            }

            // create var-arg list
            if (varargs) {
                --nvars; // number of fixed arguments
                assert argc >= nvars;

                Object lastArg = (argc == nvars+1) ? args[nvars].getValue(elctx) : null;
                if (lastArg != null && (lastArg instanceof VarArgList)) {
                    // The last argument is a variable argument list, no more
                    // work to do for this case.
                } else {
                    // Create variable argument list.
                    Closure[] actual = new Closure[nvars+1];
                    System.arraycopy(args, 0, actual, 0, nvars);
                    actual[nvars] = new LiteralClosure(new VarArgList(elctx, args, nvars));
                    args = actual;
                }
            }

            return args;
        }

        private int indexOfVar(String name) {
            int nvars = vars.length - (varargs ? 1 : 0);
            for (int i = 0; i < nvars; i++) {
                if (name.equals(vars[i].id)) {
                    return i;
                }
            }
            return -1;
        }

        Object cast_result(EvaluationContext ctx, Object res) {
            if (rtype == null) {
                return res;
            } else {
                return TypedClosure.typecast(ctx, rtype, res);
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Block - a special form of lambda expression.
     */
    public static class BLOCK extends LAMBDA {
        public BLOCK(int pos, String file, ELNode body) {
            super(pos, file, new DEFINE[0], body);
        }

        public Object invoke(EvaluationContext context, Closure[] args) {
            ELContext elctx = context.getELContext();
            Frame frame = StackTrace.addFrame(elctx, name, file, pos);

            // force to evaluate argument values
            for (Closure c : args) {
                c.getValue(elctx);
            }

            try {
                EvaluationContext env = context.pushContext();

                // set local variables
                if (args.length == 1) {
                    env.setVariable("$", args[0]);
                } else if (args.length > 1) {
                    env.setVariable("$", new LiteralClosure(new VarArgList(elctx, args, 0)));
                }

                // invoke body
                return body.pos(frame).getValue(env);
            } catch (Control.Return ret) {
                return ret.getResult();
            } finally {
                StackTrace.removeFrame(elctx);
            }
        }
    }

    public static class VarArgList extends AbstractList<Object> {
        private ELContext context;
        private Closure[] args;
        private int begin;

        public VarArgList(ELContext context, Closure[] args, int begin) {
            this.context = context;
            this.args = args;
            this.begin = begin;
        }

        public void force(ELContext context) {
            for (int i = begin; i < args.length; i++) {
                args[i].getValue(context);
            }
        }

        public int size() {
            return args.length - begin;
        }

        public Object get(int index) {
            return args[index+begin].getValue(context);
        }

        public Object set(int index, Object element) {
            index += begin;
            Object oldValue = args[index].getValue(context);
            args[index].setValue(context, element);
            return oldValue;
        }

        public int indexOf(Object o) {
            if (o == null) {
                for (int i = begin; i < args.length; i++) {
                    if (null == args[i].getValue(context)) {
                        return i;
                    }
                }
            } else {
                for (int i = begin; i < args.length; i++) {
                    if (o.equals(args[i].getValue(context))) {
                        return i;
                    }
                }
            }
            return -1;
        }

        public boolean contains(Object o) {
            return indexOf(o) != -1;
        }
    }

    /**
     * Variable declaration.
     */
    public static class DEFINE extends ELNode implements Pattern {
        public String   id;
        public String   type;
        public METASET  meta;
        public ELNode   expr;
        public boolean  immediate;

        public transient Operator operator;

        public DEFINE(int pos, String id) {
            this(pos, id, null, null, null, true);
        }

        public DEFINE(int pos, String id, String type, METASET meta) {
            this(pos, id, type, meta, null, true);
        }

        public DEFINE(int pos, String id, String type, METASET meta, ELNode expr, boolean immediate) {
            super(Token.DEFINE, pos);
            this.id = id;
            this.type = type;
            this.meta = meta;
            this.expr = expr;
            this.immediate = immediate;
        }

        public Object getValue(EvaluationContext context) {
            context.setVariable(id, defineClosure(context));
            return null;
        }

        public Closure defineClosure(EvaluationContext context) {
            Closure closure;

            if (expr == null) {
                closure = TypedClosure.make(context, type, null, false);
            } else if (immediate) {
                closure = TypedClosure.make(context, type, expr.getValue(context), false);
            } else {
                closure = TypedClosure.make(context, type, new EvalClosure(context, expr));
            }

            if (meta != null) {
                closure.setMetaData(meta.getMetaData(context));
            }

            return closure;
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public boolean matches(EvaluationContext context, Object value) {
            if (type != null && !TypedClosure.typecheck(context, type, value)) {
                return false;
            }

            // matches for as-pattern
            if (expr != null && !((Pattern)expr).matches(context, value)) {
                return false;
            }

            if ("_".equals(id)) {
                // The wildcard variable is always matches.
                return true;
            }

            ValueExpression ve = context.resolveLocalVariable(id);
            if (ve != null) {
                // If the variable already bound a value then check to see if values match.
                ELContext elctx = context.getELContext();
                return EQ.equals(elctx, value, ve.getValue(elctx));
            } else {
                // Otherwise add the variable into environment.
                context.setVariable(id, TypedClosure.make(context, type, value, false));
                return true;
            }
        }

        public boolean bind(EvaluationContext context, Closure closure) {
            ELContext elctx = context.getELContext();

            if (type != null || expr != null) {
                return matches(context, closure.getValue(elctx));
            }

            if ("_".equals(id)) {
                return true;
            }

            ValueExpression ve = context.resolveLocalVariable(id);
            if (ve != null) {
                return EQ.equals(elctx, closure.getValue(elctx), ve.getValue(elctx));
            } else {
                context.setVariable(id, new DelayEvalClosure(closure));
                return true;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The class definition.
     */
    public static class CLASSDEF extends ELNode {
        public final String   file;     // file name that defined this class
        public final String   id;       // class id
        public final String   base;     // base class id or java interface name
        public final String[] ifaces;   // java interface names
        public final DEFINE[] vars;     // initial variables
        public final DEFINE[] cvars;    // class variables
        public final DEFINE[] ivars;    // instance variables

        public CLASSDEF(int      pos,
                        String   file,
                        String   id,
                        String   base,
                        String[] ifaces,
                        DEFINE[] vars,
                        DEFINE[] cvars,
                        DEFINE[] ivars)
        {
            super(Token.CLASSDEF, pos);
            this.file = file;
            this.id = id;
            this.base = base;
            this.ifaces = ifaces;
            this.vars = vars;
            this.cvars = cvars;
            this.ivars = ivars;
        }

        public CLASSDEF(int      pos,
                        String   file,
                        String   id,
                        String   base,
                        String[] ifaces,
                        DEFINE[] vars,
                        DEFINE[] body)
        {
            super(Token.CLASSDEF, pos);
            this.file = file;
            this.id = id;
            this.base = base;
            this.ifaces = ifaces;
            this.vars = vars;

            List<DEFINE> cvs = new ArrayList<DEFINE>();
            List<DEFINE> ivs = new ArrayList<DEFINE>();
            for (DEFINE def : body) {
                (isStatic(def) ? cvs : ivs).add(def);
            }

            this.cvars = cvs.toArray(new DEFINE[cvs.size()]);
            this.ivars = ivs.toArray(new DEFINE[ivs.size()]);
        }

        private static boolean isStatic(ELNode.DEFINE def) {
            return (def.meta != null) && (def.meta.modifiers & Modifier.STATIC) != 0;
        }

        public Object getValue(EvaluationContext context) {
            return new ClassDefinition(context, this);
        }

        public Class getType(EvaluationContext context) {
            return ClassDefinition.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Undef statement.
     */
    public static class UNDEF extends ELNode {
        public final String id;

        public UNDEF(int pos, String id) {
            super(Token.UNDEF, pos);
            this.id = id;
        }

        public Object getValue(EvaluationContext context) {
            context.setVariable(id, null);
            return null;
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }
    
    /**
     * Identifier expression.
     */
    public static class IDENT extends ELNode {
        public final String id;

        public IDENT(int pos, String id) {
            super(Token.IDENT, pos);
            this.id = id;
        }

        public Object getValue(EvaluationContext context) {
            ELContext elctx = context.getELContext();

            ValueExpression expr = context.resolveVariable(id);
            if (expr != null) {
                return expr.getValue(elctx);
            }

            elctx.setPropertyResolved(false);
            Object value = elctx.getELResolver().getValue(elctx, null, id);
            if (elctx.isPropertyResolved()) {
                return value;
            }

            MethodClosure method = resolveGlobalMethod(context);
            if (method != null) {
                return method;
            }

            throw propertyNotFound(elctx, id, null);
        }

        public Class getType(EvaluationContext context) {
            ELContext elctx = context.getELContext();

            ValueExpression expr = context.resolveVariable(id);
            if (expr != null) {
                return expr.getType(elctx);
            }

            elctx.setPropertyResolved(false);
            Class type = elctx.getELResolver().getType(elctx, null, id);
            if (elctx.isPropertyResolved()) {
                return type;
            }

            if (resolveGlobalMethod(context) != null) {
                return Closure.class;
            }

            throw propertyNotFound(elctx, id, null);
        }

        public boolean isReadOnly(EvaluationContext context) {
            ELContext elctx = context.getELContext();

            ValueExpression expr = context.resolveVariable(id);
            if (expr != null) {
                return expr.isReadOnly(elctx);
            }

            elctx.setPropertyResolved(false);
            boolean readonly = elctx.getELResolver().isReadOnly(elctx, null, id);
            if (elctx.isPropertyResolved()) {
                return readonly;
            }

            return true;
        }

        public void setValue(EvaluationContext context, Object value) {
            ELContext elctx = context.getELContext();

            try {
                ValueExpression expr = context.resolveVariable(id);
                if (expr != null) {
                    expr.setValue(elctx, value);
                    return;
                }

                elctx.setPropertyResolved(false);
                elctx.getELResolver().setValue(elctx, null, id, value);
            } catch (PropertyNotFoundException ex) {
                throw propertyNotFound(elctx, id, null);
            } catch (PropertyNotWritableException ex) {
                throw propertyNotWritable(elctx, id, null);
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }

            if (!elctx.isPropertyResolved()) {
                throw propertyNotFound(elctx, id, null);
            }
        }

        public MethodInfo getMethodInfo(EvaluationContext context) {
            Object target = resolveTarget(context, id);
            return ELEngine.getTargetMethodInfo(context.getELContext(), target);
        }

        public Object invoke(EvaluationContext context, Closure[] args) {
            ELContext elctx = context.getELContext();

            Object target = resolveTarget(context, id);
            if (target == null) {
                throw runtimeError(elctx, _T(EL_UNDEFINED_IDENTIFIER, id));
            }

            try {
                return ELEngine.invokeTarget(elctx, target, args);
            } catch (MethodNotFoundException ex) {
                throw methodNotFound(elctx, target, id, ex);
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            if (args != null && context.resolveVariable(id) == call) {
                // copy argument list and recursion
                call.args = args;
                return true;
            } else {
                return super.invokeTail(context, call, args);
            }
        }

        public Closure closure(EvaluationContext context) {
            return new VarClosure(context, this);
        }

        private MethodClosure resolveGlobalMethod(EvaluationContext context) {
            return MethodResolver.getInstance(context.getELContext())
                .resolveGlobalMethod(context.getFunctionMapper(), id);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Access expression.
     */
    public static class ACCESS extends Unary {
        public final ELNode index;

        public ACCESS(int pos, ELNode right, ELNode index) {
            super(Token.ACCESS, pos, right);
            this.index = index;
        }

        public int precedence() {
            return POSTFIX_PREC;
        }

        public Object getValue(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object base = right.getValue(context);
            Object property = index.getValue(context);

            if (base == null || property == null) {
                return null;
            }

            try {
                elctx.setPropertyResolved(false);
                Object value = elctx.getELResolver().getValue(elctx, base, property);
                if (elctx.isPropertyResolved()) return value;
            } catch (PropertyNotFoundException ex) {
                // fallthrough
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }

            if (property instanceof String) {
                Closure method = resolveMethod(elctx, base, (String)property);
                if (method != null) {
                    return method;
                }
            }

            throw propertyNotFound(elctx, base, property);
        }

        public void setValue(EvaluationContext context, Object value) {
            ELContext elctx = context.getELContext();
            Object base = right.getValue(context);
            Object property = index.getValue(context);

            if (base == null || property == null) {
                throw propertyNotFound(elctx, base, index.getValue(context));
            }

            try {
                elctx.setPropertyResolved(false);
                elctx.getELResolver().setValue(elctx, base, property, value);
                if (elctx.isPropertyResolved()) return;
            } catch (PropertyNotFoundException ex) {
                // fallthrough
            } catch (PropertyNotWritableException ex) {
                throw propertyNotWritable(elctx, base, property);
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }

            throw propertyNotFound(elctx, base, property);
        }

        public Class getType(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object base = right.getValue(context);
            Object property = index.getValue(context);

            if (base == null || property == null) {
                return null;
            }

            try {
                elctx.setPropertyResolved(false);
                Class type = elctx.getELResolver().getType(elctx, base, property);
                if (elctx.isPropertyResolved()) return type;
            } catch (PropertyNotFoundException ex) {
                // fallthrough
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }

            if (property instanceof String) {
                if (resolveMethod(elctx, base, (String)property) != null) {
                    return Closure.class;
                }
            }

            throw propertyNotFound(elctx, base, property);
        }

        public boolean isReadOnly(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object base = right.getValue(context);
            Object property = index.getValue(context);

            if (base == null || property == null) {
                return true;
            }

            try {
                elctx.setPropertyResolved(false);
                boolean result = elctx.getELResolver().isReadOnly(elctx, base, property);
                if (elctx.isPropertyResolved()) return result;
            } catch (PropertyNotFoundException ex) {
                // fallthrough
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }

            if (property instanceof String) {
                if (resolveMethod(elctx, base, (String)property) != null) {
                    return true;
                }
            }

            throw propertyNotFound(elctx, base, property);
        }

        public MethodInfo getMethodInfo(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object base = right.getValue(context);
            Object property = index.getValue(context);

            if (base == null || property == null) {
                throw methodNotFound(elctx, base, property, null);
            }

            String name = coerceToString(property);

            // Check global method
            if (base == GlobalScope.SINGLETON) {
                Object target = getValue(context);
                return ELEngine.getTargetMethodInfo(elctx, target);
            }

            // Check method closure
            if (!(base instanceof MethodDelegate)) {
                Closure method = resolveMethod(elctx, base, name);
                if (method != null) {
                    return method.getMethodInfo(elctx);
                }
            }

            // Check dynamic method
            if (base instanceof MethodResolvable) {
                return ((MethodResolvable)base).getMethodInfo(elctx, name);
            }

            throw methodNotFound(elctx, base, name, null);
        }

        public Object invoke(EvaluationContext context, Closure[] args) {
            ELContext elctx = context.getELContext();
            Object base = right.getValue(context);
            Object property = index.getValue(context);

            if (base == null)
                return null;
            if (property == null)
                throw methodNotFound(elctx, base, property, null);

            String name = coerceToString(property);

            if (base == GlobalScope.SINGLETON) {
                Object target = getValue(context);
                try {
                    return ELEngine.invokeTarget(elctx, target, args);
                } catch (MethodNotFoundException ex) {
                    throw methodNotFound(elctx, target, name, ex);
                } catch (RuntimeException ex) {
                    throw runtimeError(elctx, ex);
                }
            }
            
            // Invoke on closure object
            if (base instanceof ClosureObject) {
                Object result = ((ClosureObject)base).invoke(elctx, name, args);
                if (result != NO_RESULT) {
                    return result;
                }
            }

            // Resolve and invoke method closure
            if (!(base instanceof MethodDelegate)) {
                MethodResolver resolver = MethodResolver.getInstance(elctx);
                MethodClosure method;
                boolean usebase = false;

                if (base == SystemScope.SINGLETON) {
                    method = resolver.resolveSystemMethod(name);
                } else if (base instanceof Class) {
                    method = resolver.resolveStaticMethod((Class)base, name);
                    if (method == null) {
                        method = resolver.resolveMethod((Class)base, name);
                        if (method == null) {
                            method = resolver.resolveMethod(Class.class, name);
                            usebase = true;
                        }
                    }
                } else {
                    method = resolver.resolveMethod(base.getClass(), name);
                    usebase = true;
                }

                if (method != null) {
                    try {
                        if (usebase) {
                            return method.invoke(elctx, base, args);
                        } else {
                            return method.invoke(elctx, args);
                        }
                    } catch (RuntimeException ex) {
                        throw runtimeError(elctx, ex);
                    }
                }
            }

            // Invoke dynamic object method
            if (base instanceof MethodResolvable) {
                return ((MethodResolvable)base).invoke(elctx, name, args);
            }

            // Invoke on global closure object
            if (!(base instanceof ClosureObject)) {
                Object target = resolveGlobalMethod(elctx, name);
                if (target != null) {
                    try {
                        Closure[] callArgs = new Closure[args.length + 1];
                        callArgs[0] = new LiteralClosure(base);
                        System.arraycopy(args, 0, callArgs, 1, args.length);
                        return ELEngine.invokeTarget(elctx, target, callArgs);
                    } catch (MethodNotFoundException ex) {
                        throw methodNotFound(elctx, target, name, ex);
                    } catch (RuntimeException ex) {
                        throw runtimeError(elctx, ex);
                    }
                }
            }

            throw methodNotFound(elctx, base, name, null);
        }

        private Closure resolveMethod(ELContext elctx, Object base, String name) {
            MethodResolver resolver = MethodResolver.getInstance(elctx);
            if (base == SystemScope.SINGLETON) {
                return resolver.resolveSystemMethod(name);
            } else if (base instanceof Class) {
                MethodClosure c = resolver.resolveStaticMethod((Class)base, name);
                if (c != null) return c;
                c = resolver.resolveMethod((Class)base, name);
                if (c != null) return c;
                c = resolver.resolveMethod(Class.class, name);
                return (c == null) ? null : new TargetMethodClosure(base, c);
            } else {
                MethodClosure c = resolver.resolveMethod(base.getClass(), name);
                return c == null ? null : new TargetMethodClosure(base, c);
            }
        }

        private Closure resolveGlobalMethod(ELContext elctx, String name) {
            ValueExpression expr = elctx.getVariableMapper().resolveVariable(name);
            if (expr != null && expr instanceof Closure) {
                return (Closure)expr;
            }
            return MethodResolver.getInstance(elctx).resolveGlobalMethod(name);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Function invocation expression.
     */
    public static class APPLY extends Unary {
        public final ELNode[] args;
        public final String[] keys;

        public APPLY(int pos, ELNode right, ELNode arg) {
            this(pos, right, new ELNode[]{arg}, null);
        }

        public APPLY(int pos, ELNode right, ELNode[] args, String[] keys) {
            super(Token.APPLY, pos, right);
            this.args = args;
            this.keys = keys;
        }

        public int precedence() {
            return POSTFIX_PREC;
        }

        public Object getValue(EvaluationContext context) {
            return right.invoke(context, getCallArgs(context));
        }

        public Class getType(EvaluationContext context) {
            return right.getMethodInfo(context).getReturnType();
        }

        public MethodInfo getMethodInfo(EvaluationContext context) {
            return right.getMethodInfo(context);
        }

        public Object invokeMethod(EvaluationContext context, Object[] args) {
            // This method is called by MethodExpression, concatenate external
            // arguments with our own parameters and invoke closure.
            Closure[] extra = ELEngine.getCallArgs(args, this.getCallArgs(context));
            return right.invoke(context, extra);
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            if (args == null) {
                return right.invokeTail(context, call, getCallArgs(context));
            } else {
                return super.invokeTail(context, call, args);
            }
        }

        private Closure[] getCallArgs(EvaluationContext context) {
            if (args.length == 0) {
                return NO_PARAMS;
            }

            Closure[] extra = new Closure[args.length];
            for (int i = 0; i < extra.length; i++) {
                extra[i] = args[i].closure(context);
            }

            if (keys != null) {
                for (int i = 0; i < extra.length; i++) {
                    if (keys[i] != null) {
                        extra[i] = new NamedClosure(keys[i], extra[i]);
                    }
                }
            }

            return extra;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The transform expression (x->f). A syntax sugar for lambda application (f(x)).
     */
    public static class XFORM extends Binary {
        public XFORM(int pos, ELNode left, ELNode right) {
            super(Token.XFORM, pos, left, right);
        }

        public int precedence() {
            return XFORM_PREC;
        }

        public Object getValue(EvaluationContext context) {
            if (left instanceof TUPLE) {
                return right.invoke(context, getCallArgs(context));
            }

            ELContext elctx = context.getELContext();
            Object lhs = left.getValue(context);

            if (lhs instanceof ClosureObject) {
                // invoke operator procedure on closure object
                Closure[] args = {right.closure(context)};
                Object result = ((ClosureObject)lhs).invokeSpecial(elctx, "->", args);
                if (result != NO_RESULT) {
                    return result;
                }
            } else if (lhs != null) {
                // invoke expando operator procedure
                MethodClosure method = MethodResolver.getInstance(elctx)
                    .resolveMethod(lhs.getClass(), "->");
                if (method != null) {
                    Closure[] args = {right.closure(context)};
                    return method.invoke(elctx, lhs, args);
                }
            }

            return right.invoke(context, new Closure[]{new LiteralClosure(lhs)});
        }
        
        public Class getType(EvaluationContext context) {
            return right.getMethodInfo(context).getReturnType();
        }

        public MethodInfo getMethodInfo(EvaluationContext context) {
            return right.getMethodInfo(context);
        }

        public Object invokeMethod(EvaluationContext context, Object[] args) {
            // This method is called by MethodExpression, concatenate external
            // arguments with our own parameters and invoke closure.
            Closure[] extra = ELEngine.getCallArgs(args, this.getCallArgs(context));
            return right.invoke(context, extra);
        }

        private Closure[] getCallArgs(EvaluationContext context) {
            if (left instanceof TUPLE) {
                // for the expression (a,b,c)->f, translate to f(a,b,c)
                TUPLE a = (TUPLE)left;
                Closure[] args = new Closure[a.elems.length];
                for (int i = 0; i < args.length; i++) {
                    args[i] = a.elems[i].closure(context);
                }
                return args;
            } else {
                return new Closure[] { left.closure(context) };
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    // optimize for closure array allocation
    private static final AtomicReference<Closure[]> op_args = new AtomicReference<Closure[]>();
    private static final AtomicReference<Closure[]> op_args2 = new AtomicReference<Closure[]>();

    static Closure[] getArgs(Object arg) {
        Closure[] args = op_args.getAndSet(null);
        if (args == null)
            args = new Closure[1];
        args[0] = new LiteralClosure(arg);
        return args;
    }

    static Closure[] getArgs(Object arg1, Object arg2) {
        Closure[] args = op_args2.getAndSet(null);
        if (args == null)
            args = new Closure[2];
        args[0] = new LiteralClosure(arg1);
        args[1] = new LiteralClosure(arg2);
        return args;
    }

    static void releaseArgs(Closure[] args) {
        ((args.length == 1) ? op_args : op_args2).set(args);
    }

    /**
     * Unary expression.
     */
    public static abstract class Unary extends ELNode {
        public ELNode right;

        Unary(int op, int pos, ELNode right) {
            super(op, pos);
            this.right = right;
        }

        /**
         * Order the expression based on precedence.
         */
        public ELNode order() {
            if (precedence() > right.precedence()) {
                Unary e = (Unary)right;
                right = e.right;
                e.right = order();
                return e;
            }
            return this;
        }

        public Object getValue(EvaluationContext context) {
            return getValue(context.getELContext(), right.getValue(context));
        }

        public Object getValue(ELContext elctx, Object rhs) {
            // invoke operator procedure
            String opname = opIdentifiers[op];
            if (rhs != null && opname != null) {
                Object result = invokeOperator(elctx, opname, rhs);
                if (result != NO_RESULT) {
                    return result;
                }
            }

            // do standard evaluation
            try {
                return evaluate(elctx, rhs);
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }
        }

        protected Object invokeOperator(ELContext elctx, String opname, Object rhs) {
            if (rhs instanceof ClosureObject) {
                // invoke operator procedure on closure object
                return ((ClosureObject)rhs).invokeSpecial(elctx, opname, NO_PARAMS);
            } else {
                // invoke builtin operator procedure
                MethodClosure method = MethodResolver.getInstance(elctx)
                    .resolveMethod(rhs.getClass(), opname);
                if (method != null) {
                    return method.invoke(elctx, rhs, NO_PARAMS);
                }
            }
            return NO_RESULT;
        }

        protected Object evaluate(ELContext elctx, Object right) {
            throw new AssertionError();
        }
    }

    /**
     * Binary expression.
     */
    public static abstract class Binary extends Unary {
        public ELNode left;

        Binary(int op, int pos, ELNode left, ELNode right) {
            super(op, pos, right);
            this.left = left;
        }

        /**
         * Order the expression based on precedence.
         */
        public ELNode order() {
            return left_order();
        }

        protected ELNode left_order() {
            if (precedence() > left.precedence()) {
                Unary e = (Unary)left;
                left = e.right;
                e.right = order();
                return e;
            }
            return this;
        }

        protected ELNode right_order() {
            if (precedence() >= left.precedence()) {
                Unary e = (Unary)left;
                left = e.right;
                e.right = order();
                return e;
            }
            return this;
        }

        public Object getValue(EvaluationContext context) {
            return getValue(context.getELContext(), left.getValue(context), right.getValue(context));
        }

        public Object getValue(ELContext elctx, Object lhs, Object rhs) {
            // invoke operator procedure
            String opname = opIdentifiers[op];
            if (opname != null) {
                Object result = invokeOperator(elctx, opname, lhs, rhs);
                if (result != NO_RESULT) {
                    return result;
                }
            }

            // do standard evaluation
            try {
                return evaluate(elctx, lhs, rhs);
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }
        }

        protected Object invokeOperator(ELContext elctx, String opname, Object lhs, Object rhs) {
            Closure[] args;
            Object result;

            if (lhs != null) {
                if (lhs instanceof ClosureObject) {
                    // invoke static operator procedure
                    ClassDefinition cdef = ((ClosureObject)lhs).get_class();
                    Closure cvar = cdef.getClosure(elctx, opname);
                    if (cvar != null) {
                        args = getArgs(lhs, rhs);
                        result = cdef.invokeInScope(elctx, cvar, args);
                        releaseArgs(args);
                        return result;
                    }

                    // invoke operator procedure on closure object
                    args = getArgs(rhs);
                    result = ((ClosureObject)lhs).invokeSpecial(elctx, opname, args);
                    releaseArgs(args);
                    if (result != NO_RESULT) {
                        return result;
                    }
                } else {
                    MethodResolver resolver = MethodResolver.getInstance(elctx);
                    MethodClosure method;

                    // invoke static operator procedure
                    method = resolver.resolveStaticMethod(lhs.getClass(), opname);
                    if (method != null) {
                        args = getArgs(lhs, rhs);
                        result = method.invoke(elctx, args);
                        releaseArgs(args);
                        return result;
                    }

                    // invoke expando operator procedure
                    method = resolver.resolveMethod(lhs.getClass(), opname);
                    if (method != null) {
                        args = getArgs(rhs);
                        result = method.invoke(elctx, lhs, args);
                        releaseArgs(args);
                        return result;
                    }
                }
            }

            // reverse operator resolution
            if (rhs != null) {
                if (rhs instanceof ClosureObject) {
                    // invoke static operator procedure
                    ClassDefinition cdef = ((ClosureObject)rhs).get_class();
                    Closure cvar = cdef.getClosure(elctx, opname);
                    if (cvar != null) {
                        args = getArgs(lhs, rhs);
                        result = cdef.invokeInScope(elctx, cvar, args);
                        releaseArgs(args);
                        return result;
                    }

                    // invoke reverse operator procedure on closure object
                    args = getArgs(lhs);
                    result = ((ClosureObject)rhs).invokeSpecial(elctx, "?".concat(opname), args);
                    releaseArgs(args);
                    if (result != NO_RESULT) {
                        return result;
                    }
                } else {
                    MethodResolver resolver = MethodResolver.getInstance(elctx);
                    MethodClosure method;

                    // invoke static operator procedure
                    method = resolver.resolveStaticMethod(rhs.getClass(), opname);
                    if (method != null) {
                        args = getArgs(lhs, rhs);
                        result = method.invoke(elctx, args);
                        releaseArgs(args);
                        return result;
                    }

                    // invoke expando reverse operator procedure
                    method = resolver.resolveMethod(rhs.getClass(), "?".concat(opname));
                    if (method != null) {
                        args = getArgs(lhs);
                        result = method.invoke(elctx, rhs, args);
                        releaseArgs(args);
                        return result;
                    }
                }
            }

            return NO_RESULT;
        }

        protected Object assignop(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object lhs = left.getValue(context);
            Object rhs = right.getValue(context);
            String opname = opIdentifiers[op];
            Closure[] args;
            Object result;

            // invoke assignment operator procedure
            if (lhs != null && opname != null) {
                if (lhs instanceof ClosureObject) {
                    args = getArgs(rhs);
                    result = ((ClosureObject)lhs).invokeSpecial(elctx, opname.concat("="), args);
                    releaseArgs(args);
                    if (result != NO_RESULT) {
                        return result;
                    }
                } else if (!(lhs instanceof Number)) {
                    MethodClosure method = MethodResolver.getInstance(elctx)
                        .resolveMethod(lhs.getClass(), opname.concat("="));
                    if (method != null) {
                        args = getArgs(rhs);
                        result = method.invoke(elctx, lhs, args);
                        releaseArgs(args);
                        return result;
                    }
                }
            }

            // do standard evaluation and followed by assignment
            Object value = getValue(elctx, lhs, rhs);
            left.setValue(context, value);
            return value;
        }

        protected Object evaluate(ELContext elctx, Object x, Object y) {
            throw new AssertionError();
        }
    }

    /**
     * User defined prefix operator.
     */
    public static class PREFIX extends Unary {
        public final String name;
        public final int prec;

        public PREFIX(int pos, String name, int prec, ELNode right) {
            super(Token.PREFIX, pos, right);
            this.name = name;
            this.prec = prec;
        }

        public int precedence() {
            return prec;
        }

        public Object getValue(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object rhs = right.getValue(context);
            Object result;

            // invoke operator procedure
            if (rhs != null) {
                result = invokeOperator(elctx, name, rhs);
                if (result != NO_RESULT) {
                    return result;
                }
            }

            // invoke target procedure
            Object target = resolveTarget(context, name);
            if (target == null) {
                throw runtimeError(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
            }

            try {
                Closure[] args = getArgs(rhs);
                result = ELEngine.invokeTarget(elctx, target, args);
                releaseArgs(args);
                return result;
            } catch (MethodNotFoundException ex) {
                throw methodNotFound(elctx, target, name, ex);
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }
        }

        public Class getType(EvaluationContext context) {
            return null; // FIXME
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * User defined infix operator.
     */
    public static class INFIX extends Binary {
        public final String name;
        public final int prec;

        public INFIX(int pos, String name, int prec, ELNode left, ELNode right) {
            super(Token.INFIX, pos, left, right);
            this.name = name;
            this.prec = prec;
        }

        public int precedence() {
            return (prec >= 0) ? prec : -prec;
        }

        public ELNode order() {
            return (prec >= 0) ? left_order() : right_order();
        }

        public Object getValue(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object lhs = left.getValue(context);
            Object rhs = right.getValue(context);
            Object result;

            // invoke operator procedure
            result = invokeOperator(elctx, name, lhs, rhs);
            if (result != NO_RESULT) {
                return result;
            }

            // invoke target procedure
            Object target = resolveTarget(context, name);
            if (target == null) {
                throw runtimeError(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
            }

            try {
                Closure[] args = getArgs(lhs, rhs);
                result = ELEngine.invokeTarget(elctx, target, args);
                return result;
            } catch (MethodNotFoundException ex) {
                throw methodNotFound(elctx, target, name, ex);
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The expression sequential operator.
     */
    public static class THEN extends Binary {
        public THEN(int pos, ELNode left, ELNode right) {
            super(Token.THEN, pos, left, right);
        }

        public int precedence() {
            return THEN_PREC;
        }

        public ELNode order() {
            return right_order();
        }

        public Object getValue(EvaluationContext context) {
            left.getValue(context);
            return right.getValue(context);
        }

        public Class getType(EvaluationContext context) {
            return right.getType(context);
        }

        public MethodInfo getMethodInfo(EvaluationContext context) {
            return right.getMethodInfo(context);
        }

        public Object invoke(EvaluationContext context, Closure[] args) {
            Frame f = context.getFrame();
            left.getValue(context);
            return right.pos(f).invoke(context, args);
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] params) {
            Frame f = context.getFrame();
            left.getValue(context);
            return right.pos(f).invokeTail(context, call, params);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Assignment expression.
     */
    public static class ASSIGN extends Binary {
        public ASSIGN(int pos, ELNode left, ELNode right) {
            super(Token.ASSIGN, pos, left, right);
        }

        public int precedence() {
            return ASSIGN_PREC;
        }

        public ELNode order() {
            return right_order();
        }
        
        public Object getValue(EvaluationContext context) {
            // optimize
            if (left.op == Token.TUPLE && right.op == Token.TUPLE) {
                return ((TUPLE)left).copyValue(context, ((TUPLE)right).elems);
            }

            Object value = right.getValue(context);
            left.setValue(context, value);
            return value;
        }

        public Class getType(EvaluationContext context) {
            return left.getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class ASSIGNOP extends ASSIGN {
        public final Binary binary; // the shadow node to perform actual operation

        public ASSIGNOP(int pos, Binary binary) {
            super(pos, binary.left, binary.right);
            this.binary = binary;
        }

        public int precedence() {
            return ASSIGNOP_PREC;
        }

        public Object getValue(EvaluationContext context) {
            // must have same order as assign operator
            binary.left = left;
            binary.right = right;

            return binary.assignop(context);
        }
    }

    /**
     * Conditional expression.
     */
    public static class COND extends Binary {
        public ELNode cond;

        public COND(int pos, ELNode cond, ELNode left, ELNode right) {
            super(Token.COND, pos, left, right);
            this.cond = cond;
        }

        public int precedence() {
            return COND_PREC;
        }

        /**
         * Order the expression based on precedence.
         */
        public ELNode order() {
            if (precedence() > cond.precedence()) {
                Unary e = (Unary)cond;
                cond = e.right;
                e.right = order();
                return e;
            }
            return this;
        }

        public Object getValue(EvaluationContext context) {
            Frame f = context.getFrame();
            if (cond.pos(f).getBoolean(context)) {
                return left.pos(f).getValue(context);
            } else {
                return right.pos(f).getValue(context);
            }
        }

        public Class getType(EvaluationContext context) {
            Frame f = context.getFrame();
            if (cond.pos(f).getBoolean(context)) {
                return left.pos(f).getType(context);
            } else {
                return right.pos(f).getType(context);
            }
        }

        public MethodInfo getMethodInfo(EvaluationContext context) {
            Frame f = context.getFrame();
            if (cond.pos(f).getBoolean(context)) {
                return left.pos(f).getMethodInfo(context);
            } else {
                return right.pos(f).getMethodInfo(context);
            }
        }

        public Object invoke(EvaluationContext context, Closure[] args) {
            Frame f = context.getFrame();
            if (cond.pos(f).getBoolean(context)) {
                return left.pos(f).invoke(context, args);
            } else {
                return right.pos(f).invoke(context, args);
            }
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] params) {
            Frame f = context.getFrame();
            if (cond.pos(f).getBoolean(context)) {
                return left.pos(f).invokeTail(context, call, params);
            } else {
                return right.pos(f).invokeTail(context, call, params);
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Coalescing operation.
     */
    public static class COALESCE extends Binary {
        public COALESCE(int pos, ELNode left, ELNode right) {
            super(Token.COALESCE, pos, left, right);
        }

        public int precedence() {
            return COALESCE_PREC;
        }

        public Object getValue(EvaluationContext context) {
            Object value = left.getValue(context);
            if (value == null) {
                value = right.getValue(context);
            }
            return value;
        }

        public Class getType(EvaluationContext context) {
            return right.getType(context);
        }

        protected Object assignop(EvaluationContext context) {
            Object value = left.getValue(context);
            if (value == null) {
                value = right.getValue(context);
                left.setValue(context, value);
            }
            return value;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Safe reference operation.
     */
    public static class SAFEREF extends Binary {
        public SAFEREF(int pos, ELNode left, ELNode right) {
            super(Token.SAFEREF, pos, left, right);
        }

        public int precedence() {
            return COALESCE_PREC;
        }

        // right associative
        public ELNode order() {
            return right_order();
        }

        public Object getValue(EvaluationContext context) {
            Object value = getLeftValue(context);
            if (value == null)
                value = right.getValue(context);
            return value;
        }

        private Object getLeftValue(EvaluationContext context) {
            if (left.op == Token.IDENT) {
                ELContext elctx = context.getELContext();
                String id = ((IDENT)left).id;

                ValueExpression expr = context.resolveVariable(id);
                if (expr != null) {
                    return expr.getValue(elctx);
                }

                elctx.setPropertyResolved(false);
                Object value = elctx.getELResolver().getValue(elctx, null, id);
                return elctx.isPropertyResolved() ? value : null;
            } else {
                return left.getValue(context);
            }
        }

        public Class getType(EvaluationContext context) {
            return right.getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * OR expression.
     */
    public static class OR extends Binary implements Pattern {
        public OR(int pos, ELNode left, ELNode right) {
            super(Token.OR, pos, left, right);
        }

        public int precedence() {
            return OR_PREC;
        }

        public Object getValue(EvaluationContext context) {
            return left.getBoolean(context) || right.getBoolean(context);
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        public boolean matches(final EvaluationContext context, Object value) {
            // Create a temporary variable mapper for variable pattern matching.
            // The variable is first resolved in local variable mapper then
            // resolve it from enclosing environment if not found locally.
            // In case of a failed match the variable binding is automatically
            // recovered.
            VariableMapperImpl vm = new VariableMapperImpl() {
                public ValueExpression resolveVariable(String name) {
                    ValueExpression ve = super.resolveVariable(name);
                    if (ve == null)
                        ve = context.resolveLocalVariable(name);
                    return ve;
                }
            };

            EvaluationContext env = context.pushContext(vm);
            Map<String,ValueExpression> map = vm.getVariableMap();

            // Matches for pattern branches.
            if (try_match(env, map, value)) {
                // copy matched variables into enclosing environment.
                for (Map.Entry<String,ValueExpression> e : map.entrySet())
                    context.setVariable(e.getKey(), e.getValue());
                return true;
            } else {
                return false;
            }
        }

        boolean try_match(EvaluationContext env, Map<String,ValueExpression> map, Object value) {
            boolean matched;

            if (left instanceof OR) {
                matched = ((OR)left).try_match(env, map, value);
            } else {
                matched = ((Pattern)left).matches(env, value);
            }

            if (!matched) {
                if (map != null) map.clear();
                if (right instanceof OR) {
                    matched = ((OR)right).try_match(env, map, value);
                } else {
                    matched = ((Pattern)right).matches(env, value);
                }
            }

            return matched;
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            if (left.getBoolean(context)) {
                call.result = Boolean.TRUE;
                return false;
            } else {
                return right.invokeTail(context, call, args);
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * AND expression.
     */
    public static class AND extends Binary {
        public AND(int pos, ELNode left, ELNode right) {
            super(Token.AND, pos, left, right);
        }

        public int precedence() {
            return AND_PREC;
        }

        public Object getValue(EvaluationContext context) {
            return left.getBoolean(context) && right.getBoolean(context);
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            if (left.getBoolean(context)) {
                return right.invokeTail(context, call, args);
            } else {
                call.result = Boolean.FALSE;
                return false;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Bitwise operators.
     */
    public static abstract class Bitwise extends Binary {
        Bitwise(int op, int pos, ELNode left, ELNode right) {
            super(op, pos, left, right);
        }

        public Class getType(EvaluationContext context) {
            return Number.class;
        }

        protected abstract BigInteger eval(BigInteger x, BigInteger y);
        protected abstract long eval(long x, long y);
        protected abstract boolean eval(boolean x, boolean y);

        protected Object evaluate(ELContext elctx, Object x, Object y) {
            if (x == null || y == null) {
                return 0;
            }

            if ((x instanceof BigInteger) || (x instanceof BigDecimal) ||
                (y instanceof BigInteger) || (y instanceof BigDecimal)) {
                return eval(coerceToBigInteger(x), coerceToBigInteger(y));
            } else if ((x instanceof Boolean) || (y instanceof Boolean)) {
                return eval(coerceToBoolean(x), coerceToBoolean(y));
            } else {
                return eval(coerceToLong(x), coerceToLong(y));
            }
        }
    }

    public static class BITOR extends Bitwise {
        public BITOR(int pos, ELNode left, ELNode right) {
            super(Token.BITOR, pos, left, right);
        }

        public int precedence() {
            return BITOR_PREC;
        }

        protected BigInteger eval(BigInteger x, BigInteger y) {
            return x.or(y);
        }

        protected long eval(long x, long y) {
            return x | y;
        }

        protected boolean eval(boolean x, boolean y) {
            return x | y;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class BITAND extends Bitwise {
        public BITAND(int pos, ELNode left, ELNode right) {
            super(Token.BITAND, pos, left, right);
        }

        public int precedence() {
            return BITAND_PREC;
        }

        protected BigInteger eval(BigInteger x, BigInteger y) {
            return x.and(y);
        }

        protected long eval(long x, long y) {
            return x & y;
        }

        protected boolean eval(boolean x, boolean y) {
            return x & y;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class XOR extends Bitwise {
        public XOR(int pos, ELNode left, ELNode right) {
            super(Token.XOR, pos, left, right);
        }

        public int precedence() {
            return XOR_PREC;
        }

        protected BigInteger eval(BigInteger x, BigInteger y) {
            return x.xor(y);
        }

        protected long eval(long x, long y) {
            return x ^ y;
        }

        protected boolean eval(boolean x, boolean y) {
            return x ^ y;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Bitwise shift operator.
     */
    public static abstract class BitwiseShift extends Binary {
        BitwiseShift(int op, int pos, ELNode left, ELNode right) {
            super(op, pos, left, right);
        }

        public int precedence() {
            return SHIFT_PREC;
        }

        public Class getType(EvaluationContext context) {
            return Number.class;
        }

        protected abstract BigInteger eval(BigInteger z, int n);
        protected abstract long eval(long z, int n);

        protected Object evaluate(ELContext elctx, Object z, Object n) {
            if (z == null || n == null) {
                return 0;
            }

            if (z instanceof BigInteger) {
                return eval(coerceToBigInteger(z), coerceToInt(n));
            } else {
                return eval(coerceToLong(z), coerceToInt(n));
            }
        }
    }

    public static class SHL extends BitwiseShift {
        public SHL(int pos, ELNode left, ELNode right) {
            super(Token.SHL, pos, left, right);
        }

        protected BigInteger eval(BigInteger z, int n) {
            return z.shiftLeft(n);
        }

        protected long eval(long z, int n) {
            return z << n;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class SHR extends BitwiseShift {
        public SHR(int pos, ELNode left, ELNode right) {
            super(Token.SHR, pos, left, right);
        }

        protected BigInteger eval(BigInteger z, int n) {
            return z.shiftRight(n);
        }

        protected long eval(long z, int n) {
            return z >> n;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class USHR extends BitwiseShift {
        public USHR(int pos, ELNode left, ELNode right) {
            super(Token.USHR, pos, left, right);
        }

        protected BigInteger eval(BigInteger z, int n) {
            return z.shiftRight(n);
        }

        protected long eval(long z, int n) {
            return z >>> n;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }
    
    /**
     * Binary equality expression.
     */
    public static abstract class Equality extends Binary {
        Equality(int op, int pos, ELNode left, ELNode right) {
            super(op, pos, left, right);
        }

        public int precedence() {
            return EQ_PREC;
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        protected abstract boolean eval(Number x, Number y);
        protected abstract boolean eval(Object x, Object y);

        protected Boolean evaluate(ELContext elctx, Object x, Object y) {
            if (x == y) {
                return op == Token.EQ;
            }

            if (x == null || y == null) {
                return op != Token.EQ;
            }

            if (x.getClass() == y.getClass()) {
                if (x instanceof Number) {
                    return eval((Number)x, (Number)y);
                } else if (x instanceof Object[]) {
                    return eval(elctx, (Object[])x, (Object[])y);
                } else {
                    return eval(x, y);
                }
            }

            if (x instanceof Number && y instanceof Number) {
                if (x instanceof ClosureObject) {
                    return eval(x, y);
                }
                
                if (x instanceof BigDecimal || y instanceof BigDecimal) {
                    return eval(coerceToBigDecimal(elctx, x), coerceToBigDecimal(elctx, y));
                }

                if (x instanceof Decimal || y instanceof Decimal) {
                    return eval(coerceToDecimal(x), coerceToDecimal(y));
                }

                if ((x instanceof Float || y instanceof Float) ||
                    (x instanceof Double || y instanceof Double)) {
                    return eval(coerceToDouble(x), coerceToDouble(y));
                }

                if (x instanceof Rational || y instanceof Rational) {
                    return eval(coerceToRational(x), coerceToRational(y));
                }

                if (x instanceof BigInteger || y instanceof BigInteger) {
                    return eval(coerceToBigInteger(x), coerceToBigInteger(y));
                }

                return eval(coerceToLong(x), coerceToLong(y));
            } else {
                if (x instanceof Boolean || y instanceof Boolean) {
                    return eval(coerceToBoolean(x), coerceToBoolean(y));
                }

                if (x instanceof Enum || y instanceof Enum) {
                    if (!(x instanceof Enum))
                        x = coerceToEnum(x, ((Enum<?>)y).getClass());
                    if (!(y instanceof Enum))
                        y = coerceToEnum(y, ((Enum<?>)x).getClass());
                    return eval(x, y);
                }

                if (x instanceof String || y instanceof String) {
                    return eval(x.toString(), y.toString());
                }

                if ((x instanceof Object[]) && (y instanceof Object[])) {
                    return eval(elctx, (Object[])x, (Object[])y);
                }

                if ((x instanceof Character) && (y instanceof Number) ||
                    (x instanceof Number) && (y instanceof Character)) {
                    return eval(coerceToCharacter(x), coerceToCharacter(y));
                }

                return eval(x, y);
            }
        }

        protected boolean eval(ELContext elctx, Object[] x, Object[] y) {
            int length = x.length;
            if (y.length != length) {
                return op != Token.EQ;
            }

            for (int i = 0; i < length; i++) {
                if (!evaluate(elctx, x[i], y[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    public static class EQ extends Equality {
        public EQ(int pos, ELNode left, ELNode right) {
            super(Token.EQ, pos, left, right);
        }

        protected boolean eval(Number x, Number y) {
            if (x instanceof Comparable) {
                return ((Comparable)x).compareTo(y) == 0;
            } else {
                return x.equals(y);
            }
        }

        protected boolean eval(Object x, Object y) {
            return x.equals(y);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }

        private static final EQ __eq__ = new EQ(-1, null, null);

        public static boolean equals(ELContext elctx, Object x, Object y) {
            return __eq__.evaluate(elctx, x, y);
        }
    }

    public static class NE extends Equality {
        public NE(int pos, ELNode left, ELNode right) {
            super(Token.NE, pos, left, right);
        }

        protected boolean eval(Number x, Number y) {
            if (x instanceof Comparable) {
                return ((Comparable)x).compareTo(y) != 0;
            } else {
                return !x.equals(y);
            }
        }

        protected boolean eval(Object x, Object y) {
            return !x.equals(y);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The identity equality expression.
     */
    public static class IDEQ extends Binary {
        public IDEQ(int pos, ELNode left, ELNode right) {
            super(Token.IDEQ, pos, left, right);
        }

        public int precedence() {
            return EQ_PREC;
        }

        protected Boolean evaluate(ELContext elctx, Object x, Object y) {
            return x == y;
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The identity equality expression.
     */
    public static class IDNE extends Binary {
        public IDNE(int pos, ELNode left, ELNode right) {
            super(Token.IDNE, pos, left, right);
        }

        public int precedence() {
            return EQ_PREC;
        }

        protected Boolean evaluate(ELContext elctx, Object x, Object y) {
            return x != y;
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Binary comparison expression.
     */
    public static abstract class Comparison extends Binary {
        Comparison(int op, int pos, ELNode left, ELNode right) {
            super(op, pos, left, right);
        }

        public int precedence() {
            return ORD_PREC;
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        protected abstract boolean eval(Comparable x, Comparable y);

        protected Boolean evaluate(ELContext elctx, Object x, Object y) {
            if (x == y) {
                return op == Token.LE || op == Token.GE;
            }

            if (x == null || y == null) {
                return false;
            }

            if (x.getClass() == y.getClass()) {
                if (x instanceof Comparable) {
                    return eval((Comparable)x, (Comparable)y);
                }
            }

            if (x instanceof BigDecimal || y instanceof BigDecimal) {
                return eval(coerceToBigDecimal(elctx, x), coerceToBigDecimal(elctx, y));
            }

            if (x instanceof Decimal || y instanceof Decimal) {
                return eval(coerceToDecimal(x), coerceToDecimal(y));
            }

            if ((x instanceof Float || y instanceof Float) ||
                (x instanceof Double || y instanceof Double)) {
                return eval(coerceToDouble(x), coerceToDouble(y));
            }

            if (x instanceof Rational || y instanceof Rational) {
                return eval(coerceToRational(x), coerceToRational(y));
            }

            if (x instanceof BigInteger || y instanceof BigInteger) {
                return eval(coerceToBigInteger(x), coerceToBigInteger(y));
            }

            if ((x instanceof Number) || (y instanceof Number)) {
                return eval(coerceToLong(x), coerceToLong(y));
            }

            if (x instanceof String || y instanceof String) {
                return eval(x.toString(), y.toString());
            }

            if (x instanceof Comparable && y instanceof Comparable) {
                return eval((Comparable)x, (Comparable)y);
            }

            throw new ELException(_T(JSPRT_UNSUPPORTED_EVAL_TYPE, x.getClass().getName()));
        }
    }

    /**
     * Less than comparison expression.
     */
    public static class LT extends Comparison {
        public LT(int pos, ELNode left, ELNode right) {
            super(Token.LT, pos, left, right);
        }

        protected boolean eval(Comparable x, Comparable y) {
            return x.compareTo(y) < 0;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Less or equal than comparison expression
     */
    public static class LE extends Comparison {
        public LE(int pos, ELNode left, ELNode right) {
            super(Token.LE, pos, left, right);
        }

        protected boolean eval(Comparable x, Comparable y) {
            return x.compareTo(y) <= 0;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Greater than comparison expression.
     */
    public static class GT extends Comparison {
        public GT(int pos, ELNode left, ELNode right) {
            super(Token.GT, pos, left, right);
        }

        protected boolean eval(Comparable x, Comparable y) {
            return x.compareTo(y) > 0;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Greater than or equal to comparison expression.
     */
    public static class GE extends Comparison {
        public GE(int pos, ELNode left, ELNode right) {
            super(Token.GE, pos, left, right);
        }

        protected boolean eval(Comparable x, Comparable y) {
            return x.compareTo(y) >= 0;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * INSTANCEOF expression.
     */
    public static class INSTANCEOF extends Unary {
        public final String type;
        public final boolean negative;

        public int precedence() {
            return ORD_PREC;
        }

        public INSTANCEOF(int pos, ELNode right, String type, boolean negative) {
            super(Token.INSTANCEOF, pos, right);
            this.type = type;
            this.negative = negative;
        }

        public Object getValue(EvaluationContext context) {
            Object value = right.getValue(context);
            boolean check = TypedClosure.typecheck(context, type, value);
            return check ^ negative;
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * IN expression.
     */
    public static class IN extends Binary {
        public final boolean negative;

        public IN(int pos, ELNode left, ELNode right, boolean negative) {
            super(Token.IN, pos, left, right);
            this.negative = negative;
        }

        public int precedence() {
            return ORD_PREC;
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        public Object getValue(EvaluationContext context) {
            try {
                return eval(context.getELContext(),
                            left.getValue(context),
                            right.getValue(context)) ^ negative;
            } catch (RuntimeException ex) {
                throw runtimeError(context.getELContext(), ex);
            }
        }

        private boolean eval(ELContext elctx, Object x, Object y) {
            if (x == null || y == null) {
                return false;
            }

            if (y instanceof Object[]) {
                Object[] a = (Object[])y;
                int length = a.length;
                for (int i = 0; i < length; i++) {
                    if (EQ.equals(elctx, x, a[i])) {
                        return true;
                    }
                }
                return false;
            }

            if (y.getClass().isArray()) {
                x = coerce(x, y.getClass().getComponentType());
                int length = Array.getLength(y);
                for (int i = 0; i < length; i++) {
                    if (EQ.equals(elctx, x, Array.get(y, i))) {
                        return true;
                    }
                }
                return false;
            }

            if (y instanceof Collection) {
                if (x instanceof Collection) {
                    return ((Collection)y).containsAll((Collection)x);
                } else if (y instanceof Set) {
                    return ((Set)y).contains(x);
                } else if (y instanceof Range) {
                    return ((List)y).contains(x);
                } else if (y instanceof Seq) {
                    Seq seq = (Seq)y;
                    while (!seq.isEmpty()) {
                        if (EQ.equals(elctx, x, seq.head()))
                            return true;
                        seq = seq.tail();
                    }
                } else {
                    for (Object e : (Collection)y) {
                        if (EQ.equals(elctx, x, e))
                            return true;
                    }
                }
            }

            return false;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    private static final long LONG_SIG_BIT = (1L << 63);
    private static final int INT_SIG_BIT = (1 << 31);

    /**
     * Binary arithmetic expression.
     */
    public static abstract class Arithmetic extends Binary {
        public Arithmetic(int op, int pos, ELNode left, ELNode right) {
            super(op, pos, left, right);
        }

        public Class getType(EvaluationContext context) {
            return Number.class;
        }

        protected abstract Number eval(ELContext elctx, BigDecimal x, BigDecimal y);
        protected abstract Number eval(ELContext elctx, BigInteger x, BigInteger y);
        protected abstract Number eval(ELContext elctx, Decimal x, Decimal y);
        protected abstract Number eval(ELContext elctx, Rational x, Rational y);
        protected abstract Number eval(ELContext elctx, long x, long y);
        protected abstract Number eval(ELContext elctx, int x, int y);
        protected abstract Number eval(ELContext elctx, double x, double y);

        protected Object evaluate(ELContext elctx, Object x, Object y) {
            if (x == null || y == null) {
                return 0;
            }

            if (x.getClass() == y.getClass()) {
                if (x instanceof Long) {
                    return eval(elctx, ((Long)x).longValue(), ((Long)y).longValue());
                } else if (x instanceof Integer) {
                    return eval(elctx, ((Integer)x).intValue(), ((Integer)y).intValue());
                } else if (x instanceof Double) {
                    return eval(elctx, ((Double)x).doubleValue(), ((Double)y).doubleValue());
                } else if (x instanceof Float) {
                    return eval(elctx, ((Float)x).doubleValue(), ((Float)y).doubleValue());
                } else if (x instanceof Short) {
                    return eval(elctx, ((Short)x).longValue(), ((Short)y).longValue());
                } else if (x instanceof Byte) {
                    return eval(elctx, ((Byte)x).longValue(), ((Byte)y).longValue());
                } else if (x instanceof Decimal) {
                    return eval(elctx, (Decimal)x, (Decimal)y);
                } else if (x instanceof Rational) {
                    return eval(elctx, (Rational)x, (Rational)y);
                } else if (x instanceof BigInteger) {
                    return eval(elctx, (BigInteger)x, (BigInteger)y);
                } else if (x instanceof BigDecimal) {
                    return eval(elctx, (BigDecimal)x, (BigDecimal)y);
                }
            }

            if (x instanceof BigDecimal || y instanceof BigDecimal) {
                return eval(elctx, coerceToBigDecimal(elctx, x), coerceToBigDecimal(elctx, y));
            }

            if (x instanceof Decimal || y instanceof Decimal) {
                return eval(elctx, coerceToDecimal(x), coerceToDecimal(y));
            }

            if ((x instanceof Float || x instanceof Double) ||
                (y instanceof Float || y instanceof Double) ||
                looksLikeFloat(x) || looksLikeFloat(y)) {
                return eval(elctx, coerceToDouble(x), coerceToDouble(y));
            }

            if (x instanceof Rational || y instanceof Rational) {
                return eval(elctx, coerceToRational(x), coerceToRational(y));
            }

            if (x instanceof BigInteger || y instanceof BigInteger) {
                return eval(elctx, coerceToBigInteger(x), coerceToBigInteger(y));
            }

            if (x instanceof Long || y instanceof Long) {
                return eval(elctx, coerceToLong(x), coerceToLong(y));
            }

            return eval(elctx, coerceToInt(x), coerceToInt(y));
        }
    }

    /**
     * Concatenation expression.
     */
    public static class CAT extends Binary {
        public CAT(int pos, ELNode left, ELNode right) {
            super(Token.CAT, pos, left, right);
        }

        public int precedence() {
            return SHIFT_PREC;
        }

        protected Object evaluate(ELContext elctx, Object x, Object y) {
            if (x instanceof Closure && y instanceof Closure) {
                return ((Closure)x).compose((Closure)y);
            }

            if (x instanceof Seq) {
                if (y instanceof Collection) {
                    return ((Seq)x).append(coerceToSeq(y));
                } else {
                    return ((Seq)x).append(Cons.make(y));
                }
            }

            if (x instanceof Collection) {
                List result = new ArrayList((Collection)x);
                if (y instanceof Collection) {
                    result.addAll((Collection)y);
                } else {
                    result.add(y);
                }
                return result;
            }

            if (x.getClass().isArray()) {
                Class t = x.getClass();
                Class c = t.getComponentType();
                int xlen = Array.getLength(x);

                if (y.getClass() == t) {
                    // concatenate arraies with same type
                    int ylen = Array.getLength(y);
                    Object a = Array.newInstance(c, xlen + ylen);
                    System.arraycopy(x, 0, a, 0, xlen);
                    System.arraycopy(y, 0, a, xlen, ylen);
                    return a;
                }
                else if (y.getClass().isArray()) {
                    // concatenate arraies with different type
                    int ylen = Array.getLength(y);
                    Object[] a = new Object[xlen + ylen];
                    for (int i = 0; i < xlen; i++) a[i] = Array.get(x, i);
                    for (int i = 0; i < ylen; i++) a[xlen+i] = Array.get(y, i);
                    return a;
                }
                else {
                    // concatenate an array with an element
                    Object a = Array.newInstance(c, xlen+1);
                    System.arraycopy(x, 0, a, 0, xlen);
                    Array.set(a, xlen, coerce(elctx, y, c));
                    return a;
                }
            }

            if (y instanceof Seq) {
                return new Cons(x, (Seq)y);
            }

            if (y instanceof Collection) {
                List result = new ArrayList();
                result.add(x);
                result.addAll((Collection)y);
                return result;
            }

            if (y.getClass().isArray()) {
                Class c = y.getClass().getComponentType();
                int len = Array.getLength(y);
                Object a = Array.newInstance(c, len+1);
                Array.set(a, 0, coerce(elctx, x, c));
                System.arraycopy(y, 0, a, 1, len);
                return a;
            }

            return new StringBuilder().append(x).append(y).toString();
        }

        public Class getType(EvaluationContext context) {
            return left.getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Add expression.
     */
    public static class ADD extends Arithmetic {
        public ADD(int pos, ELNode left, ELNode right) {
            super(Token.ADD, pos, left, right);
        }

        public int precedence() {
            return ADD_PREC;
        }

        protected Object evaluate(ELContext elctx, Object x, Object y) {
            if ((x instanceof CharSequence) || (y instanceof CharSequence)) {
                if (looksLikeNumber(x) && looksLikeNumber(y)) {
                    return super.evaluate(elctx, x, y);
                } else {
                    return new StringBuilder().append(x).append(y).toString();
                }
            } else if ((x instanceof Character) && (y instanceof Integer)) {
                int z = (Character)x + (Integer)y;
                if (z >= Character.MIN_VALUE && z <= Character.MAX_VALUE) {
                    return (char)z;
                } else {
                    return z;
                }
            } else {
                return super.evaluate(elctx, x, y);
            }
        }

        protected Number eval(ELContext elctx, BigDecimal x, BigDecimal y) {
            MathContext mc = GlobalScope.getMathContext(elctx);
            return (mc == null) ? x.add(y) : x.add(y, mc);
        }

        protected Number eval(ELContext elctx, BigInteger x, BigInteger y) {
            return x.add(y);
        }

        protected Number eval(ELContext elctx, Decimal x, Decimal y) {
            return x.add(y);
        }

        protected Number eval(ELContext elctx, Rational x, Rational y) {
            return x.add(y).reduce();
        }

        protected Number eval(ELContext elctx, double x, double y) {
            return x + y;
        }

        protected Number eval(ELContext elctx, long x, long y) {
            long z = x + y;
            if ((~(x ^ y) & (x ^ z) & LONG_SIG_BIT) != 0) {
                return BigInteger.valueOf(x).add(BigInteger.valueOf(y));
            } else {
                return z;
            }
        }

        protected Number eval(ELContext elctx, int x, int y) {
            int z = x + y;
            if ((~(x ^ y) & (x ^ z) & INT_SIG_BIT) != 0) {
                return (long)x + (long)y;
            } else {
                return z;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Subtraction expression.
     */
    public static class SUB extends Arithmetic {
        public SUB(int pos, ELNode left, ELNode right) {
            super(Token.SUB, pos, left, right);
        }

        public int precedence() {
            return ADD_PREC;
        }

        protected Object evaluate(ELContext elctx, Object x, Object y) {
            if ((x instanceof Character) && (y instanceof Integer)) {
                int z = (Character)x - (Integer)y;
                if (z >= Character.MIN_VALUE && z <= Character.MAX_VALUE) {
                    return (char)z;
                } else {
                    return z;
                }
            } else {
                return super.evaluate(elctx, x, y);
            }
        }

        protected Number eval(ELContext elctx, BigDecimal x, BigDecimal y) {
            MathContext mc = GlobalScope.getMathContext(elctx);
            return (mc == null) ? x.subtract(y) : x.subtract(y);
        }

        protected Number eval(ELContext elctx, BigInteger x, BigInteger y) {
            return x.subtract(y);
        }

        protected Number eval(ELContext elctx, Decimal x, Decimal y) {
            return x.subtract(y);
        }

        protected Number eval(ELContext elctx, Rational x, Rational y) {
            return x.subtract(y).reduce();
        }

        protected Number eval(ELContext elctx, double x, double y) {
            return x - y;
        }

        protected Number eval(ELContext elctx, long x, long y) {
            long z = x - y;
            if ((~(x ^ ~y) & (x ^ z) & LONG_SIG_BIT) != 0) {
                return BigInteger.valueOf(x).subtract(BigInteger.valueOf(y));
            } else {
                return z;
            }
        }

        protected Number eval(ELContext elctx, int x, int y) {
            int z = x - y;
            if ((~(x ^ -y) & (x ^ z) & INT_SIG_BIT) != 0) {
                return (long)x - (long)y;
            } else {
                return z;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Multiplication expression.
     */
    public static class MUL extends Arithmetic {
        public MUL(int pos, ELNode left, ELNode right) {
            super(Token.MUL, pos, left, right);
        }

        public int precedence() {
            return MUL_PREC;
        }

        protected Number eval(ELContext elctx, BigDecimal x, BigDecimal y) {
            MathContext mc = GlobalScope.getMathContext(elctx);
            return (mc == null) ? x.multiply(y) : x.multiply(y, mc);
        }

        protected Number eval(ELContext elctx, BigInteger x, BigInteger y) {
            return x.multiply(y);
        }

        protected Number eval(ELContext elctx, Decimal x, Decimal y) {
            return x.multiply(y);
        }

        protected Number eval(ELContext elctx, Rational x, Rational y) {
            return x.multiply(y).reduce();
        }

        protected Number eval(ELContext elctx, double x, double y) {
            return x * y;
        }

        protected Number eval(ELContext elctx, long x, long y) {
            long z = x * y;
            if (y != 0L && z/y != x) {       // overflowed
                return BigInteger.valueOf(x).multiply(BigInteger.valueOf(y));
            } else {
                return z;
            }
        }

        protected Number eval(ELContext elctx, int x, int y) {
            int z = x * y;
            if (y != 0 && z/y != x) {       // overflowed
                return (long)x * (long)y;
            } else {
                return z;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Division expression.
     */
    public static class DIV extends Arithmetic {
        public DIV(int pos, ELNode left, ELNode right) {
            super(Token.DIV, pos, left, right);
        }

        // for IDIV
        protected DIV(int op, int pos, ELNode left, ELNode right) {
            super(op, pos, left, right);
        }

        public int precedence() {
            return MUL_PREC;
        }

        protected Number eval(ELContext elctx, BigDecimal x, BigDecimal y) {
            MathContext mc = GlobalScope.getMathContext(elctx);
            if (mc == null) {
                mc = new MathContext((int)Math.min(x.precision() +
                                                   (long)Math.ceil(10.0*y.precision()/3.0),
                                                   Integer.MAX_VALUE),
                                     RoundingMode.HALF_UP);
            }
            return x.divide(y, mc);
        }

        protected Number eval(ELContext elctx, BigInteger x, BigInteger y) {
            BigInteger r[] = x.divideAndRemainder(y);
            if (r[1].equals(BigInteger.ZERO)) {
                return r[0];
            } else if (GlobalScope.isRationalEnabled(elctx)) {
                return Rational.make(x, y);
            } else {
                return eval(elctx, new BigDecimal(x), new BigDecimal(y));
            }
        }

        protected Number eval(ELContext elctx, Decimal x, Decimal y) {
            return x.divide(y);
        }

        protected Number eval(ELContext elctx, Rational x, Rational y) {
            return x.divide(y).reduce();
        }

        protected Number eval(ELContext elctx, long x, long y) {
            if (x % y == 0) {
                return x / y;
            } else if (GlobalScope.isRationalEnabled(elctx)) {
                return Rational.make(x, y);
            } else {
                return (double)x / (double)y;
            }
        }

        protected Number eval(ELContext elctx, int x, int y) {
            if (x % y == 0) {
                return x / y;
            } else if (GlobalScope.isRationalEnabled(elctx)) {
                return Rational.make(x, y);
            } else {
                return (double)x / (double)y;
            }
        }

        protected Number eval(ELContext elctx, double x, double y) {
            return x / y;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Integral division expression.
     */
    public static class IDIV extends DIV {
        public IDIV(int pos, ELNode left, ELNode right) {
            super(Token.IDIV, pos, left, right);
        }

        protected Number eval(ELContext elctx, BigDecimal x, BigDecimal y) {
            return x.toBigInteger().divide(y.toBigInteger());
        }

        protected Number eval(ELContext elctx, BigInteger x, BigInteger y) {
            return x.divide(y);
        }

        protected Number eval(ELContext elctx, Decimal x, Decimal y) {
            return x.divide(y).longValue();
        }

        protected Number eval(ELContext elctx, Rational x, Rational y) {
            return x.divide(y).longValue();
        }

        protected Number eval(ELContext elctx, long x, long y) {
            return x / y;
        }

        protected Number eval(ELContext elctx, int x, int y) {
            return x / y;
        }

        protected Number eval(ELContext elctx, double x, double y) {
            return (long)x / (long)y;
        }
    }

    /**
     * Remainder expression.
     */
    public static class REM extends Arithmetic {
        public REM(int pos, ELNode left, ELNode right) {
            super(Token.REM, pos, left, right);
        }

        public int precedence() {
            return MUL_PREC;
        }

        protected Number eval(ELContext elctx, BigDecimal x, BigDecimal y) {
            MathContext mc = GlobalScope.getMathContext(elctx);
            return (mc == null) ? x.remainder(y) : x.remainder(y, mc);
        }

        protected Number eval(ELContext elctx, BigInteger x, BigInteger y) {
            return x.remainder(y);
        }

        protected Number eval(ELContext elctx, Decimal x, Decimal y) {
            return x.remainder(y);
        }

        protected Number eval(ELContext elctx, Rational x, Rational y) {
            return x.remainder(y).reduce();
        }

        protected Number eval(ELContext elctx, long x, long y) {
            return x % y;
        }

        protected Number eval(ELContext elctx, int x, int y) {
            return x % y;
        }

        protected Number eval(ELContext elctx, double x, double y) {
            return x % y;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Power expression.
     */
    public static class POW extends Arithmetic {
        public POW(int pos, ELNode left, ELNode right) {
            super(Token.POW, pos, left, right);
        }

        public int precedence() {
            return POW_PREC;
        }

        public ELNode order() {
            return right_order();
        }
        
        protected Object evaluate(ELContext elctx, Object x, Object y) {
            if (x == null || y == null) {
                return null;
            } else if ((x instanceof Number) && (y instanceof Number)) {
                return pow(elctx, (Number)x, (Number)y);
            } else {
                return Math.pow(coerceToDouble(x), coerceToDouble(y));
            }
        }

        private Number pow(ELContext elctx, Number x, Number y) {
            if (y instanceof Long) {
                long n = y.longValue();
                if ((int)n == n) {
                    return pow(elctx, x, (int)n);
                } else {
                    return Math.pow(x.doubleValue(), n);
                }
            } else if (y instanceof Integer || y instanceof Short || y instanceof Byte) {
                return pow(elctx, x, y.intValue());
            } else {
                return Math.pow(x.doubleValue(), y.doubleValue());
            }
        }

        private Number pow(ELContext elctx, Number x, int n) {
            if (x instanceof BigInteger) {
                if (n == 0) {
                    return 1;
                } else if (n == 1) {
                    return x;
                } else if (n > 0) {
                    return ((BigInteger)x).pow(n);
                } else if (GlobalScope.isRationalEnabled(elctx)) {
                    return Rational.make(BigInteger.ONE, ((BigInteger)x).pow(-n));
                } else {
                    return Math.pow(x.doubleValue(), n);
                }
            } else if (x instanceof Long || x instanceof Integer || x instanceof Short || x instanceof Byte) {
                long m = x.longValue();
                if (n == 0) {
                    return 1;
                } else if (n == 1) {
                    return m;
                } else if (n > 0) {
                    BigInteger z = BigInteger.valueOf(m).pow(n);
                    return z.bitLength() < 32 ? z.intValue() :
                           z.bitLength() < 64 ? z.longValue() : z;
                } else if (GlobalScope.isRationalEnabled(elctx)) {
                    return Rational.make(BigInteger.ONE, BigInteger.valueOf(m).pow(-n));
                } else {
                    return Math.pow(x.doubleValue(), n);
                }
            } else if (x instanceof BigDecimal) {
                MathContext mc = (MathContext)elctx.getContext(MathContext.class);
                return (mc == null) ? ((BigDecimal)x).pow(n) : ((BigDecimal)x).pow(n, mc);
            } else if (x instanceof Decimal) {
                return Decimal.valueOf(((Decimal)x).toBigDecimal().pow(n));
            } else if (x instanceof Rational) {
                return ((Rational)x).pow(n).reduce();
            } else {
                return Math.pow(x.doubleValue(), n);
            }
        }

        protected Number eval(ELContext elctx, BigDecimal x, BigDecimal y) { return null; }
        protected Number eval(ELContext elctx, BigInteger x, BigInteger y) { return null; }
        protected Number eval(ELContext elctx, Decimal x, Decimal y)       { return null; }
        protected Number eval(ELContext elctx, Rational x, Rational y)     { return null; }
        protected Number eval(ELContext elctx, long x, long y)             { return null; }
        protected Number eval(ELContext elctx, int x, int y)               { return null; }
        protected Number eval(ELContext elctx, double x, double y)         { return null; }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Bitwise not expression.
     */
    public static class BITNOT extends Unary {
        public BITNOT(int pos, ELNode right) {
            super(Token.BITNOT, pos, right);
        }

        public int precedence() {
            return PREFIX_PREC;
        }

        protected Object evaluate(ELContext elctx, Object x) {
            if (x == null) {
                return 0;
            } else if (x instanceof Long) {
                return ~(Long)x;
            } else if (x instanceof Integer) {
                return ~(Integer)x;
            } else if (x instanceof Short) {
                return (short)(~(Short)x);
            } else if (x instanceof Byte) {
                return (byte)(~(Byte)x);
            } else if (x instanceof BigDecimal) {
                return ((BigDecimal)x).toBigInteger().not();
            } else if (x instanceof BigInteger) {
                return ((BigInteger)x).not();
            } else {
                return ~coerceToLong(x);
            }
        }

        public Class getType(EvaluationContext context) {
            return Number.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Positive expression.
     */
    public static class POS extends Unary {
        public POS(int pos, ELNode right) {
            super(Token.POS, pos, right);
        }

        public int precedence() {
            return PREFIX_PREC;
        }

        protected Object evaluate(ELContext elctx, Object x) {
            if (x == null) {
                return 0;
            }

            if (x instanceof String) {
                if (looksLikeFloat(x)) {
                    return coerceToDouble(x);
                } else {
                    return coerceToLong(x);
                }
            } else if (x instanceof Number) {
                return x;
            } else {
                throw runtimeError(elctx, _T(JSPRT_UNSUPPORTED_EVAL_TYPE, x.getClass().getName()));
            }
        }

        public Class getType(EvaluationContext context) {
            return Number.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Negative expression.
     */
    public static class NEG extends Unary {
        public NEG(int pos, ELNode right) {
            super(Token.NEG, pos, right);
        }

        public int precedence() {
            return PREFIX_PREC;
        }

        protected Object evaluate(ELContext elctx, Object x) {
            if (x == null) {
                return 0;
            } else if (x instanceof Long) {
                return -((Long)x);
            } else if (x instanceof Integer) {
                return -((Integer)x);
            } else if (x instanceof Double) {
                return -((Double)x);
            } else if (x instanceof Float) {
                return -((Float)x);
            } else if (x instanceof Short) {
                return -((Short)x);
            } else if (x instanceof Byte) {
                return -((Byte)x);
            } else if (x instanceof BigDecimal) {
                return ((BigDecimal)x).negate();
            } else if (x instanceof BigInteger) {
                return ((BigInteger)x).negate();
            } else if (x instanceof Decimal) {
                return ((Decimal)x).negate();
            } else if (x instanceof Rational) {
                return ((Rational)x).negate();
            } else if (x instanceof String) {
                String s = (String)x;
                if (looksLikeFloat(s)) {
                    return -Double.valueOf(s);
                } else {
                    return -Long.valueOf(s);
                }
            } else {
                throw runtimeError(elctx, _T(JSPRT_UNSUPPORTED_EVAL_TYPE, x.getClass().getName()));
            }
        }

        public Class getType(EvaluationContext context) {
            return Number.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class INC extends Unary {
        public final boolean is_preincrement;

        public INC(int pos, ELNode right, boolean is_preincrement) {
            super(Token.INC, pos, right);
            this.is_preincrement = is_preincrement;
        }

        public int precedence() {
            return is_preincrement ? PREFIX_PREC : POSTFIX_PREC;
        }

        public Object getValue(EvaluationContext context) {
            Object x = right.getValue(context);
            Object y;

            if (x == null) {
                y = null;
            } else if (x instanceof Long) {
                y = (Long)x + 1;
            } else if (x instanceof Integer) {
                y = (Integer)x + 1;
            } else if (x instanceof Double) {
                y = (Double)x + 1.0;
            } else if (x instanceof Float) {
                y = (Float)x + 1.0f;
            } else if (x instanceof Short) {
                y = (short)((Short)x + 1);
            } else if (x instanceof Character) {
                y = (char)((Character)x + 1);
            } else if (x instanceof Byte) {
                y = (byte)((Byte)x + 1);
            } else if (x instanceof BigDecimal) {
                y = ((BigDecimal)x).add(BigDecimal.ONE);
            } else if (x instanceof BigInteger) {
                y = ((BigInteger)x).add(BigInteger.ONE);
            } else if (x instanceof Decimal) {
                y = ((Decimal)x).add(Decimal.ONE);
            } else if (x instanceof Rational) {
                y = ((Rational)x).add(Rational.ONE);
            } else {
                throw runtimeError(context.getELContext(),
                    _T(JSPRT_UNSUPPORTED_EVAL_TYPE, x.getClass().getName()));
            }

            right.setValue(context, y);
            return is_preincrement ? y : x;
        }

        public Class getType(EvaluationContext context) {
            return right.getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class DEC extends Unary {
        public final boolean is_preincrement;

        public DEC(int pos, ELNode right, boolean is_preincrement) {
            super(Token.DEC, pos, right);
            this.is_preincrement = is_preincrement;
        }

        public int precedence() {
            return is_preincrement ? PREFIX_PREC : POSTFIX_PREC;
        }

        public Object getValue(EvaluationContext context) {
            Object x = right.getValue(context);
            Object y;

            if (x == null) {
                y = null;
            } else if (x instanceof Long) {
                y = (Long)x - 1;
            } else if (x instanceof Integer) {
                y = (Integer)x - 1;
            } else if (x instanceof Double) {
                y = (Double)x - 1.0;
            } else if (x instanceof Float) {
                y = (Float)x - 1.0f;
            } else if (x instanceof Short) {
                y = (short)((Short)x - 1);
            } else if (x instanceof Character) {
                y = (char)((Character)x - 1);
            } else if (x instanceof Byte) {
                y = (byte)((Byte)x - 1);
            } else if (x instanceof BigDecimal) {
                y = ((BigDecimal)x).subtract(BigDecimal.ONE);
            } else if (x instanceof BigInteger) {
                y = ((BigInteger)x).subtract(BigInteger.ONE);
            } else if (x instanceof Decimal) {
                y = ((Decimal)x).subtract(Decimal.ONE);
            } else if (x instanceof Rational) {
                y = ((Rational)x).subtract(Rational.ONE);
            } else {
                throw runtimeError(context.getELContext(),
                    _T(JSPRT_UNSUPPORTED_EVAL_TYPE, x.getClass().getName()));
            }

            right.setValue(context, y);
            return is_preincrement ? y : x;
        }

        public Class getType(EvaluationContext context) {
            return right.getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Not expression.
     */
    public static class NOT extends Unary implements Pattern {
        public NOT(int pos, ELNode right) {
            super(Token.NOT, pos, right);
        }

        public int precedence() {
            return PREFIX_PREC;
        }

        public Object getValue(EvaluationContext context) {
            return !right.getBoolean(context);
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        public boolean matches(EvaluationContext context, Object value) {
            return !((Pattern)right).matches(context, value);
        }
        
        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Empty expression.
     */
    public static class EMPTY extends Unary {
        public EMPTY(int pos, ELNode right) {
            super(Token.EMPTY, pos, right);
        }

        public int precedence() {
            return PREFIX_PREC;
        }

        protected Object evaluate(ELContext elctx, Object x) {
            boolean result;
            if (x == null) {
                result = true;
            } else if (x instanceof String) {
                result = ((String)x).length() == 0;
            } else if (x.getClass().isArray()) {
                result = Array.getLength(x) == 0;
            } else if (x instanceof java.util.Map) {
                result = ((java.util.Map)x).isEmpty();
            } else if (x instanceof java.util.Collection) {
                result = ((java.util.Collection)x).isEmpty();
            } else {
                result = false;
            }
            return result;
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Parentheses expression.
     */
    public static class EXPR extends Unary implements Pattern {
        public EXPR(int pos, ELNode right) {
            super(Token.EXPR, pos, right);
        }

        public Object getValue(EvaluationContext context) {
            return right.getValue(context);
        }

        public Class getType(EvaluationContext context) {
            return right.getType(context);
        }

        public boolean isReadOnly(EvaluationContext context) {
            return right.isReadOnly(context);
        }

        public void setValue(EvaluationContext context, Object value) {
            right.setValue(context, value);
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            return right.invokeTail(context, call, args);
        }

        public boolean matches(EvaluationContext context, Object value) {
            return EQ.equals(context.getELContext(), value, right.getValue(context));
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The compound expression.
     */
    public static class COMPOUND extends ELNode {
        public final ELNode[] exps;

        public COMPOUND(int pos, ELNode[] exps) {
            super(Token.EXPR, pos);
            this.exps = exps;
        }

        public Object getValue(EvaluationContext context) {
            int n = exps.length;
            if (n == 0) {
                return null;
            }

            EvaluationContext env = context.pushContext();
            Frame f = context.getFrame();
            for (int i = 0; i < n-1; i++) {
                exps[i].pos(f).getValue(env);
            }
            return exps[n-1].pos(f).getValue(env);
        }

        public Class getType(EvaluationContext context) {
            int n = exps.length;
            if (n == 0) {
                return null;
            }

            EvaluationContext env = context.pushContext();
            Frame f = context.getFrame();
            for (int i = 0; i < n-1; i++) {
                exps[i].pos(f).getValue(env);
            }
            return exps[n-1].pos(f).getType(env);
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            int n = exps.length;
            if (n == 0) {
                call.result = null;
                return false;
            }

            EvaluationContext env = context.pushContext();
            Frame f = context.getFrame();
            for (int i = 0; i < n-1; i++) {
                exps[i].pos(f).getValue(env);
            }
            return exps[n-1].pos(f).invokeTail(env, call, args);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The while expression.
     */
    public static class WHILE extends ELNode {
        public final ELNode cond;
        public final ELNode body;

        public WHILE(int pos, ELNode cond, ELNode body) {
            super(Token.WHILE, pos);
            this.cond = cond;
            this.body = body;
        }

        public Object getValue(EvaluationContext context) {
            Frame f = context.getFrame();
            while (cond.pos(f).getBoolean(context)) {
                try {
                    body.pos(f).getValue(context);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    // continue
                }
            }
            return null;
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The for loop expression.
     */
    public static class FOR extends ELNode {
        public final ELNode[] init;
        public final ELNode   cond;
        public final ELNode[] step;
        public final ELNode   body;
        public final boolean  local;

        public FOR(int pos, ELNode[] init, ELNode cond, ELNode[] step, ELNode body, boolean local) {
            super(Token.FOR, pos);
            this.init = init;
            this.cond = cond;
            this.step = step;
            this.body = body;
            this.local = local;
        }

        public Object getValue(EvaluationContext context) {
            if (local) {
                context = context.pushContext();
            }

            Frame f = context.getFrame();

            if (init != null) {
                for (ELNode e : init) {
                    e.pos(f).getValue(context);
                }
            }

            while (cond.pos(f).getBoolean(context)) {
                if (body != null) {
                    try {
                        body.pos(f).getValue(context);
                    } catch (Control.Break b) {
                        break;
                    } catch (Control.Continue c) {
                        // continue
                    }
                }

                if (step != null) {
                    for (ELNode e : step) {
                        e.pos(f).getValue(context);
                    }
                }
            }

            return null;
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The for-each loop expression.
     */
    public static class FOREACH extends ELNode {
        public final DEFINE index;
        public final DEFINE var;
        public final ELNode range;
        public final ELNode body;

        public FOREACH(int pos, DEFINE index, DEFINE var, ELNode range, ELNode body) {
            super(Token.FOR, pos);
            this.index = index;
            this.var = var;
            this.range = range;
            this.body = body;
        }

        public Object getValue(EvaluationContext context) {
            Object range = this.range.getValue(context);
            if (range == null) {
                return null;
            }

            EvaluationContext env = context.pushContext();
            Closure var = this.var.defineClosure(context);
            env.setVariable(this.var.id, var);

            if (this.index != null) {
                Closure idx = this.index.defineClosure(context);
                env.setVariable(this.index.id, idx);

                if (range instanceof Range) {
                    Range r = (Range)range;
                    if (r.isUnbound()) {
                        step(env, idx, var, r.getBegin(), r.getStep());
                    } else if (r.getStep() > 0) {
                        stepUp(env, idx, var, r.getBegin(), r.getEnd(), r.getStep());
                    } else {
                        stepDown(env, idx, var, r.getBegin(), r.getEnd(), r.getStep());
                    }
                } else if (range instanceof Iterable) {
                    foreach(env, idx, var, (Iterable)range);
                } else if (range instanceof Map) {
                    foreach(env, idx, var, (Map)range);
                } else if (range instanceof Object[]) {
                    foreach(env, idx, var, (Object[])range);
                } else if (range.getClass().isArray()) {
                    foreach(env, idx, var, range);
                } else if (range instanceof String) {
                    foreach(env, idx, var, ((String)range).toCharArray());
                }
            } else {
                if (range instanceof Range) {
                    Range r = (Range)range;
                    if (r.isUnbound()) {
                        step(env, var, r.getBegin(), r.getStep());
                    } else if (r.getStep() > 0) {
                        stepUp(env, var, r.getBegin(), r.getEnd(), r.getStep());
                    } else {
                        stepDown(env, var, r.getBegin(), r.getEnd(), r.getStep());
                    }
                } else if (range instanceof Iterable) {
                    foreach(env, var, (Iterable)range);
                } else if (range instanceof Map) {
                    foreach(env, var, (Map)range);
                } else if (range instanceof Object[]) {
                    foreach(env, var, (Object[])range);
                } else if (range.getClass().isArray()) {
                    foreach(env, var, range);
                } else if (range instanceof String) {
                    foreach(env, var, ((String)range).toCharArray());
                }
            }

            return null;
        }

        private void step(EvaluationContext ctx, Closure idx, Closure var, long begin, long step) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            int i = 0;
            for (long x = begin; ; x += step, i++) {
                try {
                    idx.setValue(elctx, i);
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void step(EvaluationContext ctx, Closure var, long begin, long step) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (long x = begin; ; x += step) {
                try {
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void stepUp(EvaluationContext ctx, Closure idx, Closure var, long begin, long end, long step) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            int i = 0;
            for (long x = begin; x <= end; x += step, i++) {
                try {
                    idx.setValue(elctx, i);
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void stepUp(EvaluationContext ctx, Closure var, long begin, long end, long step) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (long x = begin; x <= end; x += step) {
                try {
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void stepDown(EvaluationContext ctx, Closure idx, Closure var, long begin, long end, long step) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            int i = 0;
            for (long x = begin; x >= end; x += step, i++) {
                try {
                    idx.setValue(elctx, i);
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void stepDown(EvaluationContext ctx, Closure var, long begin, long end, long step) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (long x = begin; x >= end; x += step) {
                try {
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void foreach(EvaluationContext ctx, Closure idx, Closure var, Iterable xs) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            int i = 0;
            for (Object x : xs) {
                try {
                    idx.setValue(elctx, i++);
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void foreach(EvaluationContext ctx, Closure var, Iterable xs) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (Object x : xs) {
                try {
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void foreach(EvaluationContext ctx, Closure idx, Closure var, Map<?,?> map) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (Map.Entry<?,?> e : map.entrySet()) {
                try {
                    idx.setValue(elctx, e.getKey());
                    var.setValue(elctx, e.getValue());
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void foreach(EvaluationContext ctx, Closure var, Map<?,?> map) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (Map.Entry<?,?> x : map.entrySet()) {
                try {
                    var.setValue(elctx, x);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void foreach(EvaluationContext ctx, Closure idx, Closure var, Object[] xs) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (int i = 0, len = xs.length; i < len; i++) {
                try {
                    idx.setValue(elctx, i);
                    var.setValue(elctx, xs[i]);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void foreach(EvaluationContext ctx, Closure var, Object[] xs) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (int i = 0, len = xs.length; i < len; i++) {
                try {
                    var.setValue(elctx, xs[i]);
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void foreach(EvaluationContext ctx, Closure idx, Closure var, Object xs) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (int i = 0, len = Array.getLength(xs); i < len; i++) {
                try {
                    idx.setValue(elctx, i);
                    var.setValue(elctx, Array.get(xs, i));
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        private void foreach(EvaluationContext ctx, Closure var, Object xs) {
            ELContext elctx = ctx.getELContext();
            Frame f = ctx.getFrame();

            for (int i = 0, len = Array.getLength(xs); i < len; i++) {
                try {
                    var.setValue(elctx, Array.get(xs, i));
                    body.pos(f).getValue(ctx);
                } catch (Control.Break b) {
                    break;
                } catch (Control.Continue c) {
                    continue;
                }
            }
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The match expression.
     */
    public static class MATCH extends ELNode {
        public final ELNode[] args;
        public final CASE[] alts;
        public final ELNode deflt;

        public MATCH(int pos, ELNode arg, CASE alt, ELNode deflt) {
            this(pos, new ELNode[]{arg}, new CASE[]{alt}, deflt);
        }

        public MATCH(int pos, ELNode arg, CASE[] alts, ELNode deflt) {
            this(pos, new ELNode[]{arg}, alts, deflt);
        }

        public MATCH(int pos, ELNode[] args, CASE alt, ELNode deflt) {
            this(pos, args, new CASE[]{alt}, deflt);
        }

        public MATCH(int pos, ELNode[] args, CASE[] alts, ELNode deflt) {
            super(Token.MATCH, pos);
            this.args = args;
            this.alts = alts;
            this.deflt = deflt;
        }

        public Object getValue(EvaluationContext context) {
            EvaluationContext env = context.pushContext();
            return match(env).getValue(env);
        }

        public Class getType(EvaluationContext context) {
            EvaluationContext env = context.pushContext();
            return match(env).getType(env);
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            EvaluationContext env = context.pushContext();
            return match(env).invokeTail(env, call, args);
        }

        protected ELNode match(EvaluationContext context) {
            Frame f = context.getFrame();

            Object[] values = new Object[args.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = args[i].pos(f).getValue(context);
            }

            // Create a temporary environment for variable pattern matching.
            VariableMapperImpl vm = new VariableMapperImpl();
            Map<String,ValueExpression> map = vm.getVariableMap();
            EvaluationContext env = context.pushContext(vm);

            for (CASE b : alts) {
                ELNode body = b.matches(env, map, values);
                if (body != null) {
                    // Copy matched variables into actual environment.
                    for (Map.Entry<String,ValueExpression> e : map.entrySet())
                        context.setVariable(e.getKey(), e.getValue());
                    return body.pos(f);
                } else {
                    // Clear the variable mapper to reuse it.
                    map.clear();
                }
            }

            // No match case found, returns the default
            if (deflt == null) {
                f.setPos(this.pos);
                throw runtimeError(context.getELContext(), "no pattern matched.");
            } else {
                return deflt.pos(f);
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * An optimized match expression. The match cases has no variable bindings.
     */
    public static class CONST_MATCH extends MATCH {
        public CONST_MATCH(int pos, ELNode[] args, CASE[] alts, ELNode deflt) {
            super(pos, args, alts, deflt);
        }

        protected ELNode match(EvaluationContext context) {
            Frame f = context.getFrame();

            Object[] values = new Object[args.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = args[i].pos(f).getValue(context);
            }

            ELNode body;
            for (CASE b : alts) {
                f.setPos(b.pos);
                if ((body = b.matches(context, null, values)) != null) {
                    return body.pos(f);
                }
            }

            if (deflt == null) {
                f.setPos(this.pos);
                throw runtimeError(context.getELContext(), "no pattern matched.");
            } else {
                return deflt.pos(f);
            }
        }
    }

    /**
     * A pattern matching case.
     */
    public static class CASE extends ELNode {
        public final Pattern[] patterns;
        public final ELNode[] guards;
        public final ELNode[] bodies;

        public CASE(int pos, Pattern pattern, ELNode body) {
            this(pos, new Pattern[] {pattern}, null, new ELNode[] {body});
        }

        public CASE(int pos, Pattern[] elems, ELNode body) {
            this(pos, elems, null, new ELNode[] {body});
        }

        public CASE(int pos, Pattern[] elems, ELNode[] guards, ELNode[] bodies) {
            super(Token.CASE, pos);
            this.patterns = elems;
            this.guards = guards;
            this.bodies = bodies;
        }

        public Object getValue(EvaluationContext context) {
            throw new AssertionError();
        }

        public Class getType(EvaluationContext context) {
            throw new AssertionError();
        }

        ELNode matches(EvaluationContext env, Map<String,ValueExpression> map, Object[] args) {
            boolean ok = true;

            // Matches for patterns.
            if (patterns != null) {
                for (int i = 0; ok && i < patterns.length; i++) {
                    Pattern pat = patterns[i];
                    if (pat instanceof OR) { // optimize
                        ok = ((OR)pat).try_match(env, map, args[i]);
                    } else {
                        ok = pat.matches(env, args[i]);
                    }
                }
            }

            // Evaluate guard.
            if (ok) {
                if (guards == null) {
                    assert bodies.length == 1;
                    return bodies[0];
                }

                assert guards.length == bodies.length;
                for (int i = 0; i < guards.length; i++) {
                    if (guards[i] == null || guards[i].getBoolean(env)) {
                        return bodies[i];
                    }
                }
            }

            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The pattern-matching assignment expression.
     */
    public static class LET extends ELNode {
        public final ELNode left;
        public final ELNode right;
        public final boolean force;

        public LET(int pos, ELNode left, ELNode right, boolean force) {
            super(Token.LET, pos);
            this.left = left;
            this.right = right;
            this.force = force;
        }

        public Object getValue(final EvaluationContext context) {
            Frame f = context.getFrame();
            Object value = right.pos(f).getValue(context);

            // Create a temporary variable mapper for variable pattern matching.
            // The variable is first resolved in local variable mapper. If the
            // variable not found then resolve it from enclosing environment.
            VariableMapperImpl vm = new VariableMapperImpl() {
                public ValueExpression resolveVariable(String name) {
                    ValueExpression ve = super.resolveVariable(name);
                    if (ve == null && !force)
                        ve = context.resolveLocalVariable(name);
                    return ve;
                }
            };

            // Matches value against the pattern.
            EvaluationContext env = context.pushContext(vm);
            Map<String,ValueExpression> map = vm.getVariableMap();
            boolean matched;

            if (left instanceof OR) {
                matched = ((OR)left.pos(f)).try_match(env, map, value);
            } else {
                matched = ((Pattern)left.pos(f)).matches(env, value);
            }
            if (!matched) {
                throw runtimeError(context.getELContext(), "pattern not match");
            }

            // Copy matched variables into enclosing environment.
            for (Map.Entry<String,ValueExpression> e : map.entrySet()) {
                context.setVariable(e.getKey(), e.getValue());
            }

            return value;
        }

        public Class getType(EvaluationContext context) {
            return right.getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    static boolean isWildcard(ELNode pattern) {
        if (pattern instanceof ELNode.DEFINE) {
            ELNode.DEFINE var = (ELNode.DEFINE)pattern;
            return "_".equals(var.id) && var.type == null && var.expr == null;
        } else {
            return false;
        }
    }

    /**
     * The break expression.
     */
    public static class BREAK extends ELNode {
        public BREAK(int pos) {
            super(Token.BREAK, pos);
        }

        public Object getValue(EvaluationContext context) {
            throw new Control.Break();
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The continue expression.
     */
    public static class CONTINUE extends ELNode {
        public CONTINUE(int pos) {
            super(Token.CONTINUE, pos);
        }

        public Object getValue(EvaluationContext context) {
            throw new Control.Continue();
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The return statement.
     */
    public static class RETURN extends ELNode {
        public final ELNode right;

        public RETURN(int pos, ELNode right) {
            super(Token.THROW, pos);
            this.right = right;
        }

        public Class getType(EvaluationContext context) {
            return right.getType(context);
        }

        public Object getValue(EvaluationContext context) {
            throw new Control.Return(right.getValue(context));
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            return right.invokeTail(context, call, args);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The throw statement.
     */
    public static class THROW extends ELNode {
        public final ELNode cause;

        public THROW(int pos, ELNode cause) {
            super(Token.THROW, pos);
            this.cause = cause;
        }

        public Object getValue(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object value = cause.getValue(context);
            if (value instanceof UserException) {
                throw (UserException)value;
            } else if (value instanceof Throwable) {
                throw new UserException(elctx, (Throwable)value);
            } else if (value instanceof String) {
                throw new UserException(elctx, (String)value);
            } else {
                throw new UserException(elctx);
            }
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The try statement.
     */
    public static class TRY extends ELNode {
        public final ELNode body;
        public final DEFINE[] handlers;
        public final ELNode finalizer;

        public TRY(int pos, ELNode body, DEFINE[] handlers, ELNode finalizer) {
            super(Token.TRY, pos);
            this.body = body;
            this.handlers = handlers;
            this.finalizer = finalizer;
        }

        public Object getValue(EvaluationContext context) {
            Frame f = context.getFrame();
            Object result;

            if (handlers != null) {
                try {
                    result = body.pos(f).getValue(context);
                } catch (Control control) {
                    throw control;
                } catch (EvaluationException ex) {
                    Throwable t = ex.getCause();
                    if (t != null) {
                        result = handle(context, f, t);
                    } else {
                        result = handle(context, f, ex);
                    }
                    if (result == NO_RESULT) {
                        throw ex;
                    }
                } catch (RuntimeException ex) {
                    result = handle(context, f, ex);
                    if (result == NO_RESULT) {
                        throw ex;
                    }
                } catch (Error ex) {
                    result = handle(context, f, ex);
                    if (result == NO_RESULT) {
                        throw ex;
                    }
                } finally {
                    if (finalizer != null) {
                        finalizer.pos(f).getValue(context);
                    }
                }
            } else {
                try {
                    result = body.pos(f).getValue(context);
                } finally {
                    finalizer.pos(f).getValue(context);
                }
            }

            return result;
        }

        private Object handle(EvaluationContext context, Frame f, Throwable exc) {
            // find handler
            DEFINE handler = null;
            for (DEFINE def : handlers) {
                if (def.type != null) {
                    if (TypedClosure.typecheck(context, def.type, exc)) {
                        handler = def;
                        break;
                    }
                } else {
                    handler = def;
                    break;
                }
            }

            if (handler != null) {
                // evaluate handler
                EvaluationContext env = context.pushContext();
                env.setVariable(handler.id, new LiteralClosure(exc));
                return handler.expr.pos(f).getValue(env);
            } else {
                // rethrow exception
                return NO_RESULT;
            }
        }

        public Class getType(EvaluationContext context) {
            return body.getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The catch expression.
     */
    public static class CATCH extends ELNode {
        public final String var;
        public final ELNode body;

        public CATCH(int pos, String var, ELNode body) {
            super(Token.CATCH, pos);
            this.var = var;
            this.body = body;
        }

        public Object getValue(EvaluationContext context) {
            Object cp = new Object();
            try {
                EvaluationContext env = setup(context, cp);
                return body.pos(context.getFrame()).getValue(env);
            } catch (Control.Escape esc) {
                if (cp == esc.getCatchPoint()) {
                    return esc.getResult();
                } else {
                    throw esc;
                }
            }
        }

        boolean invokeTail(EvaluationContext context, TailCall call, Closure[] args) {
            Object cp = new Object();
            try {
                EvaluationContext env = setup(context, cp);
                return body.pos(context.getFrame()).invokeTail(env, call, args);
            } catch (Control.Escape esc) {
                if (cp == esc.getCatchPoint()) {
                    call.result = esc.getResult();
                    return false;
                } else {
                    throw esc;
                }
            }
        }

        private EvaluationContext setup(EvaluationContext context, final Object cp) {
            final Closure escape = new AbstractClosure() {
                public Object invoke(ELContext context, Closure[] args) {
                    Object result = (args.length > 0) ? args[0].getValue(context) : null;
                    throw new Control.Escape(result, cp);
                }};

            EvaluationContext env = context.pushContext();
            env.setVariable(var, escape);
            return env;
        }
        
        public Class getType(EvaluationContext context) {
            return body.pos(context.getFrame()).getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Synchronized statement.
     */
    public static class SYNCHRONIZED extends ELNode {
        public final ELNode exp;
        public final ELNode body;

        public SYNCHRONIZED(int pos, ELNode exp, ELNode body) {
            super(Token.SYNCHRONIZED, pos);
            this.exp = exp;
            this.body = body;
        }

        public Object getValue(EvaluationContext context) {
            Frame f = context.getFrame();

            Object obj = exp.pos(f).getValue(context);
            if (obj instanceof ThisObject) {
                obj = ((ThisObject)obj).get_proxy();
            }

            if (obj == null) {
                throw runtimeError(context.getELContext(), new NullPointerException());
            }
            
            synchronized (obj) {
                return body.pos(f).getValue(context);
            }
        }

        public Class getType(EvaluationContext context) {
            return body.getType(context);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Assert statement.
     */
    public static class ASSERT extends ELNode {
        public final ELNode exp;
        public final ELNode msg;

        public ASSERT(int pos, ELNode exp, ELNode msg) {
            super(Token.ASSERT, pos);
            this.exp = exp;
            this.msg = msg;
        }

        public Object getValue(EvaluationContext context) {
            try {
                if (msg == null) {
                    assert exp.getBoolean(context);
                } else {
                    assert exp.getBoolean(context) : msg.getValue(context);
                }
                return null;
            } catch (AssertionError ex) {
                throw runtimeError(context.getELContext(), ex);
            }
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Constant expression.
     */
    public static abstract class Constant extends ELNode {
        public Constant(int op, int pos) {
            super(op, pos);
        }

        public Closure closure(EvaluationContext context) {
            return new LiteralClosure(getValue(null));
        }
    }

    /**
     * Generic constant expression.
     */
    public static class CONST extends Constant {
        public final Object value;

        public CONST(int pos, Object value) {
            super(Token.CONST, pos);
            this.value = value;
        }

        public Object getValue(EvaluationContext context) {
            return value;
        }

        public Class getType(EvaluationContext context) {
            return (value == null) ? null : value.getClass();
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Boolean constant.
     */
    public static class BOOLEANVAL extends Constant implements Pattern {
        public final Boolean value;

        public BOOLEANVAL(int pos, boolean value) {
            super(Token.BOOLEANVAL, pos);
            this.value = value;
        }

        public Object getValue(EvaluationContext context) {
            return value;
        }

        public Class getType(EvaluationContext context) {
            return Boolean.class;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            try {
                return value.equals(coerceToBoolean(arg));
            } catch (Exception ex) {
                return false;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Character constant expression.
     */
    public static class CHARVAL extends Constant implements Pattern {
        public final Character value;

        public CHARVAL(int pos, char value) {
            super(Token.CHARVAL, pos);
            this.value = value;
        }

        public Object getValue(EvaluationContext context) {
            return value;
        }

        public Class getType(EvaluationContext context) {
            return Character.class;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            try {
                return value.equals(coerceToCharacter(arg));
            } catch (Exception ex) {
                return false;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Number constant expression.
     */
    public static class NUMBER extends Constant implements Pattern {
        public final Number value;

        public NUMBER(int pos, Number value) {
            super(Token.NUMBER, pos);
            this.value = value;
        }

        public Object getValue(EvaluationContext context) {
            return value;
        }

        public Class getType(EvaluationContext context) {
            return value.getClass();
        }

        public boolean matches(EvaluationContext context, Object arg) {
            try {
                return EQ.equals(context.getELContext(), value, arg);
            } catch (Exception ex) {
                return false;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Symbol constant value.
     */
    public static class SYMBOL extends Constant implements Pattern {
        public final Symbol value;

        public SYMBOL(int pos, Symbol value) {
            super(Token.SYMBOL, pos);
            this.value = value;
        }

        public Object getValue(EvaluationContext context) {
            return value;
        }

        public Class getType(EvaluationContext context) {
            return Symbol.class;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            return value == arg;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * String constant expression.
     */
    public static class STRINGVAL extends Constant implements Pattern {
        public final String value;

        public STRINGVAL(int pos, String value) {
            super(Token.STRINGVAL, pos);
            this.value = value;
        }

        public Object getValue(EvaluationContext context) {
            return value;
        }

        public Class getType(EvaluationContext context) {
            return String.class;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            return this.value.equals(arg);
        }

        public Object invoke(EvaluationContext context, Closure[] args) {
            ELContext elctx = context.getELContext();
            Object target = resolveTarget(context, value);

            if (target == null) {
                throw runtimeError(elctx, _T(EL_UNDEFINED_IDENTIFIER, value));
            }

            try {
                return ELEngine.invokeTarget(elctx, target, args);
            } catch (MethodNotFoundException ex) {
                throw methodNotFound(elctx, target, value, ex);
            } catch (RuntimeException ex) {
                throw runtimeError(elctx, ex);
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Regular expression pattern.
     */
    public static class REGEXP extends Constant implements Pattern {
        public final java.util.regex.Pattern value;

        public REGEXP(int pos, String value) {
            super(Token.STRINGVAL, pos);
            this.value = java.util.regex.Pattern.compile(value);
        }

        public Object getValue(EvaluationContext context) {
            return value;
        }

        public Class getType(EvaluationContext context) {
            return java.util.regex.Pattern.class;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            if (arg instanceof String) {
                return value.matcher((String)arg).matches();
            } else {
                return false;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }
    
    /**
     * Literal text expression.
     */
    public static class LITERAL extends Constant {
        public final String value;

        public LITERAL(int pos, String value) {
            super(Token.LITERAL, pos);
            this.value = value;
        }

        public Object getValue(EvaluationContext context) {
            return value;
        }

        public Class getType(EvaluationContext context) {
            return String.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Null constant expression.
     */
    public static class NULL extends Constant implements Pattern {
        public NULL(int pos) {
            super(Token.NULL, pos);
        }

        public Object getValue(EvaluationContext context) {
            return null;
        }

        public Class getType(EvaluationContext context) {
            return null;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            return arg == null;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Class literal expression.
     */
    public static class CLASS extends Constant implements Pattern {
        public final String name;
        public final String[] slots;

        public CLASS(int pos, String name, String[] slots) {
            super(Token.CLASS, pos);
            this.name = name;
            this.slots = slots;
        }

        public Object getValue(EvaluationContext context) {
            Class<?> cls = ELEngine.resolveJavaClass(context.getELContext(), name);
            String[] slots = this.slots;
            if (slots == null && cls.isAnnotationPresent(Data.class)) {
                slots = cls.getAnnotation(Data.class).value();
            }
            return new DataClass(cls, slots);
        }

        public Class getType(EvaluationContext context) {
            return Class.class;
        }

        public boolean matches(EvaluationContext context, Object value) {
            return TypedClosure.typecheck(context, name, value);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Array expression.
     */
    public static class ARRAY extends ELNode {
        public final String type;
        public final ELNode[] dims;
        public final ELNode[] init;

        public ARRAY(int pos, String type, ELNode[] dims, ELNode[] init) {
            super(Token.ARRAY, pos);
            this.type = type;
            this.dims = dims;
            this.init = init;
        }

        public Object getValue(EvaluationContext context) {
            if (dims == null || dims.length == 1) {
                return createOneDimensionalArray(context);
            } else {
                return createMultiDimensionalArray(context);
            }
        }

        private Object createOneDimensionalArray(EvaluationContext context) {
            int length = 0;
            if (dims != null) {
                length = coerceToInt(dims[0].getValue(context));
            }
            if (init != null && length < init.length) {
                length = init.length;
            }

            Class cls = componentType(context);
            Object result = Array.newInstance(cls, length);

            if (init != null) {
                if (result instanceof Object[]) {
                    Object[] a = (Object[])result;
                    for (int i = 0; i < init.length; i++) {
                        a[i] = init[i].getValue(context);
                    }
                } else {
                    for (int i = 0; i < init.length; i++) {
                        Object value = init[i].getValue(context);
                        Array.set(result, i, coerce(value, cls));
                    }
                }
            }

            return result;
        }

        private Object createMultiDimensionalArray(EvaluationContext context) {
            assert dims != null && dims.length > 1;
            int d[] = new int[dims.length];
            for (int i = 0; i < d.length; i++) {
                d[i] = coerceToInt(dims[i].getValue(context));
            }

            // TODO: populate initializer
            Class t = componentType(context);
            return Array.newInstance(t, d);
        }

        private Class componentType(EvaluationContext context) {
            if (type == null) {
                return Object.class;
            } else {
                Object cls = ELEngine.resolveClass(context, type);
                if (cls instanceof Class) {
                    return (Class)cls;
                } else {
                    return Object.class;
                }
            }
        }
        
        public Class getType(EvaluationContext context) {
            return Object[].class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * List literal expression.
     */
    public static class CONS extends ELNode implements Pattern {
        public final ELNode head;
        public final ELNode tail;
        public final boolean delay;

        public CONS(int pos, ELNode head, ELNode tail) {
            this(pos, head, tail, false);
        }

        public CONS(int pos, ELNode head, ELNode tail, boolean delay) {
            super(Token.CONS, pos);
            assert head != null && tail != null;
            this.head = head;
            this.tail = tail;
            this.delay = delay;
        }

        public Object getValue(EvaluationContext context) {
            if (delay) {
                return new DelayCons(head.closure(context), tail.closure(context));
            } else if (tail instanceof NIL) {
                return Cons.make(head.getValue(context));
            } else if (tail instanceof CONS) {
                return new Cons(head.getValue(context), (Seq)tail.getValue(context));
            } else {
                return new Cons(head.getValue(context), coerceToSeq(tail.getValue(context)));
            }
        }

        public Class getType(EvaluationContext context) {
            return Seq.class;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            if (arg instanceof CharSequence) {
                CharSequence s = (CharSequence)arg;
                return s.length() != 0 &&
                       ((Pattern)head).matches(context, s.charAt(0)) &&
                       ((Pattern)tail).matches(context, s.subSequence(1, s.length()));
            } else if (arg instanceof List) {
                Seq xs = coerceToSeq(arg);
                return !xs.isEmpty() &&
                       ((Pattern)head).matches(context, xs.head()) &&
                       ((Pattern)tail).matches(context, xs.tail());
            } else {
                return false;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * Nil literal expression.
     */
    public static class NIL extends Constant implements Pattern {
        public NIL(int pos) {
            super(Token.NIL, pos);
        }

        public Object getValue(EvaluationContext context) {
            return Cons.nil();
        }

        public Class getType(EvaluationContext context) {
            return Seq.class;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            return (arg instanceof List) && ((List)arg).isEmpty() ||
                   (arg instanceof CharSequence) && ((CharSequence)arg).length() == 0;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The tuple expression.
     */
    public static class TUPLE extends ELNode implements Pattern {
        public final ELNode[] elems;

        public TUPLE(int pos, ELNode[] elems) {
            super(Token.TUPLE, pos);
            this.elems = elems;
        }

        public Object getValue(EvaluationContext context) {
            final ELNode[] elems = this.elems;
            final int len = elems.length;
            Object[] tuple = new Object[len];
            for (int i = 0; i < len; i++) {
                tuple[i] = elems[i].getValue(context);
            }
            return tuple;
        }

        public void setValue(EvaluationContext context, Object value) {
            if (value == null || !value.getClass().isArray()) {
                throw runtimeError(context.getELContext(), _T(EL_TUPLE_PATTERN_NOT_MATCH));
            }

            final ELNode[] elems = this.elems;
            final int len = Array.getLength(value);
            if (len != elems.length) {
                throw runtimeError(context.getELContext(), _T(EL_TUPLE_PATTERN_NOT_MATCH));
            }
            for (int i = 0; i < len; i++) {
                elems[i].setValue(context, Array.get(value, i));
            }
        }

        public Object[] copyValue(EvaluationContext context, ELNode[] value) {
            final ELNode[] elems = this.elems;
            final int len = elems.length;

            if (len != value.length) {
                throw runtimeError(context.getELContext(), _T(EL_TUPLE_PATTERN_NOT_MATCH));
            }

            Object[] result = new Object[len];
            for (int i = 0; i < len; i++) {
                result[i] = value[i].getValue(context);
            }
            for (int i = 0; i < len; i++) {
                elems[i].setValue(context, result[i]);
            }
            return result;
        }

        public Class getType(EvaluationContext context) {
            return Object[].class;
        }

        public boolean matches(EvaluationContext context, Object arg) {
            if (arg == null || !arg.getClass().isArray()) {
                return false;
            }

            int len = Array.getLength(arg);
            if (len != elems.length) {
                return false;
            }

            for (int i = 0; i < len; i++) {
                if (!((Pattern)elems[i]).matches(context, Array.get(arg, i))) {
                    return false;
                }
            }

            return true;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class MAP extends ELNode implements Pattern {
        public final ELNode[] keys;
        public final ELNode[] values;

        public MAP(int pos, ELNode[] keys, ELNode[] values) {
            super(Token.MAP, pos);
            assert keys.length == values.length;
            this.keys = keys;
            this.values = values;
        }

        public Object getValue(EvaluationContext context) {
            Map map = new LinkedHashMap();
            for (int i = 0; i < keys.length; i++) {
                map.put(keys[i].getValue(context), values[i].getValue(context));
            }
            return map;
        }

        public Class getType(EvaluationContext context) {
            return Map.class;
        }

        public boolean matches(EvaluationContext context, Object base) {
            ELContext elctx = context.getELContext();
            for (int i = 0; i < keys.length; i++) {
                assert keys[i] instanceof STRINGVAL;
                assert values[i] instanceof Pattern;
                try {
                    String key = (String)keys[i].getValue(context);
                    Object value = elctx.getELResolver().getValue(elctx, base, key);
                    if (!((Pattern)values[i]).matches(context, value)) {
                        return false;
                    }
                } catch (Exception ex) {
                    return false;
                }
            }
            return true;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class RANGE extends ELNode implements Pattern {
        public final ELNode begin;
        public final ELNode next;
        public final ELNode end;
        public final boolean exclude;

        private Object value;

        public RANGE(int pos, ELNode begin, ELNode next, ELNode end, boolean exclude) {
            super(Token.RANGE, pos);
            this.begin = begin;
            this.next = next;
            this.end = end;
            this.exclude = exclude;

            // handle constant range
            if (isConstant()) {
                this.value = getValue(null);
            }
        }

        public boolean isConstant() {
            return (isNum(begin) && isNum(next) && isNum(end)) ||
                   (isChar(begin) && isChar(next) && isChar(end));
        }

        public Object getValue(EvaluationContext context) {
            if (this.value != null) {
                return this.value;
            }

            Object begin = this.begin.getValue(context);
            Object next  = (this.next == null) ? null : this.next.getValue(context);
            Object end   = (this.end == null) ? null : this.end.getValue(context);

            if (isChar(begin) && (next == null || isChar(next)) && (end == null || isChar(end))) {
                char c_begin = getChar(begin);
                int c_step = (next == null) ? 1 : getChar(next) - c_begin;
                if (end == null) {
                    return CharRanges.createUnboundedRange(c_begin, c_step);
                } else {
                    char c_end = getChar(end);
                    if (exclude) c_end = (char)(c_end - c_step);
                    return CharRanges.createCharRange(c_begin, c_end, c_step);
                }
            } else {
                long l_begin = coerceToLong(begin);
                long l_step = (next == null) ? 1 : coerceToLong(next) - l_begin;
                if (end == null) {
                    return Ranges.createUnboundedRange(l_begin, l_step);
                } else {
                    long l_end = coerceToLong(end);
                    if (exclude) l_end -= l_step;
                    return Ranges.createRange(l_begin, l_end, l_step);
                }
            }
        }

        private boolean isChar(Object o) {
            if (o instanceof Character) {
                return true;
            } else if (o instanceof String) {
                return ((String)o).length() == 1;
            } else {
                return false;
            }
        }

        private boolean isChar(ELNode node) {
            if (node == null) {
                return true;
            } else if (node instanceof CHARVAL) {
                return true;
            } else if (node instanceof STRINGVAL) {
                return ((STRINGVAL)node).value.length() == 1;
            } else {
                return false;
            }
        }

        private char getChar(Object o) {
            if (o instanceof Character) {
                return (Character)o;
            } else if (o instanceof String) {
                return ((String)o).charAt(0);
            } else {
                throw new AssertionError();
            }
        }

        private boolean isNum(ELNode node) {
            if (node == null) {
                return true;
            } else if (node instanceof NUMBER) {
                return true;
            } else {
                return false;
            }
        }

        public Class getType(EvaluationContext context) {
            return List.class;
        }

        public boolean matches(EvaluationContext context, Object value) {
            List range = (List)getValue(context);
            return range.contains(value);
        }
        
        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class AST extends ELNode {
        public final ELNode exp;

        public AST(int pos, ELNode exp) {
            super(Token.AST, pos);
            this.exp = exp;
        }

        public Object getValue(EvaluationContext context) {
            return Expression.valueOf(new ASTTransformer(context).transform(exp));
        }

        public Class getType(EvaluationContext context) {
            return Expression.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    private static class ASTTransformer extends TreeTransformer {
        private final EvaluationContext env;

        public ASTTransformer(EvaluationContext env) {
            this.env = env;
        }

        public void visit(ELNode.IDENT e) {
            if (!e.id.startsWith("$")) {
                result = e;
                return;
            }

            ValueExpression ve = env.resolveVariable(e.id.substring(1));
            if (ve == null) {
                result = e;
            } else {
                result = transform(e.pos, ve.getValue(env.getELContext()));
            }
        }

        public ELNode[] transform(ELNode[] args) {
            if (args != null && args.length == 1 && args[0] != null && args[0].op == Token.IDENT) {
                String id = ((ELNode.IDENT)args[0]).id;
                if (id.startsWith("@")) {
                    ValueExpression ve = env.resolveVariable(id.substring(1));
                    if (ve != null) {
                        Object v = ve.getValue(env.getELContext());
                        if (v instanceof List) {
                            int pos = args[0].pos;
                            List list = (List)v;
                            ELNode[] result = new ELNode[list.size()];
                            for (int i = 0; i < result.length; i++)
                                result[i] = transform(pos, list.get(i));
                            return result;
                        }
                    }
                }
            }

            return super.transform(args);
        }

        protected ELNode transform(int pos, Object value) {
            if (value instanceof Expression) {
                return ((Expression)value).getNode(pos);
            } else if (value instanceof Symbol) {
                return new ELNode.IDENT(pos, ((Symbol)value).getName());
            } else {
                return Expression.CONST(value).getNode(pos);
            }
        }

        public String transform(String id) {
            if (id != null && id.startsWith("$")) {
                ValueExpression ve = env.resolveVariable(id.substring(1));
                if (ve != null) {
                    Object value = ve.getValue(env.getELContext());
                    if (value instanceof Symbol) {
                        id = ((Symbol)value).getName();
                    } else if (value instanceof String) {
                        id = (String)value;
                    }
                }
            }
            return id;
        }
    }

    public static class XML extends ELNode {
        public final ELNode tag;
        public final ELNode[] keys;
        public final ELNode[] values;
        public final ELNode[] children;

        public XML(int pos, ELNode tag, ELNode[] keys, ELNode[] values, ELNode[] children) {
            super(Token.XML, pos);
            this.tag = tag;
            this.keys = keys;
            this.values = values;
            this.children = children;
        }

        public Object getValue(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Document doc = XmlNode.getContextDocument(elctx);
            EvaluationContext env = context.pushContext();

            try {
                String[] att_names = null, att_values = null;
                if (keys != null && values != null) {
                    att_names = new String[keys.length];
                    att_values = new String[values.length];
                    
                    // get attributes
                    for (int i = 0; i < keys.length; i++) {
                        att_names[i] = coerceToString(keys[i].getValue(context));
                        att_values[i] = coerceToString(values[i].getValue(context));
                    }

                    // declare namespaces
                    for (int i = 0; i < att_names.length; i++) {
                        String name = att_names[i];
                        if (name.equals("xmlns")) {
                            env.declarePrefix(XMLConstants.DEFAULT_NS_PREFIX, att_values[i]);
                        } else if (name.startsWith("xmlns:")) {
                            env.declarePrefix(name.substring(6), att_values[i]);
                        }
                    }
                }

                Element elem;
                String name = coerceToString(tag.getValue(env));
                String prefix, uri;
                int colon;

                // handle element namespace
                colon = name.indexOf(':');
                if (colon == -1) {
                    prefix = XMLConstants.DEFAULT_NS_PREFIX;
                } else {
                    prefix = name.substring(0, colon);
                }

                uri = env.getURI(prefix);
                if (uri == null) {
                    elem = doc.createElement(name);
                } else {
                    elem = doc.createElementNS(uri, name);
                }

                // set element attributes
                if (att_names != null) {
                    for (int i = 0; i < att_names.length; i++) {
                        String key = att_names[i], value = att_values[i];

                        if (key.equals("xmlns") || key.startsWith("xmlns:")) {
                            prefix = XMLConstants.XMLNS_ATTRIBUTE;
                            uri = XMLConstants.XMLNS_ATTRIBUTE_NS_URI;
                        } else {
                            colon = key.indexOf(':');
                            if (colon == -1) {
                                prefix = XMLConstants.DEFAULT_NS_PREFIX;
                            } else {
                                prefix = key.substring(0, colon);
                            }
                            uri = env.getURI(prefix);
                        }

                        if (uri == null) {
                            elem.setAttribute(key, value);
                        } else {
                            elem.setAttributeNS(uri, key, value);
                        }
                    }
                }

                // recursively create child nodes
                if (children != null) {
                    for (int i = 0; i < children.length; i++) {
                        Object value = children[i].getValue(env);
                        org.w3c.dom.Node node = XmlNode.coerceToNode(elctx, value);
                        if (node != null) {
                            elem.appendChild(node);
                        }
                    }
                }

                return XmlNode.valueOf(elem);
            } catch (DOMException ex) {
                throw runtimeError(elctx, ex);
            }
        }

        public Class getType(EvaluationContext context) {
            return XmlNode.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * New expression.
     */
    public static class NEW extends ELNode implements Pattern {
        public final String base;
        public final ELNode[] args;
        public final String[] keys;
        public final MAP props;

        public NEW(int pos, String base, ELNode[] args, String[] keys, MAP props) {
            super(Token.NEW, pos);
            this.base = base;
            this.args = args;
            this.keys = keys;
            this.props = props;
        }

        public Object getValue(EvaluationContext context) {
            ELContext elctx = context.getELContext();
            Object cls = ELEngine.resolveClass(context, base);
            Closure[] argv = getCallArgs(context);

            Object obj;
            if (cls instanceof ClassDefinition) {
                obj = ((ClassDefinition)cls)._new(elctx, argv);
            } else {
                obj = ELEngine.newInstance(elctx, (Class)cls, argv);
            }

            if (obj != null && props != null) {
                if (obj instanceof ClosureObject) {
                    ClosureObject clo = (ClosureObject)obj;
                    for (int i = 0; i < props.keys.length; i++) {
                        Object key = props.keys[i].getValue(context);
                        Object value = props.values[i].getValue(context);
                        clo.setValue(elctx, key, value);
                    }
                } else {
                    ELResolver resolver = elctx.getELResolver();
                    for (int i = 0; i < props.keys.length; i++) {
                        Object key = props.keys[i].getValue(context);
                        Object value = props.values[i].getValue(context);
                        resolver.setValue(elctx, obj, key, value);
                    }
                }
            }

            return obj;
        }

        private Closure[] getCallArgs(EvaluationContext context) {
            if (args.length == 0) {
                return NO_PARAMS;
            }

            Closure[] extra = new Closure[args.length];
            for (int i = 0; i < extra.length; i++) {
                extra[i] = args[i].closure(context);
            }

            if (keys != null) {
                for (int i = 0; i < extra.length; i++) {
                    if (keys[i] != null) {
                        extra[i] = new NamedClosure(keys[i], extra[i]);
                    }
                }
            }

            return extra;
        }

        public Class getType(EvaluationContext context) {
            Object cls = ELEngine.resolveClass(context, base);
            if (cls instanceof Class) {
                return (Class)cls;
            } else {
                return ClosureObject.class;
            }
        }

        public boolean matches(EvaluationContext context, Object value) {
            if (value instanceof Enum && args.length == 0) {
                return base.equals(((Enum)value).name());
            }

            Object cls = ELEngine.resolveDataClass(context, base);
            if (cls instanceof ClassDefinition) {
                if (value instanceof ClosureObject) {
                    return ((ClassDefinition)cls).matches(context, (ClosureObject)value, args, keys);
                } else {
                    return false;
                }
            } else {
                return matches(context, (DataClass)cls, value);
            }
        }

        private boolean matches(EvaluationContext context, DataClass cls, Object obj) {
            ELContext elctx = context.getELContext();
            int argc = args.length;

            if (!cls.getJavaClass().isInstance(obj))
                return false;
            if (argc == 0)
                return true;

            String[] vars;
            if (keys != null) {
                vars = keys;
            } else {
                vars = cls.getSlots();
                if (vars == null || vars.length != argc) {
                    return false;
                }
            }

            try {
                ELResolver resolver = elctx.getELResolver();
                for (int i = 0; i < argc; i++) {
                    if (!isWildcard(args[i])) { // optimize
                        elctx.setPropertyResolved(false);
                        Object value = resolver.getValue(elctx, obj, vars[i]);
                        if (!elctx.isPropertyResolved())
                            return false;
                        if (!((ELNode.Pattern)args[i]).matches(context, value))
                            return false;
                    }
                }
            } catch (PropertyNotFoundException ex) {
                return false;
            }

            return true;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * New object expression.
     */
    public static class NEWOBJ extends CLASSDEF {
        public NEWOBJ(int pos, String file, String base, String tag, DEFINE[] body) {
            super(pos, file, tag, base, null, null, body);
        }

        public NEWOBJ(int pos, String file, String base, String tag, DEFINE[] cvars, DEFINE[] ivars) {
            super(pos, file, tag, base, null, null, cvars, ivars);
        }

        public Object getValue(EvaluationContext context) {
            ClassDefinition cdef = new ClassDefinition(context, this);
            return cdef._new(context.getELContext());
        }

        public Class getType(EvaluationContext context) {
            Object cls = ELEngine.resolveClass(context, base);
            if (cls instanceof Class) {
                return (Class)cls;
            } else {
                return Object.class;
            }
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class METADATA extends ELNode {
        public final String type;
        public final String[] keys;
        public final ELNode[] values;

        public METADATA(int pos, String type, String[] keys, ELNode[] values) {
            super(Token.METADATA, pos);
            this.type = type;
            this.keys = keys;
            this.values = values;
        }

        public Object getValue(EvaluationContext context) {
            assert keys.length == values.length;
            Annotation a = new Annotation(type);
            for (int i = 0; i < keys.length; i++) {
                a.setAttribute(keys[i], values[i].getValue(context));
            }
            return a;
        }

        public Class getType(EvaluationContext context) {
            return Annotation.class;
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    public static class METASET extends ELNode {
        public final METADATA[] metadata;
        public final int modifiers;

        public METASET(int pos, int modifiers) {
            super(Token.METASET, pos);
            this.metadata = new METADATA[0];
            this.modifiers = modifiers;
        }

        public METASET(int pos, METADATA[] metadata, int modifiers) {
            super(Token.METASET, pos);
            this.metadata = metadata;
            this.modifiers = modifiers;
        }

        public METASET adjoin(METASET that) {
            int n = this.metadata.length;
            METADATA[] data = new METADATA[n + that.metadata.length];
            System.arraycopy(this.metadata, 0, data, 0, n);

        loop:
            for (METADATA t : that.metadata) {
                for (int i = 0; i < n; i++) {
                    if (t.type.equals(this.metadata[i].type)) {
                        data[i] = t;
                        continue loop;
                    }
                }
                data[n++] = t;
            }

            if (n != data.length) {
                METADATA[] newdata = new METADATA[n];
                System.arraycopy(data, 0, newdata, 0, n);
                data = newdata;
            }

            return new METASET(pos, data, this.modifiers | that.modifiers);
        }

        public Object getValue(EvaluationContext context) {
            assert false;
            return null;
        }

        public Class getType(EvaluationContext context) {
            assert false;
            return null;
        }

        public MetaData getMetaData(EvaluationContext context) {
            Annotation[] annotations = new Annotation[metadata.length];
            for (int i = 0; i < metadata.length; i++)
                annotations[i] = (Annotation)metadata[i].getValue(context);
            return new MetaData(annotations, modifiers);
        }

        public void accept(Visitor v) {
            v.visit(this);
        }
    }

    /**
     * The generic visitor for the tree.
     */
    public static abstract class Visitor {
        public void visit(Composite e)  { visitNode(e); }
        public void visit(LAMBDA e)     { visitNode(e); }
        public void visit(DEFINE e)     { visitNode(e); }
        public void visit(CLASSDEF e)   { visitNode(e); }
        public void visit(UNDEF e)      { visitNode(e); }
        public void visit(IDENT e)      { visitNode(e); }
        public void visit(ACCESS e)     { visitNode(e); }
        public void visit(APPLY e)      { visitNode(e); }
        public void visit(XFORM e)      { visitNode(e); }
        public void visit(PREFIX e)     { visitNode(e); }
        public void visit(INFIX e)      { visitNode(e); }
        public void visit(THEN e)       { visitNode(e); }
        public void visit(ASSIGN e)     { visitNode(e); }
        public void visit(COND e)       { visitNode(e); }
        public void visit(COALESCE e)   { visitNode(e); }
        public void visit(SAFEREF e)    { visitNode(e); }
        public void visit(OR e)         { visitNode(e); }
        public void visit(AND e)        { visitNode(e); }
        public void visit(BITOR e)      { visitNode(e); }
        public void visit(BITAND e)     { visitNode(e); }
        public void visit(XOR e)        { visitNode(e); }
        public void visit(SHL e)        { visitNode(e); }
        public void visit(SHR e)        { visitNode(e); }
        public void visit(USHR e)       { visitNode(e); }
        public void visit(EQ e)         { visitNode(e); }
        public void visit(NE e)         { visitNode(e); }
        public void visit(IDEQ e)       { visitNode(e); }
        public void visit(IDNE e)       { visitNode(e); }
        public void visit(LT e)         { visitNode(e); }
        public void visit(LE e)         { visitNode(e); }
        public void visit(GT e)         { visitNode(e); }
        public void visit(GE e)         { visitNode(e); }
        public void visit(INSTANCEOF e) { visitNode(e); }
        public void visit(IN e)         { visitNode(e); }
        public void visit(CAT e)        { visitNode(e); }
        public void visit(ADD e)        { visitNode(e); }
        public void visit(SUB e)        { visitNode(e); }
        public void visit(MUL e)        { visitNode(e); }
        public void visit(DIV e)        { visitNode(e); }
        public void visit(REM e)        { visitNode(e); }
        public void visit(POW e)        { visitNode(e); }
        public void visit(BITNOT e)     { visitNode(e); }
        public void visit(POS e)        { visitNode(e); }
        public void visit(NEG e)        { visitNode(e); }
        public void visit(INC e)        { visitNode(e); }
        public void visit(DEC e)        { visitNode(e); }
        public void visit(NOT e)        { visitNode(e); }
        public void visit(EMPTY e)      { visitNode(e); }
        public void visit(EXPR e)       { visitNode(e); }
        public void visit(COMPOUND e)   { visitNode(e); }
        public void visit(WHILE e)      { visitNode(e); }
        public void visit(FOR e)        { visitNode(e); }
        public void visit(FOREACH e)    { visitNode(e); }
        public void visit(MATCH e)      { visitNode(e); }
        public void visit(CASE e)       { visitNode(e); }
        public void visit(LET e)        { visitNode(e); }
        public void visit(BREAK e)      { visitNode(e); }
        public void visit(CONTINUE e)   { visitNode(e); }
        public void visit(RETURN e)     { visitNode(e); }
        public void visit(THROW e)      { visitNode(e); }
        public void visit(TRY e)        { visitNode(e); }
        public void visit(CATCH e)      { visitNode(e); }
        public void visit(SYNCHRONIZED e) { visitNode(e); }
        public void visit(ASSERT e)     { visitNode(e); }
        public void visit(CONST e)      { visitNode(e); }
        public void visit(BOOLEANVAL e) { visitNode(e); }
        public void visit(CHARVAL e)    { visitNode(e); }
        public void visit(NUMBER e)     { visitNode(e); }
        public void visit(SYMBOL e)     { visitNode(e); }
        public void visit(STRINGVAL e)  { visitNode(e); }
        public void visit(REGEXP e)     { visitNode(e); }
        public void visit(LITERAL e)    { visitNode(e); }
        public void visit(NULL e)       { visitNode(e); }
        public void visit(CLASS e)      { visitNode(e); }
        public void visit(ARRAY e)      { visitNode(e); }
        public void visit(CONS e)       { visitNode(e); }
        public void visit(NIL e)        { visitNode(e); }
        public void visit(TUPLE e)      { visitNode(e); }
        public void visit(MAP e)        { visitNode(e); }
        public void visit(RANGE e)      { visitNode(e); }
        public void visit(AST e)        { visitNode(e); }
        public void visit(XML e)        { visitNode(e); }
        public void visit(NEW e)        { visitNode(e); }
        public void visit(NEWOBJ e)     { visitNode(e); }
        public void visit(METADATA e)   { visitNode(e); }
        public void visit(METASET e)    { visitNode(e); }

        public void visitNode(ELNode e) { assert false; }
    }
}
