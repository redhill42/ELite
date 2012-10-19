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

import java.io.Serializable;
import javax.el.ELContext;

import elite.lang.annotation.Expando;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.closure.AbstractClosure;
import org.operamasks.el.eval.closure.LiteralClosure;

import static org.operamasks.el.resources.Resources.*;

public abstract class Continuation implements Serializable
{
    public abstract Object run(ELContext ctx, Closure c);

    public Object run(ELContext ctx) {
        return run(ctx, id);
    }

    private static final Closure id = new AbstractClosure() {
        public Object invoke(ELContext ctx, Closure[] args) {
            if (args.length != 1)
                throw new EvaluationException(ctx, _T(EL_FN_BAD_ARG_COUNT, "id", 1, args.length));
            return args[0].getValue(ctx);
        }
    };

    @Expando(name={"->>", "bind"})
    public Continuation bind(Closure k) {
        return new Bind(this, k);
    }

    @Expando(name=">>")
    public Continuation __seq__(final Continuation m) {
        return bind(new AbstractClosure() {
            public Object invoke(ELContext ctx, Closure[] args) {
                if (args.length != 1)
                    throw new EvaluationException(ctx, _T(EL_FN_BAD_ARG_COUNT, ">>", 1, args.length));
                args[0].getValue(ctx); // take side effect and ignore return value
                return m;
            }
        });
    }

    public String toString() {
        return "#<continuation>";
    }

    // yield(a) => Continuation(\f => f(a))
    static class Yield extends Continuation {
        private Closure[] args;

        Yield(Closure val) {
            this.args = new Closure[] {val};
        }

        public Object run(ELContext ctx, Closure f) {
            return f.invoke(ctx, args);
        }
    }

    // bind(k) => Continuation(\c => run(\a => run_cont(k(a), c)))
    static class Bind extends Continuation {
        private Continuation left;
        private Closure k;

        Bind(Continuation left, Closure k) {
            this.left = left;
            this.k = k;
        }

        public Object run(ELContext ctx, final Closure c) {
            return left.run(ctx, new AbstractClosure() {
                public Object invoke(ELContext ctx2, Closure[] args) {
                    if (args.length != 1) {
                        throw new EvaluationException(ctx2, _T(EL_FN_BAD_ARG_COUNT, "bind", 1, args.length));
                    }

                    Object right = k.invoke(ctx2, args);
                    if (!(right instanceof Continuation))
                        throw new EvaluationException(ctx2, _T(EL_RETURN_CONTINUATION, k));
                    return ((Continuation)right).run(ctx2, c);
                }
            });
        }
    }

    // call_cc(f) => Continuation(\c => run_cont(f(\a => Continuation(\_ => c(a))), c))
    static class CallCC extends Continuation {
        private Closure f;

        CallCC(Closure f) {
            this.f = f;
        }

        public Object run(ELContext ctx, final Closure c) {
            Object m = f.invoke(ctx, new Closure[] {
                new AbstractClosure() {
                    public Object invoke(ELContext ctx2, final Closure[] a) {
                        if (a.length != 1)
                            throw new EvaluationException(ctx2, _T(EL_FN_BAD_ARG_COUNT, "call_cc", 1, a.length));
                        return new Exit(c, a);
                    }
                }});

            if (!(m instanceof Continuation))
                throw new EvaluationException(ctx, _T(EL_RETURN_CONTINUATION, f));
            return ((Continuation)m).run(ctx, c);
        }
    }

    private static class Exit extends Continuation {
        private Closure c;
        private Closure[] a;

        Exit(Closure c, Closure[] a) {
            this.c = c;
            this.a = a;
        }

        public Object run(ELContext ctx, Closure ignored) {
            return c.invoke(ctx, a);
        }
    }
}
