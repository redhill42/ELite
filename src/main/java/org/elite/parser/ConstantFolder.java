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

package org.elite.parser;

import org.elite.eval.TypeCoercion;

import javax.el.ELContext;
import java.util.ArrayList;
import java.util.List;

/**
 * AST constant folding pass. Recursively evaluates sub-expressions
 * whose operands are all compile-time constants, replacing them with
 * a single constant node.
 *
 * <p>This is a package-private implementation detail of {@link Parser}.
 * It extends {@link TreeTransformer}, overriding visit methods for
 * binary and unary operators to detect constant operands and evaluate
 * them eagerly via the existing {@code evaluate(null, ...)} methods.
 */
class ConstantFolder extends TreeTransformer {

    private final ELContext elctx;

    ConstantFolder(ELContext elctx) {
        this.elctx = elctx;
    }

    /**
     * Returns true if the node is a compile-time constant.
     */
    private static boolean isConstant(ELNode node) {
        return node instanceof ELNode.NUMBER
            || node instanceof ELNode.STRINGVAL
            || node instanceof ELNode.LITERAL
            || node instanceof ELNode.BOOLEANVAL
            || node instanceof ELNode.NULL;
    }

    /**
     * Extracts the Java value from a constant node.
     */
    private static Object nodeValue(ELNode node) {
        if (node instanceof ELNode.NUMBER)
            return ((ELNode.NUMBER)node).value;
        if (node instanceof ELNode.STRINGVAL)
            return ((ELNode.STRINGVAL)node).value;
        if (node instanceof ELNode.LITERAL)
            return ((ELNode.LITERAL)node).value;
        if (node instanceof ELNode.BOOLEANVAL)
            return ((ELNode.BOOLEANVAL)node).value;
        if (node instanceof ELNode.NULL)
            return null;
        throw new IllegalArgumentException("not a constant: " + node.getClass().getSimpleName());
    }

    /**
     * Wraps a Java value as the appropriate ELNode constant.
     */
    private static ELNode makeConst(int pos, Object value) {
        if (value == null)
            return new ELNode.NULL(pos);
        if (value instanceof Number)
            return new ELNode.NUMBER(pos, (Number)value);
        if (value instanceof String)
            return new ELNode.STRINGVAL(pos, (String)value);
        if (value instanceof Boolean)
            return new ELNode.BOOLEANVAL(pos, (Boolean)value);
        throw new IllegalArgumentException("not a constant: " + value.getClass().getSimpleName());
    }

    private static ELNode newInstance(ELNode.Binary e, ELNode left, ELNode right) {
        if (e instanceof ELNode.ADD)      return new ELNode.ADD(e.pos, left, right);
        if (e instanceof ELNode.SUB)      return new ELNode.SUB(e.pos, left, right);
        if (e instanceof ELNode.MUL)      return new ELNode.MUL(e.pos, left, right);
        if (e instanceof ELNode.IDIV)     return new ELNode.IDIV(e.pos, left, right);
        if (e instanceof ELNode.DIV)      return new ELNode.DIV(e.pos, left, right);
        if (e instanceof ELNode.REM)      return new ELNode.REM(e.pos, left, right);
        if (e instanceof ELNode.POW)      return new ELNode.POW(e.pos, left, right);
        if (e instanceof ELNode.BITAND)   return new ELNode.BITAND(e.pos, left, right);
        if (e instanceof ELNode.BITOR)    return new ELNode.BITOR(e.pos, left, right);
        if (e instanceof ELNode.XOR)      return new ELNode.XOR(e.pos, left, right);
        if (e instanceof ELNode.SHL)      return new ELNode.SHL(e.pos, left, right);
        if (e instanceof ELNode.SHR)      return new ELNode.SHR(e.pos, left, right);
        if (e instanceof ELNode.USHR)     return new ELNode.USHR(e.pos, left, right);
        if (e instanceof ELNode.EQ)       return new ELNode.EQ(e.pos, left, right);
        if (e instanceof ELNode.NE)       return new ELNode.NE(e.pos, left, right);
        if (e instanceof ELNode.LT)       return new ELNode.LT(e.pos, left, right);
        if (e instanceof ELNode.LE)       return new ELNode.LE(e.pos, left, right);
        if (e instanceof ELNode.GT)       return new ELNode.GT(e.pos, left, right);
        if (e instanceof ELNode.GE)       return new ELNode.GE(e.pos, left, right);
        if (e instanceof ELNode.IDEQ)     return new ELNode.IDEQ(e.pos, left, right);
        if (e instanceof ELNode.IDNE)     return new ELNode.IDNE(e.pos, left, right);
        if (e instanceof ELNode.AND)      return new ELNode.AND(e.pos, left, right);
        if (e instanceof ELNode.OR)       return new ELNode.OR(e.pos, left, right);
        if (e instanceof ELNode.CAT)      return new ELNode.CAT(e.pos, left, right);
        throw new IllegalArgumentException("unsupported binary: " + e.getClass().getSimpleName());
    }

    // ---- Binary arithmetic ----

