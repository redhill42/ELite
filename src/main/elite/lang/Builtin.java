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

package elite.lang;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.lang.reflect.Array;
import java.io.*;
import java.text.DecimalFormatSymbols;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.text.NumberFormat;
import javax.el.ELContext;
import javax.el.ValueExpression;
import javax.el.ELException;
import javax.el.ELResolver;
import javax.script.ScriptContext;

import elite.lang.annotation.Expando;
import static elite.lang.annotation.ExpandoScope.*;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.el.eval.*;
import org.operamasks.el.eval.closure.*;
import org.operamasks.el.eval.seq.*;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;
import static elite.lang.MathLib.*;
import static org.operamasks.el.eval.TypeCoercion.*;
import static org.operamasks.el.eval.ELUtils.*;

/**
 * ELite内建函数库.
 */
@SuppressWarnings("unchecked")
public final class Builtin
{
    private Builtin() {}

    // System functions

    /**
     * 判断一个变量是否已定义.
     *
     * 用法: defined(var)
     */
    public static boolean defined(ELContext elctx, Closure var) {
        if (var instanceof VarClosure) {
            EvaluationContext ctx = var.getContext(elctx);
            String id = ((VarClosure)var).id();
            return ctx.resolveVariable(id) != null;
        } else {
            return false;
        }
    }

    /**
     * 创建一个Java对象实例.
     *
     * 用法: myclass.new(args...)
     */
    @Expando(name="new")
    public static Object __new__(ELContext elctx, Class cls, Closure... args) {
        return ELEngine.newInstance(elctx, cls, args);
    }

    /**
     * 创建多维数组.
     *
     * 用法: myclass.newArray(dimensions...)
     */
    @Expando
    public static Object newArray(Class cls, int... dimensions) {
        return Array.newInstance(cls, dimensions);
    }

    /**
     * 顺序执行一系列表达式.
     *
     * 用法1: begin(exp1, exp2, ...)
     * 用法2: begin { block }
     */
    public static Object begin(ELContext elctx, Closure... exps) {
        if (exps.length == 0) {
            return null;
        }

        int n = exps.length - 1;
        for (int i = 0; i < n; i++) {
            exps[i].getValue(elctx);
        }
        return yield(elctx, exps[n]);
    }

    /**
     * 创建延时求值表达式.
     *
     * 用法: delay(exp)
     */
    public static Closure delay(Closure exp) {
        return exp;
    }

    /**
     * 强制对延时求值表达式求值.
     *
     * 用法: force(promise)
     */
    public static Object force(ELContext elctx, Object promise) {
        if (promise instanceof ValueExpression) {
            return ((ValueExpression)promise).getValue(elctx);
        } else if (promise instanceof Seq) {
            Seq s = (Seq)promise;
            for (Seq t = s; !t.isEmpty(); t = t.tail());
            return s;
        } else {
            return promise;
        }
    }

    /**
     * 总是返回参数值的函数, 其定义定价于:
     *      define identity = { x => x }
     *
     * 用法: 将identity作为参数传递给高阶函数
     */
    public static Object identity(Object x) {
        return x;
    }

    /**
     * 总是返回常量值的函数, 其定义定价于:
     *      define const(c) { x => c }
     *
     * 用法: 将const(c)作为参数传递给高阶函数
     */
    public static Closure _const(final Object c) {
        return new AbstractClosure() {
            public Object invoke(ELContext elctx, Closure[] args) { return c; }
            public int arity(ELContext elctx) { return 0; }
            public boolean isProcedure() { return true; }
            public String toString() { return "const(" + c + ")"; }
        };
    }

