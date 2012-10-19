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
package org.operamasks.el.eval;

import javax.el.ExpressionFactory;
import javax.el.ELContext;
import javax.el.ELResolver;
import javax.el.CompositeELResolver;
import javax.el.ResourceBundleELResolver;
import javax.el.ValueExpression;
import javax.el.MethodInfo;
import javax.el.VariableMapper;
import javax.el.ELContextListener;
import javax.el.ELContextEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.math.BigInteger;
import java.math.BigDecimal;
import elite.lang.Rational;
import elite.lang.Decimal;
import elite.lang.Closure;
import elite.lang.annotation.Data;

import org.operamasks.el.parser.Parser;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.el.eval.closure.ClassDefinition;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.eval.closure.MethodClosure;
import org.operamasks.el.eval.closure.DataClass;
import org.operamasks.el.resolver.ArrayELResolver;
import org.operamasks.el.resolver.BeanPropertyELResolver;
import org.operamasks.el.resolver.ListELResolver;
import org.operamasks.el.resolver.MapELResolver;
import org.operamasks.el.resolver.SeqELResolver;
import org.operamasks.el.resolver.StringELResolver;
import org.operamasks.el.resolver.SystemClassELResolver;
import org.operamasks.el.resolver.ClassResolver;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.el.resolver.UnitELResolver;
import static org.operamasks.el.eval.TypeCoercion.*;
import static org.operamasks.el.resources.Resources.*;

/**
 * ELEngine包含一些ELite和Java集成相关的底层全局方法.
 */
public final class ELEngine
{
    private ELEngine() {}

    static final ExpressionFactoryImpl factory = new ExpressionFactoryImpl();

    // The global ELContextListener registration
    private static final List<ELContextListener> listeners
        = new CopyOnWriteArrayList<ELContextListener>();

    /**
     * 获得实现了ExpressionFactory接口的实例, 该实例是一个全局唯一的单件实例.
     * 利用ExpressionFactory接口可以创建EL表达式或对表达式求值.
     */
    public static ExpressionFactory getExpressionFactory() {
        return factory;
    }

    /**
     * 注册一个ELContextListener, 当新的ELContext创建时将会得到通知.
     *
     * @param ELContextListener ELContext侦听器
     */
    public static void addELContextListener(ELContextListener listener) {
        if (listener == null)
            throw new NullPointerException();
        if (!listeners.contains(listener))
            listeners.add(listener);
    }

    /**
     * 注销先前登记的ELContextListener.
     *
     * @param ELContextListener ELContext侦听器
     */
    public static void removeELContextListener(ELContextListener listener) {
        if (listener == null)
            throw new NullPointerException();
        listeners.remove(listener);
    }

    /**
     * 根据给定的配置创建EL求值上下文.
     *
     * @param resolver 预先配置好的EL对象解析器
     */
    public static ELContext createELContext(ELResolver resolver) {
        ELContext elctx = new ELContextImpl(resolver);
        init(elctx);
        return elctx;
    }

    /**
     * 根据给定的配置创建EL求值上下文.
     *
     * @param resolver 预先配置好的EL对象解析器
     * @param varMapper 变量绑定
     */
    public static ELContext createELContext(ELResolver resolver, VariableMapper varMapper) {
        ELContext elctx = new ELContextImpl(resolver, varMapper);
        init(elctx);
        return elctx;
    }

    /**
     * 创建一个缺省配置的EL求值上下文, 该上下文可以满足大多数情况下的求值要求.
     */
    public static ELContext createELContext() {
        CompositeELResolver composite = new CompositeELResolver();
        addDefaultELResolvers(composite);
        composite.add(new ResourceBundleELResolver());
        composite.add(new BeanPropertyELResolver());
        return createELContext(composite);
    }

    /**
     * 创建一个缺省配置的EL求值上下文, 该上下文可以满足大多数情况下的求值要求.
     *
     * @param varMapper 变量绑定
     */
    public static ELContext createELContext(VariableMapper varMapper) {
        CompositeELResolver composite = new CompositeELResolver();
        addDefaultELResolvers(composite);
        composite.add(new ResourceBundleELResolver());
        composite.add(new BeanPropertyELResolver());
        return createELContext(composite, varMapper);
    }

    private static void init(ELContext elctx) {
        if (!listeners.isEmpty()) {
            ELContextEvent event = new ELContextEvent(elctx);
            for (ELContextListener listener : listeners) {
                listener.contextCreated(event);
            }
        }
    }

