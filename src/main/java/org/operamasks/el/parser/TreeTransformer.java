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
            ELNode[] result = new ELNode[args.length];
            for (int i = 0; i < args.length; i++)
                result[i] = transform(args[i]);
            return result;
        }
    }

    public ELNode.DEFINE[] transform(ELNode.DEFINE[] defs) {
        if (defs == null) {
            return null;
        } else {
            ELNode.DEFINE[] result = new ELNode.DEFINE[defs.length];
            for (int i = 0; i < defs.length; i++)
                result[i] = (ELNode.DEFINE)transform(defs[i]);
            return result;
        }
    }

    private ELNode.Pattern[] transform(ELNode.Pattern[] pats) {
        if (pats == null) {
            return null;
        } else {
            ELNode.Pattern[] result = new ELNode.Pattern[pats.length];
            for (int i = 0; i < pats.length; i++) 
                result[i] = (ELNode.Pattern)transform((ELNode)pats[i]);
            return result;
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
        result = new ELNode.Composite(e.pos, transform(e.elems));
    }

    public void visit(ELNode.LAMBDA e) {
        result = new ELNode.LAMBDA(e.pos, e.file,
                                   transform(e.name),
                                   transform(e.rtype),
                                   transform(e.vars),
                                   e.varargs,
                                   transform(e.body));
    }

    public void visit(ELNode.DEFINE e) {
        result = new ELNode.DEFINE(e.pos,
                                   transform(e.id),
                                   transform(e.type),
                                   (ELNode.METASET)transform(e.meta),
                                   transform(e.expr),
                                   e.immediate);
    }

    public void visit(ELNode.CLASSDEF e) {
        result = new ELNode.CLASSDEF(e.pos, e.file,
                                     transform(e.id),
                                     transform(e.base),
                                     transform(e.ifaces),
                                     transform(e.vars),
                                     transform(e.cvars),
                                     transform(e.ivars));
    }

    public void visit(ELNode.UNDEF e) {
        result = new ELNode.UNDEF(e.pos, transform(e.id));
    }

    public void visit(ELNode.IDENT e) {
        result = new ELNode.IDENT(e.pos, transform(e.id));
    }

    public void visit(ELNode.ACCESS e) {
        ELNode right = transform(e.right);
        ELNode index = e.index;

        if (index instanceof ELNode.STRINGVAL) {
            String id = ((ELNode.STRINGVAL)index).value;
            index = new ELNode.STRINGVAL(index.pos, transform(id));
        } else {
            index = transform(index);
        }

        result = new ELNode.ACCESS(e.pos, right, index);
    }

    public void visit(ELNode.APPLY e) {
        result = new ELNode.APPLY(e.pos, transform(e.right), transform(e.args), e.keys);
    }

    public void visit(ELNode.XFORM e) {
        result = new ELNode.XFORM(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.THEN e) {
        result = new ELNode.THEN(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.ASSIGN e) {
        result = new ELNode.ASSIGN(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.PREFIX e) {
        result = new ELNode.PREFIX(e.pos, transform(e.name), e.prec, transform(e.right));
    }

    public void visit(ELNode.INFIX e) {
        result = new ELNode.INFIX(e.pos, transform(e.name), e.prec, transform(e.left), transform(e.right));
    }
    
    public void visit(ELNode.COND e) {
        result = new ELNode.COND(e.pos, transform(e.cond), transform(e.left), transform(e.right));
    }

    public void visit(ELNode.COALESCE e) {
        result = new ELNode.COALESCE(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.SAFEREF e) {
        result = new ELNode.SAFEREF(e.pos, transform(e.left), transform(e.right));
    }
    
    public void visit(ELNode.OR e) {
        result = new ELNode.OR(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.AND e) {
        result = new ELNode.AND(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.BITOR e) {
        result = new ELNode.BITOR(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.BITAND e) {
        result = new ELNode.BITAND(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.XOR e) {
        result = new ELNode.XOR(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.SHL e) {
        result = new ELNode.SHL(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.SHR e) {
        result = new ELNode.SHR(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.USHR e) {
        result = new ELNode.USHR(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.EQ e) {
        result = new ELNode.EQ(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.NE e) {
        result = new ELNode.NE(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.IDEQ e) {
        result = new ELNode.IDEQ(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.IDNE e) {
        result = new ELNode.IDNE(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.LT e) {
        result = new ELNode.LT(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.LE e) {
        result = new ELNode.LE(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.GT e) {
        result = new ELNode.GT(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.GE e) {
        result = new ELNode.GE(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.INSTANCEOF e) {
        result = new ELNode.INSTANCEOF(e.pos, transform(e.right), transform(e.type), e.negative);
    }

    public void visit(ELNode.IN e) {
        result = new ELNode.IN(e.pos, transform(e.left), transform(e.right), e.negative);
    }

    public void visit(ELNode.CAT e) {
        result = new ELNode.CAT(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.ADD e) {
        result = new ELNode.ADD(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.SUB e) {
        result = new ELNode.SUB(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.MUL e) {
        result = new ELNode.MUL(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.DIV e) {
        if (e instanceof ELNode.IDIV) {
            result = new ELNode.IDIV(e.pos, transform(e.left), transform(e.right));
        } else {
            result = new ELNode.DIV(e.pos, transform(e.left), transform(e.right));
        }
    }

    public void visit(ELNode.REM e) {
        result = new ELNode.REM(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.POW e) {
        result = new ELNode.POW(e.pos, transform(e.left), transform(e.right));
    }

    public void visit(ELNode.BITNOT e) {
        result = new ELNode.BITNOT(e.pos, transform(e.right));
    }

    public void visit(ELNode.POS e) {
        result = new ELNode.POS(e.pos, transform(e.right));
    }

    public void visit(ELNode.NEG e) {
        result = new ELNode.NEG(e.pos, transform(e.right));
    }

    public void visit(ELNode.INC e) {
        result = new ELNode.INC(e.pos, transform(e.right), e.is_preincrement);
    }

    public void visit(ELNode.DEC e) {
        result = new ELNode.DEC(e.pos, transform(e.right), e.is_preincrement);
    }

    public void visit(ELNode.NOT e) {
        result = new ELNode.NOT(e.pos, transform(e.right));
    }

    public void visit(ELNode.EMPTY e) {
        result = new ELNode.EMPTY(e.pos, transform(e.right));
    }

    public void visit(ELNode.EXPR e) {
        result = new ELNode.EXPR(e.pos, transform(e.right));
    }

    public void visit(ELNode.COMPOUND e) {
        result = new ELNode.COMPOUND(e.pos, transform(e.exps));
    }

    public void visit(ELNode.WHILE e) {
        result = new ELNode.WHILE(e.pos, transform(e.cond), transform(e.body));
    }

    public void visit(ELNode.FOR e) {
        result = new ELNode.FOR(e.pos,
                                transform(e.init),
                                transform(e.cond),
                                transform(e.step),
                                transform(e.body),
                                e.local);
    }

    public void visit(ELNode.FOREACH e) {
        result = new ELNode.FOREACH(e.pos,
                                    (ELNode.DEFINE)transform(e.index),
                                    (ELNode.DEFINE)transform(e.var),
                                    transform(e.range),
                                    transform(e.body));
    }

    public void visit(ELNode.MATCH e) {
        ELNode.CASE[] alts = new ELNode.CASE[e.alts.length];
        for (int i = 0; i < alts.length; i++)
            alts[i] = (ELNode.CASE)transform(e.alts[i]);
        result = new ELNode.MATCH(e.pos, transform(e.args), alts, transform(e.deflt));
    }

    public void visit(ELNode.CASE e) {
        result = new ELNode.CASE(e.pos, transform(e.patterns), transform(e.guards), transform(e.bodies));
    }

    public void visit(ELNode.LET e) {
        result = new ELNode.LET(e.pos, transform(e.left), transform(e.right), e.force);
    }

    public void visit(ELNode.BREAK e) {
        result = e;
    }

    public void visit(ELNode.CONTINUE e) {
        result = e;
    }

    public void visit(ELNode.RETURN e) {
        result = new ELNode.RETURN(e.pos, transform(e.right));
    }

    public void visit(ELNode.THROW e) {
        result = new ELNode.THROW(e.pos, transform(e.cause));
    }

    public void visit(ELNode.TRY e) {
        result = new ELNode.TRY(e.pos,
                                transform(e.body),
                                transform(e.handlers),
                                transform(e.finalizer));
    }

    public void visit(ELNode.CATCH e) {
        result = new ELNode.CATCH(e.pos, transform(e.var), transform(e.body));
    }

    public void visit(ELNode.SYNCHRONIZED e) {
        result = new ELNode.SYNCHRONIZED(e.pos, transform(e.exp), transform(e.body));
    }

    public void visit(ELNode.ASSERT e) {
        result = new ELNode.ASSERT(e.pos, transform(e.exp), transform(e.msg));
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
        result = new ELNode.CLASS(e.pos, transform(e.name), transform(e.slots));
    }

    public void visit(ELNode.ARRAY e) {
        result = new ELNode.ARRAY(e.pos, transform(e.type), transform(e.dims), transform(e.init));
    }

    public void visit(ELNode.CONS e) {
        result = new ELNode.CONS(e.pos, transform(e.head), transform(e.tail), e.delay);
    }

    public void visit(ELNode.NIL e) {
        result = e;
    }
    
    public void visit(ELNode.TUPLE e) {
        result = new ELNode.TUPLE(e.pos, transform(e.elems));
    }

    public void visit(ELNode.MAP e) {
        result = new ELNode.MAP(e.pos, transform(e.keys), transform(e.values));
    }

    public void visit(ELNode.RANGE e) {
        result = new ELNode.RANGE(e.pos,
                                  transform(e.begin),
                                  transform(e.next),
                                  transform(e.end),
                                  e.exclude);
    }

    public void visit(ELNode.AST e) {
        result = new ELNode.AST(e.pos, transform(e.exp));
    }
    
    public void visit(ELNode.XML e) {
        result = new ELNode.XML(e.pos,
                                transform(e.tag), 
                                transform(e.keys),
                                transform(e.values),
                                transform(e.children));
    }

    public void visit(ELNode.NEW e) {
        result = new ELNode.NEW(e.pos,
                                transform(e.base),
                                transform(e.args),
                                e.keys,
                                (ELNode.MAP)transform(e.props));
    }

    public void visit(ELNode.NEWOBJ e) {
        result = new ELNode.NEWOBJ(e.pos, e.file,
                                   transform(e.base),
                                   transform(e.id), // FIXME
                                   transform(e.cvars),
                                   transform(e.ivars));
    }

    public void visit(ELNode.METADATA e) {
        result = new ELNode.METADATA(e.pos,
                                     transform(e.type),
                                     transform(e.keys),
                                     transform(e.values));
    }

    public void visit(ELNode.METASET e) {
        ELNode.METADATA[] metadata = new ELNode.METADATA[e.metadata.length];
        for (int i = 0; i < metadata.length; i++)
            metadata[i] = (ELNode.METADATA)transform(e.metadata[i]);
        result = new ELNode.METASET(e.pos, metadata, e.modifiers);
    }
}