    private ELNode foldBinaryArithmetic(ELNode.Binary e, ELNode left, ELNode right) {
        if (isConstant(left) && isConstant(right)) {
            Object lv = nodeValue(left), rv = nodeValue(right);
            if (lv == null || rv == null)
                return e;  // don't fold null operands
            try {
                return makeConst(e.pos, e.evaluate(elctx, lv, rv));
            } catch (Exception ex) {
                // division by zero etc. — leave unfolded
            }
        }
        if (left != e.left || right != e.right)
            return newInstance(e, left, right);
        return e;
    }

    public void visit(ELNode.ADD e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.SUB e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.MUL e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.DIV e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.REM e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.POW e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }

    // ---- Bitwise ----

    public void visit(ELNode.BITAND e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.BITOR  e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.XOR    e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }

    // ---- Bitwise shift (operands are integers, result is Number) ----

    public void visit(ELNode.SHL  e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.SHR  e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.USHR e) { result = foldBinaryArithmetic(e, transform(e.left), transform(e.right)); }

    // ---- Comparison (result is Boolean) ----

    private ELNode foldBinaryComparison(ELNode.Binary e, ELNode left, ELNode right) {
        if (isConstant(left) && isConstant(right)) {
            Object lv = nodeValue(left), rv = nodeValue(right);
            try {
                return new ELNode.BOOLEANVAL(e.pos, (Boolean)e.evaluate(elctx, lv, rv));
            } catch (Exception ex) {
                return e;
            }
        }
        if (left != e.left || right != e.right)
            return newInstance(e, left, right);
        return e;
    }