    /**
     * 向一个组合EL对象解析器中增加默认的解析器, 这些解析器是为了正确运行ELite程序
     * 所必须的. 当不采用缺省EL求值上下文的配置时, 可以调用此方法使解析器配置和缺省
     * 配置相兼容.
     */
    public static void addDefaultELResolvers(CompositeELResolver composite) {
        composite.add(new SystemClassELResolver());
        composite.add(new MapELResolver());
        composite.add(new SeqELResolver());
        composite.add(new ListELResolver());
        composite.add(new ArrayELResolver());
        composite.add(new StringELResolver());

        if (MEASURES_AVAILABLE) {
            composite.add(new UnitELResolver());
        }
    }

    private static ThreadLocal<ELContext> currentELContext =
        new InheritableThreadLocal<ELContext>() {
            protected ELContext childValue(ELContext parent) {
                while (parent instanceof DelegatingELContext) {
                    parent = ((DelegatingELContext)parent).getDelegate();
                }
                return new DelegatingELContext(parent);
            }
        };

    /**
     * 获得当前的EL求值上下文. 此方法应当慎用, 尽量将求值上下文保存在一个可访问的地方
     * 或在需要求值上下文的方法调用中作为参数传递.
     */
    public static ELContext getCurrentELContext() {
        return currentELContext.get();
    }

    static ELContext setCurrentELContext(ELContext context) {
        ELContext prev = currentELContext.get();
        currentELContext.set(context);
        return prev;
    }

