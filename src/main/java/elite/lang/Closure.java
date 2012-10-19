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

import java.lang.reflect.Modifier;
import javax.el.ValueExpression;
import javax.el.MethodInfo;
import javax.el.ELContext;
import javax.el.VariableMapper;

import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.closure.AbstractClosure;
import org.operamasks.el.eval.closure.ValueChangeListener;
import org.operamasks.el.eval.closure.MetaData;
import static org.operamasks.el.eval.ELEngine.getCallArgs;
import elite.lang.annotation.Expando;

/**
 * Encapsulate an expression and it's associated evaluation context.
 */
public abstract class Closure extends ValueExpression
{
    /**
     * Get the evaluation context associated with this closure.
     */
    public EvaluationContext getContext() {
        return null;
    }

    /**
     * Get the evaluation context associated with this closure. If there
     * is no evaluation context associated with this closure then use
     * the given <em>ELContext</em> to initialize the evaluation context.
     */
    public EvaluationContext getContext(ELContext elctx) {
        return null;
    }

    /**
     * Set environment. Internal use only.
     */
    public void _setenv(ELContext elctx, VariableMapper env) {}

    /**
     * Set the ValueChangeListener to receive value changed notification.
     */
    public void setValueChangeListener(ValueChangeListener listener) {}

    /**
     * Returns modifiers for the closure.
     */
    public int getModifiers() {
        return 0;
    }

    /**
     * Set the modifiers for this closure.
     */
    public void setModifiers(int modifiers) {}

    // Modifier helper methods
    public boolean isPrivate()   { return (getModifiers() & Modifier.PRIVATE) != 0; }
    public boolean isProtected() { return (getModifiers() & Modifier.PROTECTED) != 0; }
    public boolean isPublic()    { return (getModifiers() & (Modifier.PRIVATE|Modifier.PROTECTED)) == 0; }
    public boolean isStatic()    { return (getModifiers() & Modifier.STATIC) != 0; }
    public boolean isAbstract()  { return (getModifiers() & Modifier.ABSTRACT) != 0; }
    public boolean isFinal()     { return (getModifiers() & Modifier.FINAL) != 0; }
    public boolean isSynchronized() { return (getModifiers() & Modifier.SYNCHRONIZED) != 0; }
    public boolean isProcedure() { return false; }

    /**
     * Get the number of arguments the function takes.
     */
    public abstract int arity(ELContext elctx);

    /**
     * Get the method information for the given arguments.
     */
    public abstract MethodInfo getMethodInfo(ELContext elctx);

    /**
     * Returns true if an annotation for the specified type
     * is present on this closure, else false.
     */
    public boolean isAnnotationPresent(String type) {
        return false;
    }

    /**
     * Returns this closure's annotation for the specified type if
     * such an annotation is present, else null.
     */
    public Annotation getAnnotation(String type) {
        return null;
    }

    /**
     * Returns all annotations present on this closure.  (Returns an array
     * of length zero if this closure has no annotations.)  The caller of
     * this method is free to modify the returned array; it will have no
     * effect on the arrays returned to other callers.
     */
    public Annotation[] getAnnotations() {
        return new Annotation[0];
    }

    /**
     * Add an annotation to the closure meta data.
     */
    public void addAnnotation(Annotation annotation) {}

    /**
     * Remove an annotation from the closure meta data.
     */
    public void removeAnnotation(String type) {}

    /**
     * Set the meta data information for this closure.
     */
    public void setMetaData(MetaData metadata) {}

    /**
     * Invoke the closure with the given arguments.
     */
    public abstract Object invoke(ELContext elctx, Closure[] args);

    /**
     * Call the procedure with the given arguments.
     */
    public Object call(ELContext elctx, Object... args) {
        return invoke(elctx, getCallArgs(args));
    }

    /**
     * Call the procedure with the given arguments and return a boolean value
     * as the test result.
     */
    public boolean test(ELContext elctx, Object... args) {
        return TypeCoercion.coerceToBoolean(call(elctx, args));
    }

    /**
     * The curry() procedure.
     *
     * @param args the currying arguments
     * @return the curried procedure
     */
    public Closure curry(final Object... args) {
        return new AbstractClosure() {
            public int arity(ELContext elctx) {
                return Closure.this.arity(elctx) - args.length;
            }

            public Object invoke(ELContext elctx, Closure[] extra) {
                return Closure.this.invoke(elctx, getCallArgs(args, extra));
            }

            public boolean isProcedure() {
                return true;
            }

            public String toString() {
                return "#<curry:" + Closure.this + ">";
            }
        };
    }

    /**
     * Create a procedure that called with flipped argumented.
     */
    public Closure flip() {
        return new AbstractClosure() {
            public int arity(ELContext elctx) {
                return Closure.this.arity(elctx);
            }

            public Object invoke(ELContext elctx, Closure[] args) {
                // reverse the arguments
                int size = args.length;
                for (int i=0, mid=size>>1, j=size-1; i<mid; i++, j--) {
                    Closure t = args[i]; args[i] = args[j]; args[j] = t;
                }
                return Closure.this.invoke(elctx, args);
            }

            public boolean isProcedure() {
                return true;
            }

            public String toString() {
                return "#<flip:" + Closure.this + ">";
            }
        };
    }

    /**
     * Compose this procedure with another procedure.
     *
     * @param other the procedure to be composed
     * @return the composed procedure
     */
    public Closure compose(Closure other) {
        return new Compose(this, other);
    }

    /**
     * Returns the power of procedure.
     *
     * @param n the exponent.
     * @return the powered procedure.
     */
    @Expando(name="^")
    public Closure pow(int n) {
        if (n <= 0) throw new IllegalArgumentException("Invalid exponent: " + n);
        return new Power(this, n);
    }

    private static class Compose extends AbstractClosure {
        private Closure f, g;

        Compose(Closure f, Closure g) {
            this.f = f; this.g = g;
        }

        public int arity(ELContext elctx) {
            return g.arity(elctx);
        }

        public MethodInfo getMethodInfo(ELContext elctx) {
            return g.getMethodInfo(elctx);
        }

        public Object invoke(ELContext elctx, Closure[] args) {
            return f.call(elctx, g.invoke(elctx, args));
        }

        public boolean isProcedure() {
            return true;
        }

        public String toString() {
            return "<" + f + " o " + g + ">";
        }
    }

    private static class Power extends AbstractClosure {
        private final Closure f;
        private final int n;

        Power(Closure f, int n) {
            this.f = f; this.n = n;
        }

        public int arity(ELContext elctx) {
            return f.arity(elctx);
        }

        public MethodInfo getMethodInfo(ELContext elctx) {
            return f.getMethodInfo(elctx);
        }

        public Object invoke(ELContext elctx, Closure[] args) {
            Object res = f.invoke(elctx, args);
            for (int i = n; --i > 0; )
                res = f.call(elctx, res);
            return res;
        }

        public boolean isProcedure() {
            return true;
        }

        public String toString() {
            return "<" + f + "^" + n + ">";
        }
    }
}