    public void visit(ELNode.EQ  e)  { result = foldBinaryComparison(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.NE  e)  { result = foldBinaryComparison(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.LT  e)  { result = foldBinaryComparison(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.LE  e)  { result = foldBinaryComparison(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.GT  e)  { result = foldBinaryComparison(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.GE  e)  { result = foldBinaryComparison(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.IDEQ e) { result = foldBinaryComparison(e, transform(e.left), transform(e.right)); }
    public void visit(ELNode.IDNE e) { result = foldBinaryComparison(e, transform(e.left), transform(e.right)); }

    // ---- Logical ----

    public void visit(ELNode.AND e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        Boolean lv = null, rv = null;
        
        if (isConstant(left) && nodeValue(left) instanceof Boolean b)
            lv = b;
        if (isConstant(right) && nodeValue(right) instanceof Boolean b)
            rv = b;

        if (lv != null && rv != null) {
            result = new ELNode.BOOLEANVAL(e.pos, lv && rv);
        } else if (lv != null) {
            // true  && x -> x
            // false && x -> false
            result = lv ? right : left;
        } else if (rv != null && rv) {
            // x && true -> x
            // x && false cannot fold because x may have side effects
            result = left;
        } else if (e.left == left && e.right == right) {
            result = e;
        } else {
            result = new ELNode.AND(e.pos, left, right);
        }
    }

    public void visit(ELNode.OR e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        Boolean lv = null, rv = null;

        if (isConstant(left) && (nodeValue(left) instanceof Boolean b))
            lv = b;
        if (isConstant(right) && (nodeValue(right) instanceof Boolean b))
            rv = b;

        if (lv != null && rv != null) {
            result = new ELNode.BOOLEANVAL(e.pos, lv || rv);
        } else if (lv != null) {
            // true  || x -> true
            // false || x -> x
            result = lv ? left : right;
        } else if (rv != null && !rv) {
            // x || false -> x
            // x || true cannot fold because x may have side effect
            result = left;
        } else if (e.left == left && e.right == right) {
            result = e;
        } else {
            result = new ELNode.AND(e.pos, left, right);
        }
    }

    // ---- String concatenation ----

    public void visit(ELNode.CAT e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);

        if (isConstant(left) && isConstant(right)) {
            String s = String.valueOf(nodeValue(left)) + nodeValue(right);
            result = new ELNode.STRINGVAL(e.pos, s);
        } else if (e.left == left && e.right == right) {
            result = e;
        } else {
            result = new ELNode.CAT(e.pos, left, right);
        }
    }

    public void visit(ELNode.Composite e) {
        ELNode[] elems = new ELNode[e.elems.length];
        boolean changed = false;
        boolean hasConst = false;

        for (int i = 0; i < elems.length; i++) {
            elems[i] = transform(e.elems[i]);
            if (elems[i] != e.elems[i])
                changed = true;
            if (isConstant(elems[i]))
                hasConst = true;
        }

        if (hasConst && elems.length > 1) {
            StringBuilder sb = new StringBuilder();
            List<ELNode> components = new ArrayList<>();
            for (ELNode elem : elems) {
                if (isConstant(elem)) {
                    sb.append(TypeCoercion.coerceToString(nodeValue(elem)));
                } else {
                    if (!sb.isEmpty()) {
                        components.add(new ELNode.STRINGVAL(e.pos, sb.toString()));
                        sb.setLength(0);
                    }
                    components.add(elem);
                }
            }
            if (!sb.isEmpty())
                components.add(new ELNode.STRINGVAL(e.pos, sb.toString()));
            result = new ELNode.Composite(e.pos, components.toArray(new ELNode[0]));
        } else if (!changed) {
            result = e;
        } else {
            result = new ELNode.Composite(e.pos, elems);
        }
    }

    // ---- Unary arithmetic ----

    public void visit(ELNode.NEG e) {
        ELNode right = transform(e.right);
        if (isConstant(right)) {
            Object rv = nodeValue(right);
            if (rv != null) {
                try {
                    result = makeConst(e.pos, e.evaluate(elctx, rv));
                    return;
                } catch (Exception ex) {
                    // fallthrough
                }
            }
        }
        
        if (right == e.right) {
            result = e;
        } else {
            result = new ELNode.NEG(e.pos, right);
        }
    }

    public void visit(ELNode.POS e) {
        ELNode right = transform(e.right);
        if (isConstant(right)) {
            Object rv = nodeValue(right);
            if (rv != null) {
                try {
                    result = makeConst(e.pos, e.evaluate(elctx, rv));
                    return;
                } catch (Exception ex) {
                    // fallthrough
                }
            }
        }
        
        if (right == e.right) {
            result = e;
        } else {
            result = new ELNode.POS(e.pos, right);
        }
    }

    public void visit(ELNode.BITNOT e) {
        ELNode right = transform(e.right);
        if (isConstant(right)) {
            Object rv = nodeValue(right);
            if (rv instanceof Number) {
                try {
                    result = makeConst(e.pos, e.evaluate(elctx, rv));
                    return;
                } catch (Exception ex) {
                    // fallthrough
                }
            }
        }
        
        if (right == e.right) {
            result = e;
        } else {
            result = new ELNode.BITNOT(e.pos, right);
        }
    }

    public void visit(ELNode.NOT e) {
        ELNode right = transform(e.right);
        if (isConstant(right) && nodeValue(right) instanceof Boolean b) {
            result = new ELNode.BOOLEANVAL(e.pos, !b);
            return;
        }

        if (right instanceof ELNode.IN n) {
            result = new ELNode.IN(e.pos, n.left, n.right, !n.negative);
        } else if (right instanceof ELNode.INSTANCEOF n) {
            result = new ELNode.INSTANCEOF(e.pos, n.right, n.type, !n.negative);
        } else if (right instanceof ELNode.EQ n) {
            result = new ELNode.NE(e.pos, n.left, n.right);
        } else if (right instanceof ELNode.NE n) {
            result = new ELNode.EQ(e.pos, n.left, n.right);
        } else if (right instanceof ELNode.IDEQ n) {
            result = new ELNode.IDNE(e.pos, n.left, n.right);
        } else if (right instanceof ELNode.IDNE n) {
            result = new ELNode.IDEQ(e.pos, n.left, n.right);
        } else if (right instanceof ELNode.LT n) {
            result = new ELNode.GE(e.pos, n.left, n.right);
        } else if (right instanceof ELNode.LE n) {
            result = new ELNode.GT(e.pos, n.left, n.right);
        } else if (right instanceof ELNode.GT n) {
            result = new ELNode.LE(e.pos, n.left, n.right);
        } else if (right instanceof ELNode.GE n) {
            result = new ELNode.LT(e.pos, n.left, n.right);
        } else if (right != e.right) {
            result = new ELNode.NOT(e.pos, right);
        } else {
            result = e;
        }
    }

    // ---- Parenthesized expression: penetrate through ----

    public void visit(ELNode.EXPR e) {
        result = transform(e.right);
    }

    // ---- Conditional: fold only when both branches are pure constants ----
    // Folding "if (true) { define x = 2 }" at compile time loses block scoping
    // semantics. Only fold when both branches are constant values (no scoping
    // concerns), and the condition is also constant so we can select.

    public void visit(ELNode.COND e) {
        ELNode cond  = transform(e.cond);
        ELNode left  = transform(e.left);
        ELNode right = transform(e.right);

        if (isConstant(left) && isConstant(right) && isConstant(cond)) {
            if (nodeValue(cond) instanceof Boolean c) {
                result = c ? left : right;
                return;
            }
        }

        if (cond == e.cond && left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.COND(e.pos, cond, left, right);
    }

    // ---- Coalesce: fold when left is non-null constant ----

    public void visit(ELNode.COALESCE e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);

        if (isConstant(left) && nodeValue(left) != null) {
            result = left;  // non-null constant → skip right
        } else if (isConstant(right) && nodeValue(right) == null) {
            result = left;  // x ?? null -> x
        } else if (left != e.left || right != e.right) {
            result = new ELNode.COALESCE(e.pos, left, right);
        } else {
            result = e;
        }
    }

    // --- Match pattern should not be transformed ---
    
    public void visit(ELNode.CASE e) {
        ELNode[] guards = transform(e.guards);
        ELNode[] bodies = transform(e.bodies);
        if (guards == e.guards && bodies == e.bodies)
            result = e;
        else
            result = new ELNode.CASE(e.pos, e.patterns, guards, bodies);
    }

    public void visit(ELNode.LET e) {
        // e.left is a pattern, so don't transform.
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.LET(e.pos, e.left, right);
    }
}