    /**
     * 对一个表达式求值.
     *
     * @param elctx 求值上下文
     * @param expression 字符串表示的EL表达式
     * @param expectedType 表达式要求的结果类型
     */
    public static Object evaluateExpression(ELContext elctx, String expression, Class<?> expectedType) {
        ELNode node = Parser.parse(expression);

        StackTrace.addFrame(elctx, "__eval__", null, 0);
        try {
            EvaluationContext env = new EvaluationContext(elctx);
            Object value = node.getValue(env);
            if (expectedType == null || expectedType == Object.class) {
                return value;
            } else {
                return coerce(elctx, value, expectedType);
            }
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    /**
     * 在一个Java类中根据名称和参数对类方法进行匹配, 并返回最佳匹配的方法.
     *
     * @param elctx 求值上下文
     * @param baseClass 需要对方法匹配的Java类
     * @param name 方法名称
     * @param args 将要传递给方法的参数
     * @return 最佳匹配的方法
     */
    public static Method resolveMethod(ELContext elctx, Class<?> baseClass, String name, Closure[] args) {
        // quick find method if no extra arguments
        if (args.length == 0) {
            try {
                return baseClass.getMethod(name);
            } catch (NoSuchMethodException ex) {
                // fallthrough
            }
        }

        return resolveMethod(elctx, baseClass.getMethods(), name, args);
    }

    /**
     * 在一组方法中根据名称和参数类型查找最佳匹配的方法.
     *
     * @param elctx 求值上下文
     * @param methods 一组需要匹配的方法
     * @param name 方法名称
     * @param args 将要传递给方法的参数
     * @return 最佳匹配的方法
     */
    public static Method resolveMethod(ELContext elctx, Method[] methods, String name, Closure[] args) {
        int argc = args.length;
        Method candidate = null;
        int shortest_distance = 0;

        // find method that have the given name and argument count
        for (Method method : methods) {
            if (name.equals(method.getName()) && !method.isVarArgs()) {
                Class[] types = method.getParameterTypes();
                int nargs = types.length;
                if (nargs > 0 && types[0] == ELContext.class) {
                    nargs--; // chop first argument if it it's an ELContext
                }
                if (argc == nargs) {
                    if (candidate == null) {
                        candidate = method;
                    } else {
                        // multiple occurence found, do deep match
                        candidate = null;
                        break;
                    }
                }
            }
        }

        if (candidate != null) {
            // found the only method that have the given name and argument count
            return candidate;
        }

        // find all candidates that match the argument list
        for (Method method : methods) {
            if (!name.equals(method.getName())) {
                continue;
            }

            Class[] types = method.getParameterTypes();
            int nargs = types.length;
            int dist = -1;

            // chop first argument if it's an ELContext
            if (nargs > 0 && types[0] == ELContext.class) {
                Class[] temp = new Class[nargs-1];
                System.arraycopy(types, 1, temp, 0, nargs-1);
                types = temp;
                nargs--;
            }

            if (nargs == argc) {
                if (!method.isVarArgs()) {
                    // do loose match on extra arguments
                    dist = distanceof(elctx, types, args, nargs);
                } else {
                    // do loose match on extra arguments before var-args
                    dist = distanceof(elctx, types, args, nargs-1);

                    // match for var-args; the var-args may be passed as an array or individual args
                    if (dist != -1) {
                        Class vargtype = types[nargs-1];
                        Closure varg = args[nargs-1];
                        int d = distanceof(elctx, varg, vargtype);
                        if (d == -1)
                            d = distanceof(elctx, varg, vargtype.getComponentType());
                        if (d != -1)
                            dist += d;
                    }
                }
            } else if (nargs >= 1 && nargs <= argc + 1 && method.isVarArgs()) {
                // do loose match on extra arguments before var-args
                int nfixed = nargs - 1;
                dist = distanceof(elctx, types, args, nfixed);

                // match for var-args
                if (dist != -1 && nfixed < argc) {
                    Class vargtype = types[nargs-1].getComponentType();
                    for (int i = nfixed; i < argc; i++) {
                        int d = distanceof(elctx, args[i], vargtype);
                        if (d == -1) {
                            dist = -1;
                            break;
                        } else {
                            dist += d;
                        }
                    }
                }
            }

            if (dist == 0) {
                // found exact candidate
                return method;
            } else if (dist != -1) {
                // compare the type distance of candidates and select
                // the candidate with shortest type distance
                if (candidate == null) {
                    candidate = method;
                    shortest_distance = dist;
                } else if (dist < shortest_distance) {
                    candidate = method;
                    shortest_distance = dist;
                }
            }
        }

        return candidate;
    }

    /**
     * 使用给定的参数调用方法, 参数值将根据方法的参数类型进行适当的转换.
     *
     * @param elctx 求值上下文
     * @param base 方法调用的目标对象, 如果是类静态方法此参数可以为null
     * @param method 被调用的方法
     * @param args 方法调用参数
     * @return 方法调用的返回值
     */
    public static Object invokeMethod(ELContext elctx, Object base, Method method, Closure[] args) {
        Class[]  types  = method.getParameterTypes();
        int      nargs  = types.length;
        Object[] values = new Object[nargs];
        int      iarg   = 0;
        int      ivarg  = 0;
        boolean  is_vargs = method.isVarArgs();

        if (is_vargs) {
            nargs--;
        }

        if (nargs > 0 && types[0] == ELContext.class) {
            values[0] = elctx;
            iarg++;
        }

        // copy fixed arguments in the extra values
        for (; iarg < nargs; iarg++, ivarg++) {
            if (delayed(types[iarg])) {
                values[iarg] = args[ivarg];
            } else {
                values[iarg] = coerce(elctx, args[ivarg].getValue(elctx), types[iarg]);
            }
        }

        // copy variable arguments in the extra values
        if (is_vargs) {
            int vargc = args.length - ivarg;
            if (vargc < 0) vargc = 0;

            assert types[nargs].isArray();
            Class argtype = types[nargs].getComponentType();

            if (delayed(argtype)) {
                if (ivarg == 0) {
                    values[nargs] = args;
                } else {
                    Closure[] vargs = new Closure[vargc];
                    System.arraycopy(args, ivarg, vargs, 0, vargc);
                    values[nargs] = vargs;
                }
            } else if (vargc == 1 && !canCoerceToSeq(argtype)) {
                Object last = args[ivarg].getValue(elctx);
                if (last instanceof List) {
                    // if the last argument is a list then convert it to array
                    List arglist = (List)last;
                    int count = arglist.size();
                    Object vargs = Array.newInstance(argtype, count);
                    for (int i = 0; i < count; i++) {
                        Array.set(vargs, i, coerce(elctx, arglist.get(i), argtype));
                    }
                    values[nargs] = vargs;
                } else if (last != null && last.getClass().isArray()) {
                    // the last argument is an array
                    if (types[nargs].isAssignableFrom(last.getClass())) {
                        values[nargs] = last;
                    } else {
                        int count = Array.getLength(last);
                        Object vargs = Array.newInstance(argtype, count);
                        for (int i = 0; i < count; i++) {
                            Array.set(vargs, i, coerce(elctx, Array.get(last, i), argtype));
                        }
                        values[nargs] = vargs;
                    }
                } else {
                    // the method invoked with solely argument
                    Object vargs = Array.newInstance(argtype, 1);
                    Array.set(vargs, 0, coerce(elctx, last, argtype));
                    values[nargs] = vargs;
                }
            } else {
                // copy argument values from argument list
                Object vargs = Array.newInstance(argtype, vargc);
                for (int i = 0; i < vargc; i++) {
                    Array.set(vargs, i, coerce(elctx, args[ivarg++].getValue(elctx), argtype));
                }
                values[nargs] = vargs;
            }
        }

        try {
            return method.invoke(base, values);
        } catch (InvocationTargetException ex) {
            invokeError(elctx, ex.getTargetException());
        } catch (Exception ex) {
            invokeError(elctx, ex);
        }

        throw new AssertionError();
    }

    /**
     * 获得给定目标方法的信息.
     */
    public static MethodInfo getTargetMethodInfo(ELContext elctx, Object target) {
        if (target == null) {
            return null;
        }

        if (target instanceof Closure) {
            return ((Closure)target).getMethodInfo(elctx);
        } else if (target instanceof ClosureObject) {
            Closure proc = ((ClosureObject)target).get_closure(elctx, "__call__");
            if (proc != null) {
                return proc.getMethodInfo(elctx);
            }
        } else if (target instanceof Class) {
            Class cls = (Class)target;
            return new MethodInfo(cls.getSimpleName(), cls, new Class[] { Object.class });
        } else {
            MethodClosure method = MethodResolver.getInstance(elctx)
                .resolveMethod(target.getClass(), "__call__");
            if (method != null) {
                return method.getMethodInfo(elctx);
            }
        }

        return null;
    }

    /**
     * 调用给定目标过程.
     */
    public static Object invokeTarget(ELContext elctx, Object target, Closure[] args) {
        if (target == null) {
            throw new EvaluationException(elctx, new NullPointerException());
        } else if (target instanceof Closure) {
            return ((Closure)target).invoke(elctx, args);
        } else if (target instanceof Class) {
            return invokeClass(elctx, (Class)target, args);
        } else if (target instanceof ClosureObject) {
            Object result = ((ClosureObject)target).invokeSpecial(elctx, "__call__", args);
            if (result != ELUtils.NO_RESULT) return result;
        } else {
            MethodClosure method = MethodResolver.getInstance(elctx)
                .resolveMethod(target.getClass(), "__call__");
            if (method != null) return method.invoke(elctx, target, args);
        }

        throw new EvaluationException(
            elctx, _T(EL_METHOD_NOT_FOUND, target.getClass().getName(), "__call__"));
    }

    private static Object invokeClass(ELContext elctx, Class target, Closure[] args) {
        if (args.length == 1 && isStandardType(target)) {
            // coerce argument to target class
            Object result = args[0].getValue(elctx);
            return (target == Void.TYPE) ? null : coerce(elctx, result, target);
        }

        // Invoke valueOf method if declared
        MethodClosure valueOf = MethodResolver.getInstance(elctx)
            .resolveStaticMethod(target, "valueOf");
        if (valueOf != null) {
            return valueOf.invoke(elctx, null, args);
        }

        // Invoke constructor of the target class
        return newInstance(elctx, target, args);
    }

    private static Set<Class> STANDARD_TYPES = new HashSet<Class>();
    static {
        STANDARD_TYPES.addAll(Arrays.<Class>asList(
            Boolean.class, Byte.class, Character.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class, BigInteger.class,
            BigDecimal.class, Rational.class, Decimal.class, String.class
        ));
    }

    private static boolean isStandardType(Class type) {
        return type.isPrimitive() || STANDARD_TYPES.contains(type);
    }

    /**
     * 将Java对象封装成闭包对象类型, 进而可以使用转换后的参数调用invokeMethod方法.
     * 使用闭包的好处是可以实现延时求值, 以完成一些在命令式语言中所不具备的特性.
     */
    public static Closure[] getCallArgs(Object[] args) {
        return getCallArgs(args, ELUtils.NO_PARAMS);
    }

    /**
     * 将Java对象封装成闭包对象, 并且和已有的另外一个闭包对象数组拼接.
     */
    public static Closure[] getCallArgs(Object[] args, Closure[] extra) {
        if (extra == null)
            extra = ELUtils.NO_PARAMS;
        if (args.length == 0)
            return extra;

        int argc = args.length + extra.length;
        Closure[] callArgs = new Closure[argc];

        for (int i = 0; i < args.length; i++) {
            callArgs[i] = new LiteralClosure(args[i]);
        }
        if (extra.length != 0) {
            System.arraycopy(extra, 0, callArgs, args.length, extra.length);
        }

        return callArgs;
    }

    /**
     * 当需要调用基本Java方法时可以使用此方法将闭包对象解包成实际的Java对象.
     */
    public static Object[] getArgValues(ELContext elctx, Closure[] args) {
        if (args == null || args.length == 0) {
            return ELUtils.NO_VALUES;
        }

        Object[] values = new Object[args.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = args[i].getValue(elctx);
        }
        return values;
    }

    /**
     * 将类名称解析成类对象, 解析后的类对象可能是一个ELite类定义, 也可能是一个
     * Java类对象, 因此需要检查返回值.
     */
    public static Object resolveClass(EvaluationContext context, String name) {
        if (name == null) {
            return null;
        }

        if (name.indexOf('.') == -1) {
            Object cls = context.resolveClass(name);
            if (cls instanceof ClassDefinition) {
                return cls;
            } else if (cls instanceof DataClass) {
                return ((DataClass)cls).getJavaClass();
            }
        }

        return resolveJavaClass(context.getELContext(), name);
    }

    public static Object resolveDataClass(EvaluationContext context, String name) {
        if (name == null) {
            return null;
        }

        if (name.indexOf('.') == -1) {
            Object cls = context.resolveClass(name);
            if (cls instanceof ClassDefinition || cls instanceof DataClass) {
                return cls;
            }
        }

        Class<?> jcls = resolveJavaClass(context.getELContext(), name);
        Data data = jcls.getAnnotation(Data.class);
        return new DataClass(jcls, data == null ? null : data.value());
    }

    /**
     * 仅将类名称解析成Java类对象.
     */
    public static Class resolveJavaClass(ELContext elctx, String name) {
        try {
            return ClassResolver.getInstance(elctx).resolveClass(name);
        } catch (ClassNotFoundException ex) {
            throw new EvaluationException(elctx, "class not found: " + name);
        }
    }

    /**
     * 查找和指定参数相匹配的构造函数.
     */
    public static Constructor<?> resolveConstructor(ELContext elctx, Class<?> cls, Closure[] args) {
        Constructor<?> candidate = null;
        int shortest_dist = 0;

        for (Constructor<?> cons : cls.getConstructors()) {
            Class[] types = cons.getParameterTypes();
            if (types.length == args.length) {
                int d = distanceof(elctx, types, args, args.length);
                if (d == 0) {
                    return cons;
                } else if (d != -1) {
                    if (candidate == null) {
                        candidate = cons;
                        shortest_dist = d;
                    } else if (d < shortest_dist) {
                        candidate = cons;
                        shortest_dist = d;
                    }
                }
            }
        }

        return candidate;
    }

    /**
     * 创建一个Java对象实例. 该方法将根据参数类型找到最佳匹配的构造方法, 并将参数
     * 值转换成构造方法的实际参数类型.
     */
    public static Object newInstance(ELContext elctx, Class<?> cls, Closure[] args) {
        try {
            if (Modifier.isAbstract(cls.getModifiers())) {
                throw new EvaluationException(elctx, _T(EL_ABSTRACT_CLASS, cls.getName()));
            }

            if (args.length == 0) {
                return cls.newInstance();
            }

            Constructor<?> cons = resolveConstructor(elctx, cls, args);
            if (cons == null) {
                throw new EvaluationException(
                    elctx, _T(EL_METHOD_NOT_FOUND, cls.getName(), cls.getSimpleName()));
            }

            Class[] types = cons.getParameterTypes();
            Object[] values = new Object[args.length];
            for (int i = 0; i < types.length; i++) {
                values[i] = coerce(elctx, args[i].getValue(elctx), types[i]);
            }

            return cons.newInstance(values);
        } catch (InvocationTargetException ex) {
            throw new EvaluationException(elctx, ex.getTargetException());
        } catch (EvaluationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EvaluationException(elctx, ex);
        }
    }

    // Implementation -----------------------------------

    private static int distanceof(ELContext elctx, Closure arg, Class type) {
        if (delayed(type)) {
            return 0;
        }

        Object value = arg.getValue(elctx);
        if (value instanceof ClosureObject)
            value = ((ClosureObject)value).get_proxy();
        if (value == null)
            return GUESSED_DISTANCE;
        return TypeCoercion.distanceof(value.getClass(), type);
    }

    private static int distanceof(ELContext elctx, Class[] types, Closure[] args, int nargs) {
        int dist = 0;
        for (int i = 0; i < nargs; i++) {
            int d = distanceof(elctx, args[i], types[i]);
            if (d == -1) return -1;
            dist += d;
        }
        return dist;
    }

    private static boolean delayed(Class<?> type) {
        return type == ValueExpression.class || type == Closure.class;
    }

    private static void invokeError(ELContext elctx, Throwable cause) {
        if (cause instanceof EvaluationException) {
            throw (EvaluationException)cause;
        } else if (cause instanceof Error) {
            throw (Error)cause;
        } else {
            throw new EvaluationException(elctx, cause);
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    public static final boolean MEASURES_AVAILABLE = classPresent("javax.measure.Measure");
}
