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

package elite.lang;

import java.io.Serializable;
import javax.el.ELContext;

import elite.lang.annotation.Expando;
import org.elite.eval.EvaluationException;
import org.elite.eval.closure.AbstractClosure;

import static org.elite.resources.Resources.*;

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
