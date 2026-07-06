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

public class TreeTransformer extends ELNode.Visitor
{
    protected ELNode result;

    public ELNode transform(ELNode arg) {
        if (arg == null) {
            return null;
        } else {
            arg.accept(this);
            ELNode result = this.result;
            this.result = null;
            return result;
        }
    }

    public ELNode[] transform(ELNode[] args) {
        if (args == null) {
            return null;
        } else {
            boolean changed = false;
            ELNode[] result = new ELNode[args.length];
            for (int i = 0; i < args.length; i++) {
                result[i] = transform(args[i]);
                if (result[i] != args[i])
                    changed = true;
            }
            return changed ? result : args;
        }
    }

    public ELNode.DEFINE[] transform(ELNode.DEFINE[] defs) {
        if (defs == null) {
            return null;
        } else {
            boolean changed = false;
            ELNode.DEFINE[] result = new ELNode.DEFINE[defs.length];
            for (int i = 0; i < defs.length; i++) {
                result[i] = (ELNode.DEFINE)transform(defs[i]);
                if (result[i] != defs[i])
                    changed = true;
            }
            return changed ? result : defs;
        }
    }

    private ELNode.Pattern[] transform(ELNode.Pattern[] pats) {
        if (pats == null) {
            return null;
        } else {
            boolean changed = false;
            ELNode.Pattern[] result = new ELNode.Pattern[pats.length];
            for (int i = 0; i < pats.length; i++) {
                result[i] = (ELNode.Pattern)transform((ELNode)pats[i]);
                if (result[i] != pats[i])
                    changed = true;
            }
            return changed ? result : pats;
        }
    }

    public String transform(String id) {
        return id;
    }

    public String[] transform(String[] ids) {
        if (ids == null) {
            return null;
        } else {
            String[] new_ids = null;
            for (int i = 0; i < ids.length; i++) {
                String new_id = transform(ids[i]);
                if (new_id != ids[i]) {
                    if (new_ids == null)
                        new_ids = ids.clone();
                    new_ids[i] = new_id;
                }
            }
            return new_ids != null ? new_ids : ids;
        }
    }

    public void visit(ELNode.Composite e) {
        ELNode[] elems = transform(e.elems);
        if (elems == e.elems )
            result = e;
        else
            result = new ELNode.Composite(e.pos, transform(e.elems));
    }

    public void visit(ELNode.LAMBDA e) {
        String name = transform(e.name);
        String rtype = transform(e.rtype);
        ELNode.DEFINE[] vars = transform(e.vars);
        ELNode body = transform(e.body);
        if (name == e.name && rtype == e.rtype && vars == e.vars && body == e.body)
            result = e;
        else
            result = new ELNode.LAMBDA(e.pos, e.file, name, rtype, vars, e.varargs, body);
    }

    public void visit(ELNode.DEFINE e) {
        String id = transform(e.id);
        String type = transform(e.type);
        ELNode.METASET meta = (ELNode.METASET)transform(e.meta);
        ELNode expr = transform(e.expr);
        if (id == e.id && type == e.type && meta == e.meta && expr == e.expr)
            result = e;
        else
            result = new ELNode.DEFINE(e.pos, id, type, meta, expr);
    }

    public void visit(ELNode.CLASSDEF e) {
        String id = transform(e.id);
        String base = transform(e.base);
        String[] ifaces = transform(e.ifaces);
        ELNode.DEFINE[] vars = transform(e.vars);
        ELNode.DEFINE[] cvars = transform(e.cvars);
        ELNode.DEFINE[] ivars = transform(e.ivars);
        if (id == e.id && base == e.base && ifaces == e.ifaces &&
            vars == e.vars && cvars == e.cvars && ivars == e.ivars)
            result = e;
        else
            result = new ELNode.CLASSDEF(e.pos, e.file, id, base, ifaces, vars, cvars, ivars);
    }

    public void visit(ELNode.UNDEF e) {
        String id = transform(e.id);
        if (id == e.id)
            result = e;
        else
            result = new ELNode.UNDEF(e.pos, id);
    }

    public void visit(ELNode.IDENT e) {
        String id = transform(e.id);
        if (id == e.id)
            result = e;
        else
            result = new ELNode.IDENT(e.pos, id);
    }

    public void visit(ELNode.ACCESS e) {
        ELNode right = transform(e.right);
        ELNode index = e.index;

        if (index instanceof ELNode.STRINGVAL) {
            String id = ((ELNode.STRINGVAL)index).value;
            String new_id = transform(id);
            if (id != new_id)
                index = new ELNode.STRINGVAL(index.pos, transform(id));
        } else {
            index = transform(index);
        }

        if (right == e.right && index == e.index)
            result = e;
        else
            result = new ELNode.ACCESS(e.pos, right, index);
    }