    /**
     * 返回参数表中的第一个非空值, 如果所有元素皆为空则最终返回空值.
     *
     * 用法: coalesce(exp1, exp2, ...)
     */
    public static Object coalesce(ELContext elctx, Closure... exps) {
        for (Closure exp : exps) {
            Object value = exp.getValue(elctx);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 从第一个值开始, 依次递增其数值, 直到达到第二个值, 在迭代过程中调用代码块.
     *
     * 用法: a.upto(b) { block ... }
     */
    public static void upto(ELContext elctx, long a, long b, Closure p) {
        for (; a <= b; ++a) {
            try {
                p.call(elctx, a);
            } catch (Control.Break br) {
                break;
            } catch (Control.Continue co) {
                continue;
            }
        }
    }

    /**
     * 从第一个值开始, 依次递减其数值, 直到达到第二个值, 在迭代过程中调用代码块.
     *
     * 用法: a.downto(b) { block ... }
     */
    public static void downto(ELContext elctx, long a, long b, Closure p) {
        for (; a >= b; --a) {
            try {
                p.call(elctx, a);
            } catch (Control.Break br) {
                break;
            } catch (Control.Continue co) {
                continue;
            }
        }
    }

    /**
     * 从第一个值开始, 按指定步长依次递增(或递减)其数值, 直到达到第二个值, 在迭代过程中调用代码块.
     *
     * 用法: a.step(b,i) { block ... }
     */
    public static void step(ELContext elctx, Object a, Object b, Object i, Closure p) {
        switch (signum(elctx, i)) {
            case 1: // step up
                for (; __le__(elctx, a, b); a = __add__(elctx, a, i)) {
                    try {
                        p.call(elctx, a);
                    } catch (Control.Break br) {
                        break;
                    } catch (Control.Continue co) {
                        continue;
                    }
                }
                break;

            case -1: // step down
                for (; __ge__(elctx, a, b); a = __add__(elctx, a, i)) {
                    try {
                        p.call(elctx, a);
                    } catch (Control.Break br) {
                        break;
                    } catch (Control.Continue co) {
                        continue;
                    }
                }
                break;

            default:
                throw new ELException("step: the step cannot be zero.");
        }
    }

    /**
     * 重复调用指定次数.
     *
     * 用法: n.times { block ... }
     */
    public static void times(ELContext elctx, int n, Closure p) {
        for (int i = 0; i < n; i++) {
            try {
                p.call(elctx, i);
            } catch (Control.Break b) {
                break;
            } catch (Control.Continue c) {
                continue;
            }
        }
    }
    
    /**
     * 返回指定变量的所有元数据.
     *
     * 用法: annotations(var)
     */
    public static Annotation[] annotations(ELContext elctx, Closure var) {
        if (var instanceof VarClosure) {
            EvaluationContext ctx = var.getContext(elctx);
            String id = ((VarClosure)var).id();
            ValueExpression ve = ctx.resolveVariable(id);
            if (ve instanceof Closure) {
                return ((Closure)ve).getAnnotations();
            }
        }
        return new Annotation[0];
    }

    /**
     * 返回指定变量的元数据.
     *
     * 用法: annotation(var, type)
     */
    public static Annotation annotation(ELContext elctx, Closure var, String type) {
        for (Annotation a : annotations(elctx, var)) {
            if (a.getAnnotationType().equals(type)) {
                return a;
            }
        }
        return null;
    }

    /**
     * 向一个Java类附加自定义方法, Java类的所有实例(无论之前或之后创建)都
     * 将可以调用此方法. 自定义方法可以用闭包实现, 闭包的第一个参数是类实例.
     *
     * 注意: 附加方法将覆盖类本身的内建方法. 当多次附加同名方式时, 以前附加
     * 的方法将被覆盖.
     *
     * 用法: javaclass.attach(name, closure)
     *
     * 示例:
     *   String.attach('capitalize') { s =>
     *       if (s.length > 0) {
     *          Character.toUpperCase(s[0]) + s[1..*];
     *       } else {
     *          s;
     *       }
     *   }
     *
     *   "hello".capitalize()  ===>  "Hello"
     */
    @Expando
    public static void attach(ELContext elctx, Class cls, String name, Closure closure) {
        MethodResolver resolver = MethodResolver.getInstance(elctx);
        resolver.attachMethod(cls, name, closure);
    }

    /**
     * 使用给定的Map对JavaBean的属性进行赋值
     *
     * 示例:
     *  假定Person对象具有firstname, lastname, age, gender等属性, 则
     *  可以使用以下方式对这些属性赋值:
     *
     *     person.populate({
     *         firstname : 'John',
     *         lastname : 'Doe',
     *         age : 31,
     *         gender : 'male'
     *     });
     *
     *  此函数可以代替以下的写法:
     *
     *     person.firstname = 'John';
     *     person.lastname = 'Doe';
     *     person.age = 31;
     *     person.gender = 'male';
     */
    @Expando
    public static Object populate(ELContext elctx, Object base, Map<?,?> properties) {
        if (base instanceof Map) {
            ((Map)base).putAll(properties);
        } else if (base instanceof ClosureObject) {
            ClosureObject clo = (ClosureObject)base;
            for (Map.Entry<?,?> e : properties.entrySet()) {
                clo.setValue(elctx, e.getKey(), e.getValue());
            }
        } else {
            ELResolver resolver = elctx.getELResolver();
            for (Map.Entry<?,?> e : properties.entrySet()) {
                resolver.setValue(elctx, base, e.getKey(), e.getValue());
            }
        }
        return base;
    }

    @Expando
    public static void topDown(ELContext elctx, ClosureObject obj, Closure proc) {
        ClosureObject owner = obj.get_owner();
        String[] vars = owner.get_class().getDataSlots();

        if (vars != null) {
            proc.call(elctx, owner);
            for (String id : vars) {
                Object elem = owner.getValue(elctx, id);
                if (elem instanceof ClosureObject) {
                    topDown(elctx, (ClosureObject)elem, proc);
                }
            }
        }
    }

    @Expando
    public static void bottomUp(ELContext elctx, ClosureObject obj, Closure proc) {
        ClosureObject owner = obj.get_owner();
        String[] vars = owner.get_class().getDataSlots();

        if (vars != null) {
            for (String id : vars) {
                Object elem = owner.getValue(elctx, id);
                if (elem instanceof ClosureObject) {
                    bottomUp(elctx, (ClosureObject)elem, proc);
                }
            }
            proc.call(elctx, owner);
        }
    }

    public static void showTree(ELContext elctx, ClosureObject obj) {
        showTree_rec(elctx, obj, "", null, true);
    }

    private static void showTree_rec(ELContext elctx, ClosureObject obj, String tab, String prefix, boolean last) {
        ClassDefinition cls = obj.get_class();
        String[] slots = cls.getDataSlots();

        if (slots == null) {
            print(elctx, obj);
            return;
        }

        ClosureObject[] kids = new ClosureObject[slots.length];
        StringBuilder buf = new StringBuilder();
        boolean sep = false;

        buf.append(tab);
        if (prefix != null)
            buf.append(prefix).append('=');
        buf.append(cls.getName()).append('(');
        for (int i = 0; i < slots.length; i++) {
            Object kid = obj.getValue(elctx, slots[i]);
            if ((kid instanceof ClosureObject) && ((ClosureObject)kid).get_class().getDataSlots() != null) {
                kids[i] = (ClosureObject)kid;
            } else {
                if (sep) buf.append(','); else sep = true;
                buf.append(slots[i]).append('=').append(kid);
            }
        }
        buf.append(')');
        print(elctx, buf);

        if (tab.length() != 0)
            tab = tab.substring(0, tab.length()-3) + (last ? "   " : "|  ");
        tab += "+--";
        for (int i = 0; i < slots.length; i++) {
            if (kids[i] != null) {
                showTree_rec(elctx, kids[i], tab, slots[i], is_last(kids, i));
            }
        }
    }

    private static boolean is_last(ClosureObject[] kids, int i) {
        while (++i < kids.length) {
            if (kids[i] != null)
                return false;
        }
        return true;
    }

    // List functions

    /**
     * 以给定参数创建一个序列.
     *
     * 用法: list(exp1, exp2, ...)
     */
    public static Seq list(Object... args) {
        Seq xs = new Cons();
        for (int i = args.length; --i >= 0; ) {
            xs = new Cons(args[i], xs);
        }
        return xs;
    }

    /**
     * 以给定的头部和尾部创建一个新序列.
     */
    public static Seq cons(Closure head, Closure tail) {
        return new DelayCons(head, tail);
    }

    /**
     * Returns an infinite list of repeated applications of the specified
     * function to the specified argument.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq iterate(Object x, Closure f) {
        return new IterateSeq(x, f);
    }

    private static class IterateSeq extends DelaySeq {
        private Closure proc;

        IterateSeq(Object x, Closure f) {
            this.head = x;
            this.proc = f;
        }

        public boolean isEmpty() {
            return false;
        }

        protected void force(ELContext elctx) {
            if (proc != null) {
                Closure f = proc; proc = null;
                tail = new IterateSeq(f.call(elctx, head), f);
            }
        }
    }
    
    /**
     * Creates a list of length given by the second argument and the items
     * having value of the first argument.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object replicate(Object x, int n) {
        if (x instanceof Character) {
            StringBuilder buf = new StringBuilder();
            char c = ((Character)x).charValue();
            for (int i = 0; i < n; i++)
                buf.append(c);
            return buf.toString();
        } else if (x instanceof String) {
            StringBuilder buf = new StringBuilder();
            String s = (String)x;
            for (int i = 0; i < n; i++)
                buf.append(s);
            return buf.toString();
        } else {
            return ReplicateSeq.make(n, x);
        }
    }
    
    private static class ReplicateSeq extends AbstractSeq
        implements RandomAccess
    {
        private final int length;
        private final Object head;
        private Seq tail;

        private ReplicateSeq(int n, Object x) {
            this.length = n;
            this.head = x;
        }

        static Seq make(int n, Object x) {
            if (n <= 0) {
                return Cons.nil();
            } else {
                return new ReplicateSeq(n, x);
            }
        }

        public int size() {
            return length;
        }

        public boolean isEmpty() {
            return false;
        }

        public Object get() {
            return head;
        }

        public Object get(int index) {
            if (index < 0 || index >= length)
                throw new IndexOutOfBoundsException("Index:"+index);
            return head;
        }

        public Seq tail() {
            if (tail == null)
                tail = make(length-1, head);
            return tail;
        }
    }

    /**
     * Creates an infinite list where all items are the specified object.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq repeat(Object x) {
        return new RepeatSeq(x);
    }

    private static class RepeatSeq extends AbstractSeq {
        private final Object value;

        RepeatSeq(Object value) {
            this.value = value;
        }

        public int size() {
            return Integer.MAX_VALUE;
        }

        public boolean isEmpty() {
            return false;
        }

        public Object get() {
            return value;
        }

        public Object get(int index) {
            return value;
        }

        public Seq tail() {
            return this;
        }
    }

    /**
     * Ties a finite list into a circular one, or equivalently, the infinite
     * repetition of the original list. It is the identity on infinite lists.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq cycle(Seq seq) {
        return seq.isEmpty() ? Cons.nil() : new CycleSeq(seq);
    }

    private static class CycleSeq extends DelaySeq {
        private Seq begin, current;

        CycleSeq(Seq seq) {
            begin = this;
            current = seq;
        }

        private CycleSeq(Seq begin, Seq current) {
            this.begin = begin;
            this.current = current;
        }

        protected void force(ELContext elctx) {
            if (current != null) {
                Seq beg = begin, cur = current;
                begin = current = null;
                head = cur.get(); cur = cur.tail();
                tail = cur.isEmpty() ? beg : new CycleSeq(beg, cur);
            }
        }
    }

    /**
     * 创建区间对象.
     *
     * 用法: range(low, high, increment)
     */
    public static Seq range(ELContext elctx, Object a, Object b, Object i) {
        if ((a instanceof Long || a instanceof Integer) &&
            (b instanceof Long || b instanceof Integer) &&
            (i instanceof Long || i instanceof Integer)) {
            return Ranges.createRange(((Number)a).longValue(),
                                      ((Number)b).longValue(),
                                      ((Number)i).longValue());
        }

        if ((a instanceof Character) && (b instanceof Character) && (i instanceof Integer)) {
            return CharRanges.createCharRange((Character)a, (Character)b, (Integer)i);
        }

        switch (signum(elctx, i)) {
            case 1:  return StepUp.make(elctx, a, b, i);
            case -1: return StepDown.make(elctx, a, b, i);
            default: throw new ELException("range: the step cannot be zero.");
        }
    }

    /**
     * 递增区间对象.
     */
    private static class StepUp extends DelaySeq {
        private Object a, b, i;

        StepUp(Object a, Object b, Object i) {
            this.a = a; this.b = b; this.i = i;
        }

        static Seq make(ELContext elctx, Object a, Object b, Object i) {
            if (__gt__(elctx, a, b)) {
                return Cons.nil();
            } else {
                return new StepUp(a, b, i);
            }
        }

        public int size() {
            if (a instanceof Number && b instanceof Number && i instanceof Number) {
                ELContext elctx = ELEngine.getCurrentELContext();
                return coerceToInt(__div__(elctx,
                                     __add__(elctx,
                                       __sub__(elctx, b, a), i), i));
            } else {
                return super.size();
            }
        }

        protected void force(ELContext elctx) {
            if (a != null) {
                head = a;
                tail = make(elctx, __add__(elctx, a, i), b, i);
                a = b = i = null;
            }
        }
    }

    /**
     * 递减区间对象.
     */
    private static class StepDown extends DelaySeq {
        private Object a, b, i;

        StepDown(Object a, Object b, Object i) {
            this.a = a; this.b = b; this.i = i;
        }

        static Seq make(ELContext elctx, Object a, Object b, Object i) {
            if (__lt__(elctx, a, b)) {
                return Cons.nil();
            } else {
                return new StepDown(a, b, i);
            }
        }

        public int size() {
            if (a instanceof Number && b instanceof Number && i instanceof Number) {
                ELContext elctx = ELEngine.getCurrentELContext();
                return coerceToInt(__div__(elctx,
                                     __add__(elctx,
                                       __sub__(elctx, b, a), i), i));
            } else {
                return super.size();
            }
        }

        protected void force(ELContext elctx) {
            if (a != null) {
                head = a;
                tail = make(elctx, __add__(elctx, a, i), b, i);
                a = b = i = null;
            }
        }
    }

    /**
     * take n, applied to a list xs, returns the prefix of xs of length n,
     * or xs itself if n > xs.length()
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq take(Seq xs, int n) {
        return TakeSeq.make(xs, n);
    }

    private static class TakeSeq extends DelaySeq {
        private Seq xs;
        private int n;

        private TakeSeq(Seq xs, int n) {
            this.xs = xs;
            this.n = n;
        }

        static Seq make(Seq xs, int n) {
            if (n <= 0 || xs.isEmpty()) {
                return Cons.nil();
            } else {
                return new TakeSeq(xs, n);
            }
        }

        protected void force(ELContext elctx) {
            if (xs != null) {
                Seq s = xs; xs = null;
                head = s.get();
                tail = make(s.tail(), n-1);
            }
        }
    }

    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq takeWhile(Seq seq, Closure pred) {
        return new TakeWhileSeq(seq, pred);
    }

    private static class TakeWhileSeq extends DelaySeq {
        private Seq seq;
        private Closure pred;

        TakeWhileSeq(Seq seq, Closure pred) {
            this.seq = seq;
            this.pred = pred;
        }

        protected void force(ELContext elctx) {
            if (seq != null) {
                Seq s = seq;
                Closure p = pred;
                seq = null;
                pred = null;
                if (!s.isEmpty()) {
                    Object x = s.get(); s = s.tail();
                    if (p.test(elctx, x)) {
                        head = x;
                        tail = new TakeWhileSeq(s, p);
                    }
                }
            }
        }
    }

    /**
     * 给定一组列表, 顺序从每个列表中取出一个元素组成一个元组, 将这些元组构造成一个新的列表.
     *
     * 用法: zip([a,b,c], [x,y,z])  ===> [(a,x), (b,y), (c,z)]
     */
    public static Seq zip(Seq... ls) {
        if (ls.length == 0) {
            return Cons.nil();
        } else {
            return ZipSeq.make(ls);
        }
    }

    /**
     * 给定一组列表, 顺序从每个列表中取出一个元素组成一个元组, 使用这个元组调用
     * 指定的过程, 过程的返回值构造成一个新的列表.
     *
     * 用法: zipWith(f, [a,b,c], [x,y,z]) ===> [f(a,x), f(b,y), f(c,z)]
     */
    public static Seq zipwith(Closure proc, Seq... ls) {
        if (ls.length == 0) {
            return Cons.nil();
        } else {
            return ZipWithSeq.make(ls, proc);
        }
    }

    private static class ZipSeq extends DelaySeq {
        private Seq[] ls;

        ZipSeq(Seq[] ls) {
            this.ls = ls;
        }

        static Seq make(Seq[] ls) {
            return new ZipSeq(ls);
        }

        protected void force(ELContext elctx) {
            if (ls != null) {
                Object[] z = new Object[ls.length];
                for (int i = 0; i < z.length; i++) {
                    if (ls[i].isEmpty()) {
                        ls = null;
                        return;
                    } else {
                        z[i] = ls[i].get();
                        ls[i] = ls[i].tail();
                    }
                }

                head = z;
                tail = make(ls);
                ls = null;
            }
        }
    }

    private static class ZipWithSeq extends DelaySeq {
        private Seq[] ls;
        private Closure proc;

        ZipWithSeq(Seq[] ls, Closure proc) {
            this.ls = ls;
            this.proc = proc;
        }

        static Seq make(Seq[] ls, Closure proc) {
            return new ZipWithSeq(ls, proc);
        }

        protected void force(ELContext elctx) {
            if (ls != null) {
                Object[] args = new Object[ls.length];
                for (int i = 0; i < args.length; i++) {
                    if (ls[i].isEmpty()) {
                        ls = null;
                        proc = null;
                        return;
                    } else {
                        args[i] = ls[i].get();
                        ls[i] = ls[i].tail();
                    }
                }

                head = proc.call(elctx, args);
                tail = make(ls, proc);
                ls = null;
                proc = null;
            }
        }
    }

    /**
     * 将数组, 集合等对象转换成列表.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq asList(Object obj) {
        return coerceToSeq(obj);
    }

    /**
     * 将Map转换成以二元组(key,value)为元素的列表.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq asList(Map<?,?> map) {
        Seq head = new Cons(), tail = head;
        for (Map.Entry<?,?> e : map.entrySet()) {
            Object[] x = { e.getKey(), e.getValue() };
            Seq t = new Cons();
            tail.set(x);
            tail.set_tail(t);
            tail = t;
        }
        return head;
    }

    /**
     * 将集合对象转换成数组.
     */
    public static Object[] toArray(Collection c) {
        return c.toArray();
    }

    /**
     * 将集合对象转换成数组.
     */
    public static Object toArray(ELContext elctx, Collection c, Class type) {
        int size = c.size();
        Object array = Array.newInstance(type, size);
        Iterator it = c.iterator();
        for (int i = 0; i < size; i++) {
            Array.set(array, i, coerce(elctx, it.next(), type));
        }
        return array;
    }

    /**
     * 以指定数据填充列表内容.
     *
     * 用法: fill(list, item) 或 list.fill(item)
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object fill(List<Object> lst, Object item) {
        Collections.fill(lst, item);
        return lst;
    }

    /**
     * 以指定数据填充数组.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object[] fill(ELContext elctx, Object[] a, Object x) {
        Arrays.fill(a, coerce(elctx, x, a.getClass().getComponentType()));
        return a;
    }

    /**
     * 以指定数据填充数组.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static byte[] fill(byte[] a, byte x) {
        Arrays.fill(a, x);
        return a;
    }

    /**
     * 以指定数据填充数组.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static char[] fill(char[] a, char x) {
        Arrays.fill(a, x);
        return a;
    }

    /**
     * 以指定数据填充数组.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static short[] fill(short[] a, short x) {
        Arrays.fill(a, x);
        return a;
    }

    /**
     * 以指定数据填充数组.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static int[] fill(int[] a, int x) {
        Arrays.fill(a, x);
        return a;
    }

    /**
     * 以指定数据填充数组.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static long[] fill(long[] a, long x) {
        Arrays.fill(a, x);
        return a;
    }

    /**
     * 以指定数据填充数组.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static float[] fill(float[] a, float x) {
        Arrays.fill(a, x);
        return a;
    }

    /**
     * 以指定数据填充数组.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static double[] fill(double[] a, double x) {
        Arrays.fill(a, x);
        return a;
    }

    /**
     * 将数据项添加到列表的末尾.
     *
     * 用法: list.push(item)
     */
    @Expando
    public static List push(List lst, Object item) {
        if (item instanceof Collection) {
            lst.addAll((Collection)item);
        } else if (item != null && item.getClass().isArray()) {
            lst.addAll(new ArrayAsList(item));
        } else {
            lst.add(item);
        }
        return lst;
    }

    /**
     * 从列表的末尾删除元素, 并返回被删除的元素. 当列表为空时返回null.
     *
     * 用法: list.pop()
     */
    @Expando
    public static Object pop(List lst) {
        if (!lst.isEmpty()) {
            return lst.remove(lst.size()-1);
        } else {
            return null;
        }
    }

    /**
     * 将数据项添加到列表的开始.
     *
     * 用法: list.unshift(item)
     */
    @Expando
    public static List unshift(List lst, Object item) {
        if (item instanceof Collection) {
            lst.addAll(0, (Collection)item);
        } else if (item != null && item.getClass().isArray()) {
            lst.addAll(0, new ArrayAsList(item));
        } else {
            lst.add(0, item);
        }
        return lst;
    }

    /**
     * 从列表的开始删除元素并返回被删除的元素. 当列表为空时返回null.
     *
     * 用法: list.shift()
     */
    @Expando
    public static Object shift(List lst) {
        if (!lst.isEmpty()) {
            return lst.remove(0);
        } else {
            return null;
        }
    }

    /**
     * 遍历集合, 对集合的每个元素调用给定的过程, 过程的返回值组成一个新的集合.
     *
     * 用法: collection.map(proc)
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object map(ELContext elctx, Object base, Closure proc) {
        if (base instanceof CharSequence) {
            return map_string(elctx, (CharSequence)base, proc);
        } else if (base instanceof Object[]) {
            return map_array(elctx, (Object[])base, proc);
        } else if (base.getClass().isArray()) {
            return map_array(elctx, base, proc);
        } else if (base instanceof Set) {
            return map_set(elctx, (Set)base, proc);
        } else {
            return MappedSeq.make(coerceToSeq(base), proc);
        }
    }

    private static String map_string(ELContext elctx, CharSequence str, Closure proc) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0, len = str.length(); i < len; i++) {
            char c = coerceToCharacter(proc.call(elctx, str.charAt(i)));
            buf.append(c);
        }
        return buf.toString();
    }

    private static Object[] map_array(ELContext elctx, Object[] a, Closure proc) {
        int size = a.length;
        Object[] b = new Object[size];
        for (int i = 0; i < size; i++) {
            b[i] = proc.call(elctx, a[i]);
        }
        return b;
    }
    
    private static Object map_array(ELContext elctx, Object a, Closure proc) {
        int size = Array.getLength(a);
        Object[] b = new Object[size];
        for (int i = 0; i < size; i++) {
            b[i] = proc.call(elctx, Array.get(a, i));
        }
        return b;
    }

    private static Object map_set(ELContext elctx, Set set, Closure proc) {
        Set r = new LinkedHashSet();
        for (Object o : set) {
            r.add(proc.call(elctx, o));
        }
        return r;
    }

    /**
     * 遍历两个集合, 对集合的每个元素调用给定的过程, 过程的返回值组成一个新的集合.
     * 返回集合的元素数是两个输入集合中元素数较小者.
     *
     * 用法: map2(collection1, collection2, proc)
     */
    public static Object map2(ELContext elctx, Object a, Object b, Closure proc) {
        if (a instanceof CharSequence && b instanceof CharSequence) {
            return map2_string(elctx, (CharSequence)a, (CharSequence)b, proc);
        } else if (a instanceof Object[] && b instanceof Object[]) {
            return map2_array(elctx, (Object[])a, (Object[])b, proc);
        } else if (a.getClass().isArray() && b.getClass().isArray()) {
            return map2_array(elctx, a, b, proc);
        } else {
            return Map2Seq.make(coerceToSeq(a), coerceToSeq(b), proc);
        }
    }

    private static String map2_string(ELContext elctx, CharSequence a, CharSequence b, Closure proc) {
        int len = Math.min(a.length(), b.length());
        StringBuilder buf = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = coerceToCharacter(proc.call(elctx, a.charAt(i), b.charAt(i)));
            buf.append(c);
        }
        return buf.toString();
    }
    
    private static Object map2_array(ELContext elctx, Object[] a, Object[] b, Closure proc) {
        int size = Math.min(a.length, b.length);
        Object r[] = new Object[size];
        for (int i = 0; i < size; i++) {
            r[i] = proc.call(elctx, a[i], b[i]);
        }
        return r;
    }

    private static Object map2_array(ELContext elctx, Object a, Object b, Closure proc) {
        int size = Math.min(Array.getLength(a), Array.getLength(b));
        Object[] r = new Object[size];
        for (int i = 0; i < size; i++) {
            r[i] = proc.call(elctx, Array.get(a,i), Array.get(b,i));
        }
        return r;
    }

    /**
     * 遍历集合的每个元素, 调用给定的谓词, 当谓词返回值为true时将元素加入结果集合.
     *
     * 用法: collection.filter(proc)
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object filter(ELContext elctx, Object base, Closure pred) {
        if (base instanceof CharSequence) {
            return filter_string(elctx, (CharSequence)base, pred);
        } else if (base instanceof Set) {
            return filter_set(elctx, (Set)base, pred);
        } else {
            return FilteredSeq.make(coerceToSeq(base), pred);
        }
    }

    private static String filter_string(ELContext elctx, CharSequence str, Closure pred) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0, len = str.length(); i < len; i++) {
            char c = str.charAt(i);
            if (pred.test(elctx, c)) {
                buf.append(c);
            }
        }
        return buf.toString();
    }

    private static Set filter_set(ELContext elctx, Set set, Closure pred) {
        Set r = new LinkedHashSet();
        for (Object o : set) {
            if (pred.test(elctx, o)) {
                r.add(o);
            }
        }
        return r;
    }

    /**
     * 列表聚合支持函数, 将子表展开后拼接成新的表.
     */
    @Expando(name={"mappend", "flatmap"}, scope={EXPANDO,GLOBAL})
    public static Seq mappend(Seq seq, Closure proc) {
        return MappendSeq.make(seq, proc);
    }

    /**
     * List Monad支持函数，与mappend定义相同。
     */
    @Expando
    public static Seq bind(Seq seq, Closure proc) {
        return MappendSeq.make(seq, proc);
    }

    /**
     * 使用指定的谓词将一个列表分成两部分, 第一部分包含所有满足谓词
     * 的元素, 第二部分包含所有不满足谓词的元素.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq[] splitWith(Seq seq, Closure pred) {
        return SplitSeq.make(seq, pred);
    }

    static class SplitSeq {
        protected Seq xs;
        protected Closure pred;
        protected SubSeq left, right;

        private SplitSeq(Seq xs, Closure pred) {
            this.xs = xs;
            this.pred = pred;
            this.left = new SubSeq();
            this.right = new SubSeq();
        }

        public static Seq[] make(Seq xs, Closure pred) {
            if (xs.isEmpty()) {
                return new Seq[] {xs, xs};
            } else {
                SplitSeq seq = new SplitSeq(xs, pred);
                return new Seq[] {seq.left, seq.right};
            }
        }

        protected class SubSeq extends DelaySeq {
            protected void force(ELContext elctx) {
                while (xs != null && tail == null) {
                    SplitSeq.this.force(elctx);
                }
            }

            void force(Object head, SubSeq tail) {
                this.head = head;
                this.tail = tail;
            }
        }

        protected void force(ELContext elctx) {
            if (xs != null) {
                Object x = xs.get();
                xs = xs.tail();

                SubSeq t = new SubSeq();
                if (pred.test(elctx, x)) {
                    left.force(x, t);
                    left = t;
                } else {
                    right.force(x, t);
                    right = t;
                }

                if (xs.isEmpty()) {
                    xs = null;
                    pred = null;
                    left = right = null;
                }
            }
        }
    }

    /**
     * 在指定的位置将列表分成两部分.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq[] splitAt(Seq seq, int index) {
        Seq head = new Cons();
        for (int i = 0; i < index && !seq.isEmpty(); i++) {
            head = new Cons(seq.get(), head);
            seq = seq.tail();
        }
        return new Seq[] { head.reverse(), seq };
    }

    /**
     * 查找集合中符合谓词的第一个元素.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object find(ELContext elctx, Iterable c, Closure pred) {
        for (Object e : c) {
            if (pred.test(elctx, e)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 给定一个谓词, 当集合中的所有元素都满足谓词时该函数返回true, 否则返回false.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static boolean forall(ELContext elctx, Object xs, Closure pred) {
        if (xs == null) {
            return true;
        }

        if (xs instanceof Iterable) {
            for (Object e : (Iterable)xs) {
                if (!pred.test(elctx, e)) {
                    return false;
                }
            }
        } else if (xs instanceof Map) {
            for (Object e : ((Map)xs).entrySet()) {
                if (!pred.test(elctx, e)) {
                    return false;
                }
            }
        } else if (xs instanceof CharSequence) {
            CharSequence cs = (CharSequence)xs;
            for (int i = 0, len = cs.length(); i < len; i++) {
                if (!pred.test(elctx, cs.charAt(i))) {
                    return false;
                }
            }
        } else if (xs instanceof Object[]) {
            for (Object e : (Object[])xs) {
                if (!pred.test(elctx, e)) {
                    return false;
                }
            }
        } else if (xs.getClass().isArray()) {
            for (int i = 0, len = Array.getLength(xs); i < len; i++) {
                if (!pred.test(elctx, Array.get(xs, i))) {
                    return false;
                }
            }
        } else {
            throw new IllegalArgumentException("not a collection type");
        }

        return true;
    }

    /**
     * 给定一个谓词, 如果集合中的任意一个元素满足谓词则该函数返回true, 否则返回false.
     */
    @Expando(name={"exists", "forany"}, scope={EXPANDO,GLOBAL})
    public static boolean forany(ELContext elctx, Object xs, Closure pred) {
        if (xs instanceof Iterable) {
            for (Object e : (Iterable)xs) {
                if (pred.test(elctx, e)) {
                    return true;
                }
            }
        } else if (xs instanceof Map) {
            for (Object e : ((Map)xs).entrySet()) {
                if (pred.test(elctx, e)) {
                    return true;
                }
            }
        } else if (xs instanceof CharSequence) {
            CharSequence cs = (CharSequence)xs;
            for (int i = 0, len = cs.length(); i < len; i++) {
                if (pred.test(elctx, cs.charAt(i))) {
                    return true;
                }
            }
        } else if (xs instanceof Object[]) {
            for (Object e : (Object[])xs) {
                if (pred.test(elctx, e)) {
                    return true;
                }
            }
        } else if (xs != null && xs.getClass().isArray()) {
            for (int i = 0, len = Array.getLength(xs); i < len; i++) {
                if (pred.test(elctx, Array.get(xs, i))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 使用指定过程遍历调用集合的每一个元素.
     *
     * 用法: collection.each { x => block... }
     */
    @Expando(name={"each", "foreach", "iterate"}, scope={EXPANDO,GLOBAL})
    public static void each(ELContext elctx, Object xs, Closure proc) {
        if (xs instanceof Iterable) {
            for (Object e : (Iterable)xs) {
                try {
                    proc.call(elctx, e);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        } else if (xs instanceof Map) {
            for (Object e : ((Map)xs).entrySet()) {
                try {
                    proc.call(elctx, e);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        } else if (xs instanceof CharSequence) {
            CharSequence cs = (CharSequence)xs;
            for (int i = 0, len = cs.length(); i < len; i++) {
                try {
                    proc.call(elctx, cs.charAt(i));
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        } else if (xs instanceof Object[]) {
            for (Object e : (Object[])xs) {
                try {
                    proc.call(elctx, e);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        } else if (xs != null && xs.getClass().isArray()) {
            for (int i = 0, len = Array.getLength(xs); i < len; i++) {
                try {
                    proc.call(elctx, Array.get(xs, i));
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        }
    }

    @Expando(name="do")
    public static Object __do(ELContext elctx, Object arg, Procedure proc) {
        if (arg == null) {
            return null;
        }

        if (arg instanceof Iterable) {
            for (Object e : (Iterable)arg) {
                try {
                    proc.call_with(elctx, e);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
            return null;
        }

        if (arg instanceof Object[]) {
            for (Object e : (Object[])arg) {
                try {
                    proc.call_with(elctx, e);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
            return null;
        }

        if (arg.getClass().isArray()) {
            for (int i = 0, len = Array.getLength(arg); i < len; i++) {
                try {
                    proc.call_with(elctx, Array.get(arg, i));
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
            return null;
        }

        return proc.call_with(elctx, arg);
    }

    @Expando(name={"foldl", "foldLeft", "fold", "reduce"}, scope={EXPANDO,GLOBAL})
    public static Object foldl(ELContext elctx, Object lst, Object init, Closure proc) {
        if (lst instanceof Iterable) {
            for (Object e : (Iterable)lst) {
                init = proc.call(elctx, init, e);
            }
        } else if (lst instanceof Map) {
            for (Object e : ((Map)lst).entrySet()) {
                init = proc.call(elctx, init, e);
            }
        } else if (lst instanceof CharSequence) {
            CharSequence cs = (CharSequence)lst;
            for (int i = 0, len = cs.length(); i < len; i++) {
                init = proc.call(elctx, init, cs.charAt(i));
            }
        } else if (lst instanceof Object[]) {
            for (Object e : (Object[])lst) {
                init = proc.call(elctx, init, e);
            }
        } else if (lst != null && lst.getClass().isArray()) {
            for (int i = 0, len = Array.getLength(lst); i < len; i++) {
                init = proc.call(elctx, init, Array.get(lst, i));
            }
        }
        return init;
    }

    @Expando(name={"foldr", "foldRight"}, scope={EXPANDO,GLOBAL})
    public static Object foldr(ELContext elctx, Object lst, Object end, Closure proc) {
        if ((lst instanceof List) && (lst instanceof RandomAccess) &&
                !((lst instanceof Range) && ((Range)lst).isUnbound())) {
            ListIterator i = ((List)lst).listIterator(((List)lst).size());
            while (i.hasPrevious()) {
                end = proc.call(elctx, i.previous(), end);
            }
        } else if (lst instanceof Collection) {
            return seq_foldr(elctx, coerceToSeq(lst), end, proc);
        } else if (lst instanceof CharSequence) {
            CharSequence cs = (CharSequence)lst;
            for (int i = cs.length(); --i >= 0; ) {
                end = proc.call(elctx, cs.charAt(i), end);
            }
        } else if (lst instanceof Object[]) {
            Object[] a = (Object[])lst;
            for (int i = a.length; --i >= 0; ) {
                end = proc.call(elctx, a[i], end);
            }
        } else if (lst != null && lst.getClass().isArray()) {
            for (int i = Array.getLength(lst); --i >= 0; ) {
                end = proc.call(elctx, Array.get(lst, i), end);
            }
        }
        return end;
    }

    private static Object seq_foldr(ELContext elctx, Seq seq, Object end, Closure proc) {
        if (seq.isEmpty()) {
            return end;
        } else {
            Closure[] args = new Closure[2];
            args[0] = new DelayHead(seq);
            args[1] = new FoldRightRec(seq, end, proc);
            return proc.invoke(elctx, args);
        }
    }

    private static class DelayHead extends DelayClosure {
        private Seq seq;

        DelayHead(Seq seq) {
            this.seq = seq;
        }

        protected Object force(ELContext elctx) {
            Seq s = seq;
            seq = null;
            return s.get();
        }

        protected void forget() {
            seq = null;
        }
    }

    private static class FoldRightRec extends DelayClosure {
        private Seq seq;
        private Object end;
        private Closure proc;

        FoldRightRec(Seq seq, Object end, Closure proc) {
            this.seq = seq; this.end = end; this.proc = proc;
        }

        protected Object force(ELContext elctx) {
            Object result = seq_foldr(elctx, seq.tail(), end, proc);
            seq = null; end = null; proc = null;
            return result;
        }

        protected void forget() {
            seq = null; end = null; proc = null;
        }
    }

    public static Object fold2(ELContext elctx, Object a, Object b, Object z, Closure p) {
        if (a instanceof Iterable && b instanceof Iterable) {
            Iterator ia = ((Iterable)a).iterator();
            Iterator ib = ((Iterable)b).iterator();
            while (ia.hasNext() && ib.hasNext()) {
                z = p.call(elctx, z, ia.next(), ib.next());
            }
        } else if (a instanceof Object[] && b instanceof Object[]) {
            Object[] aa = (Object[])a;
            Object[] ba = (Object[])b;
            int size = Math.min(aa.length, ba.length);
            for (int i = 0; i < size; i++) {
                z = p.call(elctx, z, aa[i], ba[i]);
            }
        } else if (a.getClass().isArray() && b.getClass().isArray()) {
            int size = Math.min(Array.getLength(a), Array.getLength(b));
            for (int i = 0; i < size; i++) {
                z = p.call(elctx, z, Array.get(a,i), Array.get(b,i));
            }
        }
        return z;
    }

    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq unfold(ELContext elctx, Object init, Closure next, Closure pred) {
        return UnfoldSeq.make(elctx, init, next, pred);
    }

    @Expando(scope={EXPANDO,GLOBAL})
    public static Seq unfold(ELContext elctx, Object init, Closure next) {
        return UnfoldSeq.make(elctx, init, next, null);
    }

    private static class UnfoldSeq extends DelaySeq {
        private Closure next;
        private Closure pred;

        private UnfoldSeq(Object init, Closure next, Closure pred) {
            this.head = init;
            this.next = next;
            this.pred = pred;
        }

        static Seq make(ELContext elctx, Object init, Closure next, Closure pred) {
            if (pred != null && pred.test(elctx, init)) {
                return Cons.make(init);
            } else if (pred == null && init == null) {
                return Cons.nil();
            } else {
                return new UnfoldSeq(init, next, pred);
            }
        }

        protected void force(ELContext elctx) {
            if (next != null) {
                Closure n = next, p = pred;
                next = pred = null;
                try {
                    tail = make(elctx, n.call(elctx, head), n, p);
                } catch (Control.Break b) {
                    tail = Cons.nil();
                }
            }
        }
    }

    /**
     * 按条件删除集合元素.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static void removeIf(ELContext elctx, Object xs, Closure pred) {
        Iterator it;
        if (xs instanceof Iterable) {
            it = ((Iterable)xs).iterator();
        } else if (xs instanceof Map) {
            it = ((Map)xs).entrySet().iterator();
        } else {
            return; // throw?
        }

        while (it.hasNext()) {
            if (pred.test(elctx, it.next())) {
                it.remove();
            }
        }
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Collection sort(Collection c) {
        Object[] a = c.toArray();
        Arrays.sort(a);
        return ArraySeq.make(a);
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Collection sort(ELContext elctx, Collection c, Closure comp) {
        Object[] a = c.toArray();
        Arrays.sort(a, make_comparator(elctx, comp));
        return ArraySeq.make(a);
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object[] sort(Object[] a) {
        a = a.clone();
        Arrays.sort(a);
        return a;
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object[] sort(ELContext elctx, Object[] a, Closure comp) {
        a = a.clone();
        Arrays.sort(a, make_comparator(elctx, comp));
        return a;
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static byte[] sort(byte[] a) {
        a = a.clone();
        Arrays.sort(a);
        return a;
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static short[] sort(short[] a) {
        a = a.clone();
        Arrays.sort(a);
        return a;
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static char[] sort(char[] a) {
        a = a.clone();
        Arrays.sort(a);
        return a;
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static int[] sort(int[] a) {
        a = a.clone();
        Arrays.sort(a);
        return a;
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static long[] sort(long[] a) {
        a = a.clone();
        Arrays.sort(a);
        return a;
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static float[] sort(float[] a) {
        a = a.clone();
        Arrays.sort(a);
        return a;
    }

    /**
     * 对集合元素进行排序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static double[] sort(double[] a) {
        a = a.clone();
        Arrays.sort(a);
        return a;
    }

    /**
     * 对集合元素反序.
     *
     * 用法: reverse([a, b, c]) ===> [c, b, a]
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object reverse(Object base) {
        if (base instanceof Seq) {
            return ((Seq)base).reverse();
        } else if (base instanceof String) {
            char[] cs = ((String)base).toCharArray();
            int size = cs.length;
            for (int i = 0, mid = size>>1, j = size-1; i < mid; i++, j--) {
                char t = cs[i]; cs[i] = cs[j]; cs[j] = t;
            }
            return new String(cs);
        } else if (base instanceof Collection) {
            ArrayList rev = new ArrayList((Collection)base);
            Collections.reverse(rev);
            return rev;
        } else if (base != null && base.getClass().isArray()) {
            Collections.reverse(new ArrayAsList(base));
            return base;
        } else {
            return null;
        }
    }

    /**
     * 打乱集合中元素的顺序.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static Object shuffle(Object base) {
        if (base instanceof Collection) {
            List lst = new ArrayList((Collection)base);
            Collections.shuffle(lst);
            return lst;
        } else if (base != null && base.getClass().isArray()) {
            Collections.shuffle(new ArrayAsList(base));
            return base; // FIXME
        } else {
            return null;
        }
    }

    /**
     * 将集合元素拼接成一个字符串.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static String join(Iterable c) {
        return join(c, null);
    }

    /**
     * 使用指定的分隔符将集合元素拼接成一个字符串.
     */
    @Expando(scope={EXPANDO,GLOBAL})
    public static String join(Iterable c, String sep) {
        StringBuilder buf = new StringBuilder();
        Iterator i = c.iterator();
        boolean hasNext = i.hasNext();
        while (hasNext) {
            Object o = i.next();
            buf.append(coerceToString(o));
            hasNext = i.hasNext();
            if (hasNext && sep != null) {
                buf.append(sep);
            }
        }
        return buf.toString();
    }

    // BitSet operations

    @Expando(name="[]")
    public static boolean __bitset_get__(BitSet s, int i) {
        return s.get(i);
    }

    @Expando(name="[]")
    public static BitSet __bitset_get__(BitSet s, Range r) {
        int from = (int)r.getBegin();
        int step = (int)r.getEnd();
        int end = r.isUnbound() ? s.length() : (int)r.getEnd();
        if (step == 1) {
            return s.get(from, end);
        } else if (step < 0) {
            BitSet t = new BitSet();
            for (int i = from; i >= 0; i += step)
                if (s.get(i)) t.set(i);
            return t;
        } else {
            BitSet t = new BitSet();
            for (int i = from; i <= end; i += step)
                if (s.get(i)) t.set(i);
            return t;
        }
    }

    @Expando(name="[]=")
    public static boolean __bitset_set__(BitSet s, int i, boolean b) {
        s.set(i, b);
        return b;
    }

    @Expando(name="[]=")
    public static void __bitset_set__(BitSet s, Range r, boolean b) {
        int from = (int)r.getBegin();
        int step = (int)r.getStep();
        int end = r.isUnbound() ? s.length() : (int)r.getEnd();
        if (step == 1) {
            s.set(from, end, b);
        } else if (step < 0) {
            for (int i = from; i >= 0; i += step) {
                s.set(i, b);
            }
        } else {
            for (int i = from; i <= end; i += step) {
                s.set(i, b);
            }
        }
    }

    @Expando(name="^!")
    public static BitSet __bitset_flip__(BitSet s) {
        s = (BitSet)s.clone();
        s.flip(0, s.length());
        return s;
    }

    @Expando(name="^&")
    public static BitSet __bitset_and__(BitSet s1, BitSet s2) {
        BitSet s = (BitSet)s1.clone();
        s.and(s2);
        return s;
    }

    @Expando(name="^&=")
    public static BitSet __bitset_iand__(BitSet s1, BitSet s2) {
        s1.and(s2);
        return s1;
    }

    @Expando(name="^|")
    public static BitSet __bitset_or__(BitSet s1, BitSet s2) {
        BitSet s = (BitSet)s1.clone();
        s.or(s2);
        return s;
    }

    @Expando(name="^|=")
    public static BitSet __bitset_ior__(BitSet s1, BitSet s2) {
        s1.or(s2);
        return s1;
    }

    @Expando(name="^^")
    public static BitSet __bitset_xor__(BitSet s1, BitSet s2) {
        BitSet s = (BitSet)s1.clone();
        s.xor(s2);
        return s;
    }

    @Expando(name="^^=")
    public static BitSet __bitset_ixor__(BitSet s1, BitSet s2) {
        s1.xor(s2);
        return s1;
    }

    // String and regular expression functions

    /**
     * 判断字符串是否与指定的正则表达式相匹配.
     */
    @Expando
    public static boolean matches(CharSequence str, Object pattern) {
        if (pattern instanceof Pattern) {
            return ((Pattern)pattern).matcher(str).matches();
        } else {
            return str.toString().matches(pattern.toString());
        }
    }

    /**
     * 判断字符串是否与指定的正则表达式相匹配.
     */
    @Expando
    public static String[] match(CharSequence str, Object arg) {
        Pattern p;
        if (arg instanceof Pattern) {
            p = (Pattern)arg;
        } else {
            p = Pattern.compile(coerceToString(arg));
        }

        Matcher m = p.matcher(str);
        if (m.matches()) {
            int count = m.groupCount();
            String[] result = new String[count+1];
            for (int i = 0; i <= count; i++) {
                result[i] = m.group(i);
            }
            return result;
        }
        return null;
    }

    /**
     * 替换字符串中的模式.
     */
    @Expando
    public static String replace(String str, Object pattern, String repl) {
        if (pattern instanceof Pattern) {
            Matcher m = ((Pattern)pattern).matcher(str);
            return m.replaceAll(repl);
        } else if (pattern instanceof Character) {
            return str.replace((Character)pattern, coerceToCharacter(repl));
        } else {
            return str.replace(coerceToString(pattern), coerceToString(repl));
        }
    }

    // Character functions

    @Expando
    public static boolean isLower(Character c) {
        return Character.isLowerCase(c);
    }

    @Expando
    public static boolean isLowerCase(Character c) {
        return Character.isLowerCase(c);
    }

    @Expando
    public static boolean isUpper(Character c) {
        return Character.isUpperCase(c);
    }

    @Expando
    public static boolean isUpperCase(Character c) {
        return Character.isUpperCase(c);
    }

    @Expando
    public static boolean isDigit(Character c) {
        return Character.isDigit(c);
    }

    @Expando
    public static boolean isLetter(Character c) {
        return Character.isLetter(c);
    }

    @Expando
    public static boolean isLetterOrDigit(Character c) {
        return Character.isLetterOrDigit(c);
    }

    @Expando
    public static boolean isJavaIdentifierStart(Character c) {
        return Character.isJavaIdentifierStart(c);
    }

    @Expando
    public static boolean isJavaIdentifierPart(Character c) {
        return Character.isJavaIdentifierPart(c);
    }

    @Expando
    public static boolean isWhitespace(Character c) {
        return Character.isWhitespace(c);
    }

    @Expando
    public static boolean isSpace(Character c) {
        return Character.isWhitespace(c);
    }

    @Expando
    public static boolean isControl(Character c) {
        return Character.isISOControl(c);
    }

    @Expando
    public static char toLowerCase(Character c) {
        return Character.toLowerCase(c);
    }

    @Expando
    public static char toLower(Character c) {
        return Character.toLowerCase(c);
    }

    @Expando
    public static char toUpperCase(Character c) {
        return Character.toUpperCase(c);
    }

    @Expando
    public static char toUpper(Character c) {
        return Character.toUpperCase(c);
    }

    // Other functions

    private static Random prng = new Random();

    /**
     * 设置随机种子.
     */
    public static void srand(long seed) {
        prng.setSeed(seed);
    }

    /**
     * 返回0到1之间以浮点数表示的随机数.
     */
    public static double rand() {
        return prng.nextDouble();
    }

    /**
     * 返回0到n(不包括n)之间的整数随机数
     */
    public static int rand(int n) {
        return prng.nextInt(n);
    }

    /**
     * 判断给定参数是否是一个过程.
     */
    public static boolean is_procedure(Object obj) {
        return (obj instanceof Closure) && ((Closure)obj).isProcedure();
    }

    /**
     * 使用当前环境对以字符串表示的表达式求值.
     */
    public static Object eval(ELContext elctx, String exp) {
        return eval(exp, new EvaluationContext(elctx));
    }

    /**
     * 使用给定的变量绑定对以字符串表示的表达式求值.
     */
    public static Object eval(ELContext elctx, String exp, Map<String,Object> bindings) {
        VariableMapperImpl vm = new VariableMapperImpl(bindings);
        return eval(exp, new EvaluationContext(elctx, null, vm));
    }

    /**
     * 使用给定环境对以字符串表示的表达式求值.
     */
    public static Object eval(String exp, EvaluationContext env) {
        return Parser.parseExpression(exp).getValue(env);
    }

    /**
     * 创建一个线程, 线程启动时调用给定的过程.
     */
    public static Thread thread(final ELContext elctx, final Closure proc) {
        return new Thread() {
            public void run() {
                // invoke procedure in the current context
                proc.call(DelegatingELContext.get(elctx));
            }
        };
    }

    /**
     * 创建并启动一个线程, 线程启动时调用给定的过程.
     */
    public static Thread start_thread(ELContext elctx, Closure proc) {
        Thread t = thread(elctx, proc);
        t.start();
        return t;
    }

    /**
     * 在控制台输出给定的对象.
     */
    public static void print(ELContext elctx, Object obj) {
        PrintWriter out = getPrintWriter(elctx);
        try {
            print0(out, obj);
            out.println();
            out.flush();
        } catch (RuntimeException ex) {
            out.flush();
            throw ex;
        }
    }

    private static void print0(PrintWriter out, Object obj) {
        if (obj == null) {
            out.print("null");
        } else if (obj instanceof Range || obj instanceof CharRange) {
            out.print(obj.toString());
        } else if (obj instanceof Seq) {
            boolean sep = false;
            out.print("[");
            for (Seq xs = (Seq)obj; !xs.isEmpty(); xs = xs.tail()) {
                Object x = xs.get();
                if (sep) out.print(", ");
                sep = true;
                if (x instanceof String) {
                    StringBuilder buf = new StringBuilder();
                    TypeCoercion.escape(buf, (String)x);
                    out.print(buf);
                } else if (x instanceof Character) {
                    String esc = TypeCoercion.escape((Character)x);
                    out.print("#'" + (esc != null ? esc : x) + "'");
                } else {
                    print0(out, x);
                }
                out.flush();
            }
            out.print("]");
        } else {
            out.print(coerceToString(obj));
        }
    }

    private static PrintWriter getPrintWriter(ELContext elctx) {
        ScriptContext sctx = (ScriptContext)elctx.getContext(ScriptContext.class);
        if (sctx != null) {
            Writer w = sctx.getWriter();
            if (w instanceof PrintWriter) {
                return (PrintWriter)w;
            } else {
                return new PrintWriter(w);
            }
        } else {
            return new PrintWriter(System.out);
        }
    }

    /**
     * 在控制台输出一个换行符.
     */
    public static void nl(ELContext elctx) {
        PrintWriter out = getPrintWriter(elctx);
        out.println();
        out.flush();
    }

    /**
     * 在控制台输出一个格式化字符串.
     */
    public static void printf(ELContext elctx, String format, Object... args) {
        getPrintWriter(elctx).printf(format, args);
    }

    /**
     * 返回一个格式化字符串.
     */
    public static String sprintf(String format, Object... args) {
        return String.format(format, args);
    }

    /**
     * 格式化一个数值.
     */
    @Expando
    public static String format(ELContext elctx, Number value) {
        return NumberFormat.getInstance(getLocale(elctx)).format(value);
    }

    /**
     * 格式化一个数值.
     */
    @Expando
    public static String format(ELContext elctx, Number value, String pattern) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(getLocale(elctx));
        DecimalFormat format = new DecimalFormat(pattern, symbols);
        return format.format(value);
    }

    /**
     * 格式化一个日期值
     */
    @Expando
    public static String format(ELContext elctx, Date value, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, getLocale(elctx));
        return format.format(value);
    }

    // Builtin Operators -----------------------------

    private static final ELNode.CAT __CAT__ = new ELNode.CAT(-1, null, null);
    private static final ELNode.ADD __ADD__ = new ELNode.ADD(-1, null, null);
    private static final ELNode.SUB __SUB__ = new ELNode.SUB(-1, null, null);
    private static final ELNode.MUL __MUL__ = new ELNode.MUL(-1, null, null);
    private static final ELNode.DIV __DIV__ = new ELNode.DIV(-1, null, null);
    private static final ELNode.IDIV __IDIV__ = new ELNode.IDIV(-1, null, null);
    private static final ELNode.REM __REM__ = new ELNode.REM(-1, null, null);
    private static final ELNode.POW __POW__ = new ELNode.POW(-1, null, null);
    private static final ELNode.BITNOT __BITNOT__ = new ELNode.BITNOT(-1, null);
    private static final ELNode.BITOR  __BITOR__  = new ELNode.BITOR(-1, null, null);
    private static final ELNode.BITAND __BITAND__ = new ELNode.BITAND(-1, null, null);
    private static final ELNode.XOR __XOR__ = new ELNode.XOR(-1, null, null);
    private static final ELNode.SHL __SHL__ = new ELNode.SHL(-1, null, null);
    private static final ELNode.SHR __SHR__ = new ELNode.SHR(-1, null, null);
    private static final ELNode.USHR __USHR__ = new ELNode.USHR(-1, null, null);
    private static final ELNode.LT __LT__ = new ELNode.LT(-1, null, null);
    private static final ELNode.LE __LE__ = new ELNode.LE(-1, null, null);
    private static final ELNode.GT __GT__ = new ELNode.GT(-1, null, null);
    private static final ELNode.GE __GE__ = new ELNode.GE(-1, null, null);
    private static final ELNode.EQ __EQ__ = new ELNode.EQ(-1, null, null);
    private static final ELNode.NE __NE__ = new ELNode.NE(-1, null, null);
    private static final ELNode.IDEQ __IDEQ__ = new ELNode.IDEQ(-1, null, null);
    private static final ELNode.IDNE __IDNE__ = new ELNode.IDNE(-1, null, null);
    private static final ELNode.EMPTY __EMPTY__ = new ELNode.EMPTY(-1, null);

    @Expando(name="then", scope=GLOBAL)
    public static Object __then__(ELContext elctx, Object x, Object y) {
        return y;
    }

    @Expando(name="~", scope=GLOBAL)
    public static Object __cat__(ELContext elctx, Object x, Object y) {
        return __CAT__.getValue(elctx, x, y);
    }

    @Expando(name="+", scope=GLOBAL)
    public static Object __add__(ELContext elctx, Object x, Object y) {
        return __ADD__.getValue(elctx, x, y);
    }

    @Expando(name="-", scope=GLOBAL)
    public static Object __sub__(ELContext elctx, Object x, Object y) {
        return __SUB__.getValue(elctx, x, y);
    }

    @Expando(name="*", scope=GLOBAL)
    public static Object __mul__(ELContext elctx, Object x, Object y) {
        return __MUL__.getValue(elctx, x, y);
    }

    @Expando(name="/", scope=GLOBAL)
    public static Object __div__(ELContext elctx, Object x, Object y) {
        return __DIV__.getValue(elctx, x, y);
    }

    @Expando(name="div", scope=GLOBAL)
    public static Object __idiv__(ELContext elctx, Object x, Object y) {
        return __IDIV__.getValue(elctx, x, y);
    }
    
    @Expando(name="%", scope=GLOBAL)
    public static Object __rem__(ELContext elctx, Object x, Object y) {
        return __REM__.getValue(elctx, x, y);
    }

    @Expando(name="^", scope=GLOBAL)
    public static Object __pow__(ELContext elctx, Object x, Object y) {
        return __POW__.getValue(elctx, x, y);
    }

    @Expando(name="^!", scope=GLOBAL)
    public static Object __bitnot__(ELContext elctx, Object x) {
        return __BITNOT__.getValue(elctx, x);
    }

    @Expando(name="^|", scope=GLOBAL)
    public static Object __bitor__(ELContext elctx, Object x, Object y) {
        return __BITOR__.getValue(elctx, x, y);
    }

    @Expando(name="^&", scope=GLOBAL)
    public static Object __bitand__(ELContext elctx, Object x, Object y) {
        return __BITAND__.getValue(elctx, x, y);
    }

    @Expando(name="^^", scope=GLOBAL)
    public static Object __xor__(ELContext elctx, Object x, Object y) {
        return __XOR__.getValue(elctx, x, y);
    }

    @Expando(name="<<", scope=GLOBAL)
    public static Object __shl__(ELContext elctx, Object x, Object y) {
        return __SHL__.getValue(elctx, x, y);
    }

    @Expando(name=">>", scope=GLOBAL)
    public static Object __shr__(ELContext elctx, Object x, Object y) {
        return __SHR__.getValue(elctx, x, y);
    }

    @Expando(name=">>>", scope=GLOBAL)
    public static Object __ushr__(ELContext elctx, Object x, Object y) {
        return __USHR__.getValue(elctx, x, y);
    }

    @Expando(name="??", scope=GLOBAL)
    public static Object __coalesce__(ELContext elctx, Object x, Closure y) {
        return x != null ? x : y.getValue(elctx);
    }

    @Expando(name="<", scope=GLOBAL)
    public static boolean __lt__(ELContext elctx, Object x, Object y) {
        return (Boolean)__LT__.getValue(elctx, x, y);
    }

    @Expando(name="<=", scope=GLOBAL)
    public static boolean __le__(ELContext elctx, Object x, Object y) {
        return (Boolean)__LE__.getValue(elctx, x, y);
    }

    @Expando(name=">", scope=GLOBAL)
    public static boolean __gt__(ELContext elctx, Object x, Object y) {
        return (Boolean)__GT__.getValue(elctx, x, y);
    }

    @Expando(name=">=", scope=GLOBAL)
    public static boolean __ge__(ELContext elctx, Object x, Object y) {
        return (Boolean)__GE__.getValue(elctx, x, y);
    }

    @Expando(name="==", scope=GLOBAL)
    public static boolean __eq__(ELContext elctx, Object x, Object y) {
        return (Boolean)__EQ__.getValue(elctx, x, y);
    }

    @Expando(name="!=", scope=GLOBAL)
    public static boolean __ne__(ELContext elctx, Object x, Object y) {
        return (Boolean)__NE__.getValue(elctx, x, y);
    }

    @Expando(name="===", scope=GLOBAL)
    public static boolean __ideq__(ELContext elctx, Object x, Object y) {
        return (Boolean)__IDEQ__.getValue(elctx, x, y);
    }

    @Expando(name="!==", scope=GLOBAL)
    public static boolean __idne__(ELContext elctx, Object x, Object y) {
        return (Boolean)__IDNE__.getValue(elctx, x, y);
    }

    @Expando(name="!", scope=GLOBAL)
    public static Object __not__(Object x) {
        return !TypeCoercion.coerceToBoolean(x);
    }

    @Expando(name="&&", scope=GLOBAL)
    public static boolean __and__(ELContext elctx, boolean x, Closure y) {
        return x && TypeCoercion.coerceToBoolean(y.getValue(elctx));
    }

    @Expando(name="||", scope=GLOBAL)
    public static boolean __or__(ELContext elctx, boolean x, Closure y) {
        return x || TypeCoercion.coerceToBoolean(y.getValue(elctx));
    }

    public static Object empty(ELContext elctx, Object x) {
        return __EMPTY__.getValue(elctx, x);
    }

    // Expando Operators ------------------------------
    
    @Expando(name="<<")
    public static Object __lshift__(Appendable lhs, Object rhs) throws IOException {
        lhs.append(coerceToString(rhs));
        return lhs;
    }

    @Expando(name="<<")
    public static Object __lshift__(Collection lhs, Object rhs) {
        if (rhs instanceof Collection) {
            lhs.addAll((Collection)rhs);
        } else {
            lhs.add(rhs);
        }
        return lhs;
    }

    @Expando(name="+")
    public static Seq __add__(Seq lhs, Object rhs) {
        if (rhs instanceof Collection) {
            return lhs.append(coerceToSeq(rhs));
        } else {
            return lhs.append(Cons.make(rhs));
        }
    }

    @Expando(name="+")
    public static Collection __add__(Collection lhs, Object rhs) {
        lhs = new ArrayList(lhs);
        if (rhs instanceof Collection) {
            lhs.addAll((Collection)rhs);
        } else {
            lhs.add(rhs);
        }
        return lhs;
    }

    @Expando(name="+=")
    public static Object __iadd__(Collection lhs, Object rhs) {
        if (rhs instanceof Collection) {
            lhs.addAll((Collection)rhs);
        } else {
            lhs.add(rhs);
        }
        return lhs;
    }

    @Expando(name="-")
    public static Seq __sub__(Seq lhs, Object rhs) {
        if (canCoerceToSeq(rhs)) {
            return MinusSeq.make(lhs, coerceToSeq(rhs));
        } else {
            return MinusSeq.make(lhs, rhs);
        }
    }

    static class MinusSeq extends DelaySeq {
        private Seq xs;
        private Object el;

        private MinusSeq(Seq xs, Object el) {
            this.xs = xs;
            this.el = el;
        }

        static Seq make(Seq xs, Object el) {
            if (xs.isEmpty()) {
                return xs;
            } else {
                return new MinusSeq(xs, el);
            }
        }

        static Seq make(Seq xs, Seq ys) {
            if (ys.isEmpty() || xs.isEmpty()) {
                return xs;
            } else {
                Object y = ys.get(); ys = ys.tail();
                return make(new MinusSeq(xs, y), ys);
            }
        }

        protected void force(ELContext elctx) {
            if (xs != null) {
                while (true) {
                    Object x = xs.get(); xs = xs.tail();
                    if (ELNode.EQ.equals(elctx, x, el)) {
                        if (xs.isEmpty()) {
                            head = null;
                            tail = null;
                            break;
                        }
                    } else {
                        head = x;
                        tail = make(xs, el);
                        break;
                    }
                }
                xs = null;
                el = null;
            }
        }
    }

    @Expando(name="-")
    public static Collection __sub__(Collection lhs, Object rhs) {
        lhs = new ArrayList(lhs);
        if (rhs instanceof Collection) {
            lhs.removeAll((Collection)rhs);
        } else {
            lhs.remove(rhs);
        }
        return lhs;
    }

    @Expando(name="-=")
    public static Collection __isub__(Collection lhs, Object rhs) {
        if (rhs instanceof Collection) {
            lhs.removeAll((Collection)rhs);
        } else {
            lhs.remove(rhs);
        }
        return lhs;
    }

    @Expando(name="^|")
    public static Collection __union__(Collection lhs, Collection rhs) {
        if (lhs instanceof SortedSet) {
            lhs = new TreeSet(lhs);
            lhs.addAll(rhs);
        } else if (lhs instanceof Set) {
            lhs = new HashSet(lhs);
            lhs.addAll(rhs);
        } else {
            lhs = new ArrayList(lhs);
            lhs.removeAll(rhs);
            lhs.addAll(rhs);
        }
        return lhs;
    }

    @Expando(name="^|=")
    public static Collection __iunion__(Collection lhs, Collection rhs) {
        if (lhs instanceof Set) {
            lhs.addAll(rhs);
        } else {
            lhs.removeAll(rhs);
            lhs.addAll(rhs);
        }
        return lhs;
    }

    @Expando(name="^&")
    public static Collection __intersect__(Collection lhs, Collection rhs) {
        if (lhs instanceof SortedSet) {
            lhs = new TreeSet(lhs);
            lhs.retainAll(rhs);
        } else if (lhs instanceof Set) {
            lhs = new HashSet(lhs);
            lhs.retainAll(rhs);
        } else {
            lhs = new ArrayList(lhs);
            lhs.retainAll(rhs);
        }
        return lhs;
    }

    @Expando(name="^&=")
    public static Collection __iintersect__(Collection lhs, Collection rhs) {
        lhs.retainAll(rhs);
        return lhs;
    }

    // Implementation ----------------------------

    /** Yield a block. */
    private static Object yield(ELContext elctx, Closure proc) {
        Object result = proc.getValue(elctx);
        if (result instanceof Closure) {
            result = ((Closure)result).invoke(elctx, NO_PARAMS);
        }
        return result;
    }

    private static Comparator make_comparator(final ELContext elctx, final Closure comp) {
        return new Comparator() {
            public int compare(Object o1, Object o2) {
                return coerceToInt(comp.call(elctx, o1, o2));
            }
        };
    }

    static Locale getLocale(ELContext elctx) {
        if (elctx != null) {
            Locale locale = elctx.getLocale();
            if (locale != null) {
                return locale;
            }
        }
        return Locale.getDefault();
    }

    private static class ArrayAsList extends AbstractList
        implements RandomAccess, Serializable
    {
        private final Object a;

        ArrayAsList(Object array) {
            this.a = array;
        }

        public int size() {
            return Array.getLength(a);
        }

        public Object[] toArray() {
            if (a instanceof Object[]) {
                return (Object[])a;
            } else {
                return super.toArray();
            }
        }

        public Object get(int index) {
            return Array.get(a, index);
        }

        public Object set(int index, Object value) {
            Object oldValue = Array.get(a, index);
            Array.set(a, index, value);
            return oldValue;
        }

        public int indexOf(Object o) {
            int size = Array.getLength(a);
            if (o == null) {
                for (int i=0; i<size; i++) {
                    if (Array.get(a, i) == null) {
                        return i;
                    }
                }
            } else {
                for (int i=0; i<size; i++) {
                    if (o.equals(Array.get(a, i))) {
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
}
