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

package org.operamasks.el.eval.closure;

import java.io.Serializable;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.lang.reflect.Modifier;
import javax.el.ELContext;
import javax.el.ValueExpression;
import javax.el.VariableMapper;
import javax.el.FunctionMapper;
import javax.el.MethodInfo;
import javax.el.PropertyNotWritableException;

import elite.lang.Closure;
import elite.lang.Annotation;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.VariableMapperImpl;
import org.operamasks.el.eval.MethodResolvable;
import org.operamasks.el.eval.PropertyResolvable;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.StackTrace;
import org.operamasks.el.eval.EvaluationException;
import static org.operamasks.el.eval.ELEngine.resolveClass;
import static org.operamasks.el.eval.ELEngine.resolveJavaClass;
import static org.operamasks.el.resources.Resources.*;

public class ClassDefinition extends AnnotatedClosure
    implements PropertyResolvable, MethodResolvable, Serializable
{
    private transient EvaluationContext env;
    private ELNode.CLASSDEF     cdef;
    private FunctionMapper      fm;
    private VariableMapper      vm;
    private transient Object    basecls;
    private transient Class[]   interfaces;
    private VariableMapper      cvmap;
    private Map<String,Closure> expando;

    private static final long serialVersionUID = 2605237093923769160L;

    public static final String INIT_PROC = "__init__";
    public static final String CLINIT_PROC = "__clinit__";

    private volatile int init_state = NOT_INITIALIZED;
    private transient Thread init_thread = null;
    private transient Object singleton = null;

    private static final int NOT_INITIALIZED = 0;
    private static final int INITIALIZE_PENDING = 1;
    private static final int INITIALIZED = 2;

    public ClassDefinition(EvaluationContext env, ELNode.CLASSDEF cdef) {
        this.env     = env;
        this.cdef    = cdef;
        this.fm      = env.getFunctionMapper();
        this.vm      = null; // build it on demand
        this.expando = new LinkedHashMap<String,Closure>();
    }

    private void init(ELContext elctx) {
        if (init_state == INITIALIZED) {
            return;
        }

        // Initialize class once with single thead
        synchronized (this) {
            do {
                if (init_state == INITIALIZED) {
                    notify();
                    return;
                } else if (init_state == NOT_INITIALIZED) {
                    init_state = INITIALIZE_PENDING;
                    init_thread = Thread.currentThread();
                    break;
                } else if (init_thread == Thread.currentThread()) {
                    return;
                } else {
                    try {
                        wait();
                    } catch (InterruptedException ex) {
                        notify();
                        throw new ExceptionInInitializerError(ex);
                    }
                }
            } while (true);
        }

        try {
            EvaluationContext ctx = getContext(elctx);

            // Resolve base class and interfaces
            if (cdef.base != null) {
                basecls = resolveClass(ctx, cdef.base);
            }
            if (cdef.ifaces != null) {
                interfaces = new Class[cdef.ifaces.length];
                for (int i = 0; i < interfaces.length; i++) {
                    interfaces[i] = resolveJavaClass(elctx, cdef.ifaces[i]);
                    if (!interfaces[i].isInterface()) {
                        throw new EvaluationException(elctx, "interface expected: " + cdef.ifaces[i]);
                    }
                }
            }

            if (basecls instanceof ClassDefinition) {
                // Recurisively initialize base class
                ClassDefinition base = (ClassDefinition)basecls;
                if (base.isFinal())
                    throw new EvaluationException(elctx, _T(EL_SUBCLASS_FINAL, cdef.base));
                base.init(elctx);
            } else if (basecls != null && basecls != Object.class) {
                // Check for base class
                Class base = (Class)basecls;
                if (base.isInterface()) {
                    if (interfaces != null) {
                        throw new EvaluationException(elctx, "class expected: " + cdef.base);
                    } else {
                        basecls = null;
                        interfaces = new Class[]{base};
                    }
                } else if (Modifier.isFinal(base.getModifiers())) {
                    throw new EvaluationException(elctx, _T(EL_SUBCLASS_FINAL, cdef.base));
                }
            } else {
                basecls = null;
            }

            // Create static class variable mapper
            if (cdef.cvars.length != 0) {
                cvmap = new VariableMapperImpl();
                cvmap.setVariable(cdef.id, this);

                for (ELNode.DEFINE def : cdef.cvars) {
                    Closure cvar = def.defineClosure(env);
                    cvar._setenv(elctx, new ClassEnvironment(this));
                    cvmap.setVariable(def.id, cvar);
                }

                // Invoke class initialization procedure
                Closure clinit = (Closure) cvmap.resolveVariable(CLINIT_PROC);
                if (clinit != null) {
                    clinit.call(elctx);
                    cvmap.setVariable(CLINIT_PROC, null); // no longer used
                }
            }
        } catch (Throwable ex) {
            synchronized (this) {
                cvmap = null;
                basecls = null;
                interfaces = null;
                init_state = NOT_INITIALIZED;
                init_thread = null;
                notify();
                throw new ExceptionInInitializerError(ex);
            }
        }

        synchronized (this) {
            init_state = INITIALIZED;
            init_thread = null;
            notify();
        }
    }

    @Override
    public EvaluationContext getContext() {
        return env;
    }

    @Override
    public EvaluationContext getContext(ELContext elctx) {
        if (env == null) {
            // create environment from variable mapper(s).
            if (elctx == null)
                elctx = ELEngine.getCurrentELContext();
            env = new EvaluationContext(elctx, fm, vm);
        } else {
            // reinitialize environment
            if (elctx != null) {
                env.setELContext(elctx);
            }
        }
        return env;
    }

    public void _setenv(ELContext elctx, VariableMapper env) {
        this.env = getContext(elctx).pushContext(env);
    }
    
    public String getName() {
        return cdef.id;
    }

    public ClassDefinition getBaseClass(ELContext elctx) {
        if (cdef.base != null) {
            init(elctx);
            if (basecls instanceof ClassDefinition) {
                return (ClassDefinition)basecls;
            }
        }
        return null;
    }

    public boolean isAssignableFrom(ELContext elctx, ClassDefinition cls) {
        while (cls != null) {
            if (cls.equals(this))
                return true;
            cls = cls.getBaseClass(elctx);
        }
        return false;
    }

    public boolean isInstance(ELContext elctx, Object obj) {
        if (obj instanceof ClosureObject) {
            return this.isAssignableFrom(elctx, ((ClosureObject)obj).get_class());
        } else {
            return false;
        }
    }

    public String[] getDataSlots() {
        if (cdef.vars == null) {
            return null;
        } else {
            String[] slots = new String[cdef.vars.length];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = cdef.vars[i].id;
            }
            return slots;
        }
    }

    public boolean isImmutable() {
        Annotation at = getAnnotation("data");
        if (at != null) {
            Object mutable = at.getAttribute("mutable");
            if (mutable == null) {
                return true;
            } else {
                return !TypeCoercion.coerceToBoolean(mutable);
            }
        } else {
            return false;
        }
    }

    public void attach(String name, Closure closure) {
        expando.put(name, closure);
    }

    public void detach(String name) {
        expando.remove(name);
    }

    public Closure getExpandoClosure(String name) {
        return expando.get(name);
    }

    public Object _new(ELContext elctx, Closure... args) {
        if (getAnnotation("Singleton") != null) {
            synchronized (this) {
                if (singleton == null)
                    singleton = newInstance(elctx, args);
                return singleton;
            }
        } else {
            return newInstance(elctx, args);
        }
    }

    private Object newInstance(ELContext elctx, Closure[] args) {
        // Initialize class for first instance instantiation.
        init(elctx);

        // create the underlying closure object
        ThisObject thisObj = createThisObject(elctx, this);
        ClosureObject outer = new DefaultClosureObject(thisObj);

        // invoke the initialization procedure
        thisObj.setOwner(outer);
        thisObj.init(elctx, args);

        // return the proxy object
        return thisObj.createProxy(elctx);
    }

    private ThisObject createThisObject(ELContext elctx, ClassDefinition orig) {
        StackTrace.addFrame(elctx, cdef.id, cdef.file, cdef.pos);

        try {
            EvaluationContext ctx = getContext(elctx);

            // Initialize base object
            ThisObject baseObj = null;
            if (basecls instanceof ClassDefinition) {
                if (orig.equals(basecls))
                    throw new EvaluationException(elctx, _T(EL_CIRCULAR_CLASS_DEFINITION));
                baseObj = ((ClassDefinition)basecls).createThisObject(elctx, orig);
            }

            // Create variable mapper that contains all instance variables
            Map<String,Closure> vmap = new LinkedHashMap<String,Closure>();
            for (ELNode.DEFINE def : cdef.ivars) {
                vmap.put(def.id, def.defineClosure(ctx));
            }
            addMixins(elctx, vmap);

            // Create the this object
            ThisObject thisObj;
            if (baseObj != null) {
                thisObj = new DerivedThisObject(elctx, this, vmap, baseObj);
            } else if (basecls != null) {
                thisObj = new ProxiedThisObject(elctx, this, vmap, (Class)basecls);
            } else {
                thisObj = new BasicThisObject(elctx, this, vmap);
            }

            if (interfaces != null) {
                for (Class c : interfaces) {
                    thisObj.addInterface(c);
                }
            }

            // Post initialize this object
            initialize(thisObj);

            // create delegated this object
            Closure[] delegates = getDelegates(vmap);
            if (delegates != null) {
                thisObj = new DelegatedThisObject(thisObj, delegates);
            }

            return thisObj;
        } catch (EvaluationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new EvaluationException(elctx, ex);
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    private void addMixins(ELContext elctx, Map<String,Closure> vmap) {
        for (Annotation at : getAnnotations()) {
            if (at.getAnnotationType().equals("Mixin")) {
                Object value = at.getAttribute("value");
                if (value instanceof List) {
                    for (Object e : (List)value) {
                        mixin(elctx, e, null, vmap);
                    }
                } else if (value != null) {
                    Map rename = (Map)at.getAttribute("rename");
                    mixin(elctx, value, rename, vmap);
                }
            }
        }
    }

    private void mixin(ELContext elctx, Object obj, Map rename, Map<String,Closure> vmap) {
        ClosureObject mixin;

        if (obj instanceof ClosureObject) {
            mixin = (ClosureObject)obj;
        } else if (obj instanceof ClassDefinition) {
            mixin = (ClosureObject)((ClassDefinition)obj)._new(elctx);
        } else {
            throw new EvaluationException(elctx, "Invalid mixin object: " + obj);
        }

        for (Map.Entry<String,Closure> e : mixin.get_closures(elctx).entrySet()) {
            String name = e.getKey();
            if (rename != null && rename.containsKey(name)) {
                name = (String)rename.get(name);
                if (name == null) continue; // name not included
            }
            if (!vmap.containsKey(name)) {
                vmap.put(name, e.getValue());
            } else {
                // FIXME: report warning
            }
        }
    }

    private Closure[] getDelegates(Map<String,Closure> vmap) {
        List<Closure> delegates = null;
        for (Closure c : vmap.values()) {
            if (c.isAnnotationPresent("delegate")) {
                if (delegates == null)
                    delegates = new ArrayList<Closure>();
                delegates.add(c);
            }
        }

        if (delegates == null) {
            return null;
        } else {
            return delegates.toArray(new Closure[delegates.size()]);
        }
    }

    private void initialize(ThisObject thisObj) {
        Map<String,Closure> vmap = thisObj.getClosureMap();
        Closure initproc = vmap.get(cdef.id);

        // create initialization procedure
        if (cdef.vars != null) {
            initproc = new InitProc(thisObj, initproc);
        }

        // replace initialization procedure
        if (initproc != null) {
            vmap.put(INIT_PROC, initproc);
            vmap.remove(cdef.id);
        }

        // == and equals are identical, so if == defined then equals is also defined
        if (vmap.containsKey("==") && !vmap.containsKey("equals")) {
            vmap.put("equals", vmap.get("=="));
        }

        // create default toString, equals, hashCode procedures
        if (cdef.vars != null) {
            if (!vmap.containsKey("toString"))
                vmap.put("toString", new ToStringProc(thisObj));
            if (!vmap.containsKey("equals"))
                vmap.put("equals", new EqualsProc(thisObj));
            if (!vmap.containsKey("hashCode"))
                vmap.put("hashCode", new HashCodeProc(thisObj));
        }

        // If a class defines equals and < procedures then this class
        // is a Comparable object. The compareTo method of Comparable
        // interface is implemented using equals and < procedures.
        if (vmap.containsKey("equals") && vmap.containsKey("<")) {
            if (!vmap.containsKey("compareTo")) {
                thisObj.addInterface(Comparable.class);
                vmap.put("compareTo", new CompareToProc(thisObj));
            }
        }
    }

    private static class InitProc extends AbstractClosure {
        private ThisObject obj;
        private Closure init;

        InitProc(ThisObject obj, Closure init) {
            this.obj = obj;
            this.init = init;
        }

        public Object invoke(ELContext elctx, Closure[] args) {
            ClassDefinition cls = obj.get_class();
            StackTrace.addFrame(elctx, cls.cdef.id + ".__init__", cls.cdef.file, cls.cdef.pos);

            try {
                EvaluationContext ctx = cls.getContext(elctx);
                ELNode.DEFINE[] vars = cls.cdef.vars;
                int argc = args.length;
                int nvars = vars.length;
                Closure[] xargs = null;

                if (argc < nvars) {
                    // pad with default values
                    xargs = new Closure[nvars];
                } else if (argc > nvars) {
                    throw new EvaluationException(
                        elctx, _T(EL_FN_BAD_ARG_COUNT, cls.cdef.id, nvars, argc));
                }

                // rearrange named arguments
                for (int i = 0; i < argc; i++) {
                    if (args[i] instanceof NamedClosure) {
                        NamedClosure c = (NamedClosure)args[i];
                        int j = indexOfVar(c.name(), vars);
                        if (j == -1)
                            throw new EvaluationException(elctx, _T(EL_UNKNOWN_ARG_NAME, c.name()));
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
                    args = xargs;
                }

                // define initialization variables
                Map<String,Closure> vmap = obj.getClosureMap();
                boolean immutable = cls.isImmutable();
                for (int i = 0; i < nvars; i++) {
                    ELNode.DEFINE var = vars[i];
                    Closure c = args[i];
                    if (c == null) {
                        if (var.expr != null) {
                            c = var.expr.closure(ctx);
                        } else {
                            throw new EvaluationException(elctx, _T(EL_MISSING_ARG_VALUE, var.id));
                        }
                    }
                    if (var.type != null)
                        c = TypedClosure.make(ctx, var.type, c);
                    if (immutable)
                        c.setModifiers(c.getModifiers() | Modifier.FINAL);
                    if (var.immediate)
                        c.getValue(elctx);
                    vmap.put(var.id, c);
                }

                // invoke original initialization procedure
                if (init != null) {
                    init.call(elctx);
                }

                // the initialization procedure is no longer needed
                vmap.remove(INIT_PROC);

                return null;

            } catch (EvaluationException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new EvaluationException(elctx, ex);
            } finally {
                StackTrace.removeFrame(elctx);
            }
        }

        public int arity(ELContext elctx) {
            return obj.get_class().cdef.vars.length;
        }

        private static int indexOfVar(String name, ELNode.DEFINE[] vars) {
            for (int i = 0; i < vars.length; i++) {
                if (name.equals(vars[i].id)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static class ToStringProc extends AbstractClosure {
        private ThisObject thiz;

        ToStringProc(ThisObject obj) {
            thiz = obj;
        }

        public Object invoke(ELContext elctx, Closure[] args) {
            if (args.length != 0) {
                throw new EvaluationException(
                    elctx, _T(EL_FN_BAD_ARG_COUNT, "toString", 0, args.length));
            }

            ClassDefinition cls = thiz.get_class();
            ELNode.DEFINE[] vars = cls.cdef.vars;

            StringBuilder buf = new StringBuilder();
            buf.append(cls.cdef.id);
            buf.append("(");
            for (int i = 0; i < vars.length; i++) {
                if (i > 0) buf.append(", ");
                Object value = thiz.get_closure(elctx, vars[i].id).getValue(elctx);
                if (value instanceof String) {
                    TypeCoercion.escape(buf, (String)value);
                } else {
                    buf.append(TypeCoercion.coerceToString(value));
                }
            }
            buf.append(")");
            return buf.toString();
        }

        public int arity(ELContext elctx) {
            return 0;
        }

        public boolean isProcedure() {
            return true;
        }

        public String toString() {
            return "#<procedure:toString>";
        }
    }

    private static class EqualsProc extends AbstractClosure {
        private ThisObject thiz;

        EqualsProc(ThisObject obj) {
            this.thiz = obj;
        }

        public Object invoke(ELContext elctx, Closure[] args) {
            if (args.length != 1) {
                throw new EvaluationException(
                    elctx, _T(EL_FN_BAD_ARG_COUNT, "equals", 1, args.length));
            }

            ClassDefinition cls = thiz.get_class();
            Object obj = args[0].getValue(elctx);
            if (!cls.isInstance(elctx, obj)) {
                return Boolean.FALSE;
            }

            ClosureObject that = ((ClosureObject)obj).get_this();
            for (ELNode.DEFINE var : cls.cdef.vars) {
                Object v1 = thiz.get_closure(elctx, var.id).getValue(elctx);
                Object v2 = that.get_closure(elctx, var.id).getValue(elctx);
                if (!ELNode.EQ.equals(elctx, v1, v2))
                    return Boolean.FALSE;
            }

            // TODO: super.equals(obj)?
            return Boolean.TRUE;
        }

        public int arity(ELContext elctx) {
            return 1;
        }

        public boolean isProcedure() {
            return true;
        }

        public String toString() {
            return "#<procedure:equals>";
        }
    }

    private static class HashCodeProc extends AbstractClosure {
        private ThisObject thiz;

        HashCodeProc(ThisObject obj) {
            thiz = obj;
        }

        public Object invoke(ELContext elctx, Closure[] args) {
            if (args.length != 0) {
                throw new EvaluationException(
                    elctx, _T(EL_FN_BAD_ARG_COUNT, "hashCode", 0, args.length));
            }

            int hash = 0;
            for (ELNode.DEFINE var : thiz.get_class().cdef.vars) {
                Object value = thiz.get_closure(elctx, var.id).getValue(elctx);
                hash = 31*hash + ((value==null) ? 0 : value.hashCode());
            }
            return hash;
        }

        public int arity(ELContext elctx) {
            return 0;
        }

        public boolean isProcedure() {
            return true;
        }

        public String toString() {
            return "#<procedure:hashCode>";
        }
    }

    private static class CompareToProc extends AbstractClosure {
        private ThisObject thiz;

        CompareToProc(ThisObject obj) {
            thiz = obj;
        }
        
        public Object invoke(ELContext elctx, Closure[] args) {
            if (TypeCoercion.coerceToBoolean(thiz.invokePublic(elctx, "equals", args))) {
                return 0;
            } else if (TypeCoercion.coerceToBoolean(thiz.invokePublic(elctx, "<", args))) {
                return -1;
            } else {
                return 1;
            }
        }

        public int arity(ELContext elctx) {
            return 1;
        }

        public boolean isProcedure() {
            return true;
        }

        public String toString() {
            return "#<procedure:compareTo>";
        }
    }

    // Pattern matching -------------------------------
    
    public boolean matches(EvaluationContext context, ClosureObject obj, ELNode[] args, String[] keys) {
        ELContext elctx = context.getELContext();
        ClosureObject target = obj.get_owner();
        ELNode.DEFINE[] vars = cdef.vars;
        int argc = args.length;
        boolean matched = true;

        // pre-conditions
        if (!isAssignableFrom(elctx, target.get_class()))
            return false;
        if (argc == 0)
            return true;
        if (keys == null && (vars == null || vars.length != argc))
            return false;

        if (keys != null) {
            // matches for closure object properties
            for (int i = 0; matched && i < argc; i++) {
                elctx.setPropertyResolved(false);
                Object value = target.getValue(elctx, keys[i]);
                matched = elctx.isPropertyResolved()
                     && ((ELNode.Pattern)args[i]).matches(context, value);
            }
        } else {
            // matches for constructor variables
            target = target.get_this();
            for (int i = 0; matched && i < argc; i++) {
                ELNode arg = args[i];
                Closure c = target.get_closure(elctx, vars[i].id);
                if (arg instanceof ELNode.DEFINE && !vars[i].immediate) {
                    matched = ((ELNode.DEFINE)arg).bind(context, c);
                } else {
                    matched = ((ELNode.Pattern)arg).matches(context, c.getValue(elctx));
                }
            }
        }

        return matched;
    }

    // Static member variables ------------------------

    public Closure getClosure(ELContext elctx, String name) {
        init(elctx); // initialize class for first call

        ClassDefinition cdef = this;
        while (cdef != null) {
            if (cdef.cvmap != null) {
                Closure c = (Closure)cdef.cvmap.resolveVariable(name);
                if (c != null && c.isPublic()) {
                    return c;
                }
            }
            cdef = cdef.getBaseClass(elctx);
        }
        return null;
    }

    protected Closure getPrivateClosure(ELContext elctx, String name) {
        if (this.cvmap != null) {
            Closure c = (Closure)this.cvmap.resolveVariable(name);
            if (c != null) {
                return c;
            }
        }

        ClassDefinition base = this;
        while ((base = base.getBaseClass(elctx)) != null) {
            if (base.cvmap != null) {
                Closure c = (Closure)base.cvmap.resolveVariable(name);
                if (c != null && !c.isPrivate()) {
                    return c;
                }
            }
        }

        return null;
    }
    
    public Object getValue(ELContext elctx, Object property) {
        if (property instanceof String) {
            Closure c = getClosure(elctx, (String)property);
            if (c != null) {
                Object r = c.getValue(elctx);
                elctx.setPropertyResolved(true);
                return r;
            }
        }
        return null;
    }

    public Class<?> getType(ELContext elctx, Object property) {
        if (property instanceof String) {
            Closure c = getClosure(elctx, (String)property);
            if (c != null) {
                Class<?> r = c.getType(elctx);
                elctx.setPropertyResolved(true);
                return r;
            }
        }
        return null;
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        if (property instanceof String) {
            Closure c = getClosure(elctx, (String)property);
            if (c != null) {
                c.setValue(elctx, value);
                elctx.setPropertyResolved(true);
            }
        }
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        if (property instanceof String) {
            Closure c = getClosure(elctx, (String)property);
            if (c != null) {
                boolean r = c.isReadOnly(elctx);
                elctx.setPropertyResolved(true);
                return r;
            }
        }
        return false;
    }

    public MethodInfo getMethodInfo(ELContext elctx, String name) {
        Closure c = getClosure(elctx, name);
        if (c != null) {
            return c.getMethodInfo(elctx);
        } else {
            throw new EvaluationException(elctx, _T(EL_METHOD_NOT_FOUND, cdef.id, name));
        }
    }

    public Object invoke(ELContext elctx, String name, Closure[] args) {
        Closure proc = getClosure(elctx, name);
        if (proc != null) {
            return invokeInScope(elctx, proc, args);
        } else {
            throw new EvaluationException(elctx, _T(EL_METHOD_NOT_FOUND, cdef.id, name));
        }
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        // Foo(a,b,c...) ===> Foo.valueOf(a,b,c...)
        Closure proc = getClosure(elctx, "valueOf");
        if (proc != null) {
            return invokeInScope(elctx, proc, args);
        } else {
            return _new(elctx, args);
        }
    }

    public int arity(ELContext elctx) {
        Closure proc = getClosure(elctx, "valueOf");
        if (proc != null) {
            return proc.arity(elctx);
        } else if (cdef.vars != null) {
            return cdef.vars.length;
        } else {
            return -1;
        }
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        Closure proc = getClosure(elctx, "valueOf");
        if (proc != null) {
            return proc.getMethodInfo(elctx);
        } else if (cdef.vars != null) {
            Class[] args = new Class[cdef.vars.length];
            Arrays.fill(args, Object.class);
            return new MethodInfo("valueOf", Object.class, args);
        } else {
            throw new EvaluationException(elctx, _T(EL_METHOD_NOT_FOUND, cdef.id, "valueOf"));
        }
    }

    /**
     * Evaluation context for static class member procedures.
     */
    static class ClassEnvironment extends VariableMapper {
        protected ClassDefinition cdef;

        ClassEnvironment(ClassDefinition cdef) {
            this.cdef = cdef;
        }

        public ValueExpression resolveVariable(String name) {
            ELContext elctx = cdef.getContext().getELContext();
            return cdef.getPrivateClosure(elctx, name);
        }

        public ValueExpression setVariable(String name, ValueExpression value) {
            return null;
        }
    }

    // Invocation Scope ------------------

    private static final ThreadLocal<ClassDefinition[]> invoke_scope =
        new ThreadLocal<ClassDefinition[]>() {
            protected ClassDefinition[] initialValue() {
                // optimize: use a single element array of scope object
                // so thread local accessed only once instead of 3 times.
                return new ClassDefinition[1];
            }
        };

    /**
     * Setup an invocation scope. The private member for object of the same class
     * can be accessed within the scope.
     */
    public Object invokeInScope(ELContext elctx, Closure proc, Closure[] args) {
        if (proc instanceof BasicThisObject.ExpandoClosure) {
            // no private accessibility for expando closures
            return proc.invoke(elctx, args);
        } else {
            // force to evaluate arguments before enter current scope.
            for (Closure c : args) {
                if (c instanceof DelayClosure) {
                    Object value = c.getValue(elctx);
                    if (value instanceof ELNode.VarArgList) {
                        ((ELNode.VarArgList)value).force(elctx);
                    }
                }
            }
            
            ClassDefinition[] scope = invoke_scope.get();
            ClassDefinition prev = scope[0]; scope[0] = this;
            try {
                return proc.invoke(elctx, args);
            } finally {
                scope[0] = prev;
            }
        }
    }

    /**
     * Determines current class is within the scope.
     */
    public boolean inScope(ELContext elctx) {
        ClassDefinition scope = invoke_scope.get()[0];
        if (scope == null) {
            return false;
        } else if (scope == this) {
            return true;
        } else {
            return scope.isAssignableFrom(elctx, this);
        }
    }

    // Others -----------------------------

    public Object getValue(ELContext context) {
        return this;
    }

    public void setValue(ELContext context, Object value) {
        throw new PropertyNotWritableException();
    }

    public boolean isReadOnly(ELContext context) {
        return true;
    }

    public Class<?> getType(ELContext context) {
        return ClassDefinition.class;
    }

    public Class<?> getExpectedType() {
        return ClassDefinition.class;
    }

    public String getExpressionString() {
        return null;
    }

    public boolean isLiteralText() {
        return false;
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj instanceof ClassDefinition) {
            ClassDefinition other = (ClassDefinition)obj;
            return this.cdef == other.cdef;
        } else {
            return false;
        }
    }

    public int hashCode() {
        return cdef.hashCode();
    }

    public String toString() {
        return "#<class:" + cdef.id + ">";
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        // rebuild variable mapper to be saved
        if (this.env != null) {
            this.vm = VariableMapperBuilder.build(this.env, this.cdef);
        }

        out.defaultWriteObject();
    }

    private static class VariableMapperBuilder extends VariableMapper {
        EvaluationContext source;
        VariableMapper target;

        private VariableMapperBuilder(EvaluationContext source) {
            this.source = source;
        }

        public ValueExpression resolveVariable(String name) {
            ValueExpression value = source.resolveVariable(name);
            if (value != null) {
                if (target == null)
                    target = new VariableMapperImpl();
                target.setVariable(name, value);
            }
            return value;
        }

        public ValueExpression setVariable(String name, ValueExpression value) {
            throw new IllegalStateException();
        }

        public static VariableMapper build(EvaluationContext context, ELNode node) {
            VariableMapperBuilder vmb = new VariableMapperBuilder(context);
            node.applyVariableMapper(vmb);
            return vmb.target;
        }
    }
}