    public void visit(ELNode.APPLY e) {
        ELNode right = transform(e.right);
        ELNode[] args = transform(e.args);
        if (right == e.right && args == e.args)
            result = e;
        else
            result = new ELNode.APPLY(e.pos, right, args, e.keys);
    }

    public void visit(ELNode.ASSIGN e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.ASSIGN(e.pos, left, right);
    }

    public void visit(ELNode.ASSIGNOP e) {
        e.binary.left = transform(e.left);
        e.binary.right = transform(e.right);
        if (e.binary.left == e.left && e.binary.right == e.right)
            result = e;
        else
            result = new ELNode.ASSIGNOP(e.pos, e.binary);
    }

    public void visit(ELNode.PREFIX e) {
        String name = transform(e.name);
        ELNode right = transform(e.right);
        if (name == e.name && right == e.right)
            result = e;
        else
            result = new ELNode.PREFIX(e.pos, name, e.prec, right);
    }

    public void visit(ELNode.INFIX e) {
        String name = transform(e.name);
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (name == e.name && left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.INFIX(e.pos, e.name, e.prec, left, right);
    }
    
    public void visit(ELNode.COND e) {
        ELNode cond = transform(e.cond);
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (cond == e.cond && left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.COND(e.pos, cond, left, right);
    }

    public void visit(ELNode.COALESCE e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.COALESCE(e.pos, left, right);
    }

    public void visit(ELNode.OR e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.OR(e.pos, left, right);
    }

    public void visit(ELNode.AND e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.AND(e.pos, left, right);
    }

    public void visit(ELNode.BITOR e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.BITOR(e.pos, left, right);
    }

    public void visit(ELNode.BITAND e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.BITAND(e.pos, left, right);
    }

    public void visit(ELNode.XOR e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.XOR(e.pos, left, right);
    }

    public void visit(ELNode.SHL e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.SHL(e.pos, left, right);
    }

    public void visit(ELNode.SHR e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.SHR(e.pos, left, right);
    }

    public void visit(ELNode.USHR e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.USHR(e.pos, left, right);
    }

    public void visit(ELNode.EQ e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.EQ(e.pos, left, right);
    }

    public void visit(ELNode.NE e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.NE(e.pos, left, right);
    }

    public void visit(ELNode.IDEQ e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.IDEQ(e.pos, left, right);
    }

    public void visit(ELNode.IDNE e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.IDNE(e.pos, left, right);
    }

    public void visit(ELNode.LT e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.LT(e.pos, left, right);
    }

    public void visit(ELNode.LE e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.LE(e.pos, left, right);
    }

    public void visit(ELNode.GT e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.GT(e.pos, left, right);
    }

    public void visit(ELNode.GE e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.GE(e.pos, left, right);
    }

    public void visit(ELNode.INSTANCEOF e) {
        ELNode right = transform(e.right);
        String type = transform(e.type);
        if (right == e.right && type == e.type)
            result = e;
        else
            result = new ELNode.INSTANCEOF(e.pos, right, type, e.negative);
    }

    public void visit(ELNode.IN e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.IN(e.pos, left, right, e.negative);
    }

    public void visit(ELNode.CAT e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.CAT(e.pos, left, right);
    }

    public void visit(ELNode.ADD e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.ADD(e.pos, left, right);
    }

    public void visit(ELNode.SUB e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.SUB(e.pos, left, right);
    }

    public void visit(ELNode.MUL e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.MUL(e.pos, left, right);
    }

    public void visit(ELNode.DIV e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else if (e instanceof ELNode.IDIV)
            result = new ELNode.IDIV(e.pos, left, right);
        else
            result = new ELNode.DIV(e.pos, left, right);
    }

    public void visit(ELNode.REM e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.REM(e.pos, left, right);
    }

    public void visit(ELNode.POW e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.POW(e.pos, left, right);
    }

    public void visit(ELNode.BITNOT e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.BITNOT(e.pos, right);
    }

    public void visit(ELNode.POS e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.POS(e.pos, right);
    }

    public void visit(ELNode.NEG e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.NEG(e.pos, right);
    }

    public void visit(ELNode.INC e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.INC(e.pos, right, e.is_preincrement);
    }

    public void visit(ELNode.DEC e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.DEC(e.pos, right, e.is_preincrement);
    }

    public void visit(ELNode.NOT e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.NOT(e.pos, right);
    }

    public void visit(ELNode.EMPTY e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.EMPTY(e.pos, right);
    }

    public void visit(ELNode.EXPR e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.EXPR(e.pos, right);
    }

    public void visit(ELNode.COMPOUND e) {
        ELNode[] exps = transform(e.exps);
        if (exps == e.exps)
            result = e;
        else
            result = new ELNode.COMPOUND(e.pos, exps);
    }

    public void visit(ELNode.WHILE e) {
        ELNode cond = transform(e.cond);
        ELNode body = transform(e.body);
        if (cond == e.cond && body == e.body)
            result = e;
        else
            result = new ELNode.WHILE(e.pos, cond, body);
    }

    public void visit(ELNode.REPEAT e) {
        ELNode cond = transform(e.cond);
        ELNode body = transform(e.body);
        if (cond == e.cond && body == e.body)
            result = e;
        else
            result = new ELNode.REPEAT(e.pos, cond, body);
    }

    public void visit(ELNode.FOR e) {
        ELNode[] init = transform(e.init);
        ELNode cond = transform(e.cond);
        ELNode[] step = transform(e.step);
        ELNode body = transform(e.body);
        if (init == e.init && cond == e.cond && step == e.step && body == e.body)
            result = e;
        else
            result = new ELNode.FOR(e.pos, init, cond, step, body, e.local);
    }

    public void visit(ELNode.FOREACH e) {
        ELNode.DEFINE index = (ELNode.DEFINE)transform(e.index);
        ELNode.DEFINE var = (ELNode.DEFINE)transform(e.var);
        ELNode range = transform(e.range);
        ELNode body = transform(e.body);
        if (index == e.index && var == e.var && range == e.range && body == e.body)
            result = e;
        else
            result = new ELNode.FOREACH(e.pos, index, var, range, body);
    }

    public void visit(ELNode.MATCH e) {
        boolean changed = false;
        ELNode.CASE[] alts = new ELNode.CASE[e.alts.length];
        for (int i = 0; i < alts.length; i++) {
            alts[i] = (ELNode.CASE)transform(e.alts[i]);
            if (alts[i] != e.alts[i])
                changed = true;
        }

        ELNode[] args = transform(e.args);
        ELNode deflt = transform(e.deflt);
        if (!changed && args == e.args && deflt == e.deflt)
            result = e;
        else
            result = new ELNode.MATCH(e.pos, args, alts, e.deflt);
    }

    public void visit(ELNode.CASE e) {
        ELNode.Pattern[] patterns = transform(e.patterns);
        ELNode[] guards = transform(e.guards);
        ELNode[] bodies = transform(e.bodies);
        if (patterns == e.patterns && guards == e.guards && bodies == e.bodies)
            result = e;
        else
            result = new ELNode.CASE(e.pos, patterns, guards, bodies);
    }

    public void visit(ELNode.LET e) {
        ELNode left = transform(e.left);
        ELNode right = transform(e.right);
        if (left == e.left && right == e.right)
            result = e;
        else
            result = new ELNode.LET(e.pos, left, right);
    }

    public void visit(ELNode.BREAK e) {
        result = e;
    }

    public void visit(ELNode.CONTINUE e) {
        result = e;
    }

    public void visit(ELNode.RETURN e) {
        ELNode right = transform(e.right);
        if (right == e.right)
            result = e;
        else
            result = new ELNode.RETURN(e.pos, right);
    }

    public void visit(ELNode.THROW e) {
        ELNode cause = transform(e.cause);
        if (cause == e.cause)
            result = e;
        else
            result = new ELNode.THROW(e.pos, cause);
    }

    public void visit(ELNode.TRY e) {
        ELNode body = transform(e.body);
        ELNode[] handlers = transform(e.handlers);
        ELNode finalizer = transform(e.finalizer);
        if (body == e.body && handlers == e.handlers && finalizer == e.finalizer)
            result = e;
        else
            result = new ELNode.TRY(e.pos, body, e.types, handlers, finalizer);
    }

    public void visit(ELNode.CATCH e) {
        String var = transform(e.var);
        ELNode body = transform(e.body);
        if (var == e.var && body == e.body)
            result = e;
        else
            result = new ELNode.CATCH(e.pos, var, body);
    }

    public void visit(ELNode.SYNCHRONIZED e) {
        ELNode exp = transform(e.exp);
        ELNode body = transform(e.body);
        if (exp == e.exp && body == e.body)
            result = e;
        else
            result = new ELNode.SYNCHRONIZED(e.pos, exp, body);
    }

    public void visit(ELNode.ASSERT e) {
        ELNode exp = transform(e.exp);
        ELNode msg = transform(e.msg);
        if (exp == e.exp && msg == e.msg)
            result = e;
        else
            result = new ELNode.ASSERT(e.pos, exp, msg);
    }

    public void visit(ELNode.CONST e) {
        result = e;
    }
    
    public void visit(ELNode.BOOLEANVAL e) {
        result = e;
    }

    public void visit(ELNode.CHARVAL e) {
        result = e;
    }

    public void visit(ELNode.NUMBER e) {
        result = e;
    }

    public void visit(ELNode.SYMBOL e) {
        result = e;
    }

    public void visit(ELNode.STRINGVAL e) {
        result = e;
    }

    public void visit(ELNode.REGEXP e) {
        result = e;
    }

    public void visit(ELNode.LITERAL e) {
        result = e;
    }

    public void visit(ELNode.NULL e) {
        result = e;
    }

    public void visit(ELNode.CLASS e) {
        String name = transform(e.name);
        String[] slots = transform(e.slots);
        if (name == e.name && slots == e.slots)
            result = e;
        else
            result = new ELNode.CLASS(e.pos, name, slots);
    }

    public void visit(ELNode.ARRAY e) {
        String type = transform(e.type);
        ELNode[] dims = transform(e.dims);
        ELNode[] init = transform(e.init);
        if (type == e.type && dims == e.dims && init == e.init)
            result = e;
        else
            result = new ELNode.ARRAY(e.pos, type, dims, init);
    }

    public void visit(ELNode.CONS e) {
        ELNode head = transform(e.head);
        ELNode tail = transform(e.tail);
        if (head == e.head && tail == e.tail)
            result = e;
        else
            result = new ELNode.CONS(e.pos, head, tail, e.delay);
    }

    public void visit(ELNode.NIL e) {
        result = e;
    }
    
    public void visit(ELNode.TUPLE e) {
        ELNode[] elems = transform(e.elems);
        if (elems == e.elems)
            result = e;
        else
            result = new ELNode.TUPLE(e.pos, elems);
    }

    public void visit(ELNode.MAP e) {
        ELNode[] keys = transform(e.keys);
        ELNode[] values = transform(e.values);
        if (keys == e.keys && values == e.values)
            result = e;
        else
            result = new ELNode.MAP(e.pos, keys, values);
    }

    public void visit(ELNode.RANGE e) {
        ELNode begin = transform(e.begin);
        ELNode next = transform(e.next);
        ELNode end = transform(e.end);
        if (begin == e.begin && next == e.next && end == e.end)
            result = e;
        else
            result = new ELNode.RANGE(e.pos, begin, next, end, e.exclude);
    }

    public void visit(ELNode.AST e) {
        ELNode exp = transform(e.exp);
        if (exp == e.exp)
            result = e;
        else
            result = new ELNode.AST(e.pos, exp);
    }
    
    public void visit(ELNode.XML e) {
        ELNode tag = transform(e.tag);
        ELNode[] keys = transform(e.keys);
        ELNode[] values = transform(e.values);
        ELNode[] children = transform(e.children);
        if (tag == e.tag && keys == e.keys && values == e.values && children == e.children)
            result = e;
        else
            result = new ELNode.XML(e.pos, tag, keys, values, children);
    }

    public void visit(ELNode.NEW e) {
        ELNode base = transform(e.base);
        ELNode[] args = transform(e.args);
        ELNode.MAP props = (ELNode.MAP)transform(e.props);
        if (base == e.base && args == e.args && props == e.props)
            result = e;
        else
            result = new ELNode.NEW(e.pos, base, args, e.keys, props);
    }

    public void visit(ELNode.NEWOBJ e) {
        String base = transform(e.base);
        String id = transform(e.id);
        ELNode.DEFINE[] cvars = transform(e.cvars);
        ELNode.DEFINE[] ivars = transform(e.ivars);
        if (base == e.base && id == e.id && cvars == e.cvars && ivars == e.ivars)
            result = e;
        else
            result = new ELNode.NEWOBJ(e.pos, e.file, base, id, cvars, ivars);
    }

    public void visit(ELNode.METADATA e) {
        String type = transform(e.type);
        String[] keys = transform(e.keys);
        ELNode[] values = transform(e.values);
        if (type == e.type && keys == e.keys && values == e.values)
            result = e;
        else
            result = new ELNode.METADATA(e.pos, type, keys, values);
    }

    public void visit(ELNode.METASET e) {
        boolean changed = false;
        ELNode.METADATA[] metadata = new ELNode.METADATA[e.metadata.length];
        for (int i = 0; i < metadata.length; i++) {
            metadata[i] = (ELNode.METADATA)transform(e.metadata[i]);
            if (metadata[i] != e.metadata[i])
                changed = true;
        }
        if (!changed)
            result = e;
        else
            result = new ELNode.METASET(e.pos, metadata, e.modifiers);
    }
}
