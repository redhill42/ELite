/*
 * $Id: DefaultVisitor.java,v 1.24 2009/05/24 10:46:45 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
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

public class DefaultVisitor extends ELNode.Visitor
{
    public void scan(ELNode e) {
        if (e != null) {
            e.accept(this);
        }
    }

    public void scan(ELNode[] args) {
        if (args != null) {
            for (ELNode e : args) {
                scan(e);
            }
        }
    }

    public void visit(ELNode.Composite e) {
        scan(e.elems);
    }

    public void visit(ELNode.LAMBDA e) {
        scan(e.vars);
        scan(e.body);
    }

    public void visit(ELNode.DEFINE e) {
        scan(e.meta);
        scan(e.expr);
    }

    public void visit(ELNode.CLASSDEF e) {
        scan(e.vars);
        scan(e.cvars);
        scan(e.ivars);
    }
    
    public void visit(ELNode.UNDEF e) {}

    public void visit(ELNode.IDENT e) {}

    public void visit(ELNode.ACCESS e) {
        scan(e.right);
        scan(e.index);
    }

    public void visit(ELNode.APPLY e) {
        scan(e.right);
        scan(e.args);
    }

    public void visit(ELNode.COND e) {
        scan(e.cond);
        scan(e.left);
        scan(e.right);
    }

    public void visitUnary(ELNode.Unary e) {
        scan(e.right);
    }

    public void visitBinary(ELNode.Binary e) {
        scan(e.left);
        scan(e.right);
    }

    public void visit(ELNode.XFORM e)    { visitBinary(e); }
    public void visit(ELNode.PREFIX e)   { visitUnary(e);  }
    public void visit(ELNode.INFIX e)    { visitBinary(e); }
    public void visit(ELNode.ASSIGN e)   { visitBinary(e); }
    public void visit(ELNode.COALESCE e) { visitBinary(e); }
    public void visit(ELNode.SAFEREF e)  { visitBinary(e); }
    public void visit(ELNode.OR e)       { visitBinary(e); }
    public void visit(ELNode.AND e)      { visitBinary(e); }
    public void visit(ELNode.BITOR e)    { visitBinary(e); }
    public void visit(ELNode.BITAND e)   { visitBinary(e); }
    public void visit(ELNode.XOR e)      { visitBinary(e); }
    public void visit(ELNode.SHL e)      { visitBinary(e); }
    public void visit(ELNode.SHR e)      { visitBinary(e); }
    public void visit(ELNode.USHR e)     { visitBinary(e); }
    public void visit(ELNode.EQ e)       { visitBinary(e); }
    public void visit(ELNode.NE e)       { visitBinary(e); }
    public void visit(ELNode.IDEQ e)     { visitBinary(e); }
    public void visit(ELNode.IDNE e)     { visitBinary(e); }
    public void visit(ELNode.LT e)       { visitBinary(e); }
    public void visit(ELNode.LE e)       { visitBinary(e); }
    public void visit(ELNode.GT e)       { visitBinary(e); }
    public void visit(ELNode.GE e)       { visitBinary(e); }
    public void visit(ELNode.INSTANCEOF e) { visitUnary(e); }
    public void visit(ELNode.IN e)       { visitBinary(e); }
    public void visit(ELNode.CAT e)      { visitBinary(e); }
    public void visit(ELNode.ADD e)      { visitBinary(e); }
    public void visit(ELNode.SUB e)      { visitBinary(e); }
    public void visit(ELNode.MUL e)      { visitBinary(e); }
    public void visit(ELNode.DIV e)      { visitBinary(e); }
    public void visit(ELNode.REM e)      { visitBinary(e); }
    public void visit(ELNode.POW e)      { visitBinary(e); }
    public void visit(ELNode.BITNOT e)   { visitUnary(e); }
    public void visit(ELNode.POS e)      { visitUnary(e); }
    public void visit(ELNode.NEG e)      { visitUnary(e); }
    public void visit(ELNode.INC e)      { visitUnary(e); }
    public void visit(ELNode.DEC e)      { visitUnary(e); }
    public void visit(ELNode.NOT e)      { visitUnary(e); }
    public void visit(ELNode.EMPTY e)    { visitUnary(e); }
    public void visit(ELNode.EXPR e)     { visitUnary(e); }

    public void visit(ELNode.COMPOUND e) {
        scan(e.exps);
    }

    public void visit(ELNode.WHILE e) {
        scan(e.cond);
        scan(e.body);
    }

    public void visit(ELNode.FOR e) {
        scan(e.init);
        scan(e.cond);
        scan(e.step);
        scan(e.body);
    }
    
    public void visit(ELNode.FOREACH e) {
        scan(e.index);
        scan(e.var);
        scan(e.range);
        scan(e.body);
    }

    public void visit(ELNode.MATCH e) {
        scan(e.args);
        scan(e.alts);
        scan(e.deflt);
    }

    public void visit(ELNode.CASE e) {
        if (e.patterns != null) {
            for (ELNode.Pattern p : e.patterns)
                scan((ELNode)p);
        }
        scan(e.guard);
        scan(e.body);
    }

    public void visit(ELNode.LET e) {
        scan(e.left);
        scan(e.right);
    }

    public void visit(ELNode.BREAK e) {}
    public void visit(ELNode.CONTINUE e) {}

    public void visit(ELNode.RETURN e) {
        scan(e.right);
    }

    public void visit(ELNode.THROW e) {
        scan(e.cause);
    }

    public void visit(ELNode.TRY e) {
        scan(e.body);
        scan(e.handlers);
        scan(e.finalizer);
    }

    public void visit(ELNode.CATCH e) {
        scan(e.body);
    }

    public void visit(ELNode.SYNCHRONIZED e) {
        scan(e.exp);
        scan(e.body);
    }

    public void visit(ELNode.ASSERT e) {
        scan(e.exp);
        scan(e.msg);
    }

    public void visitConstant(ELNode.Constant e) {}
    public void visit(ELNode.CONST e)       { visitConstant(e); }
    public void visit(ELNode.BOOLEANVAL e)  { visitConstant(e); }
    public void visit(ELNode.CHARVAL e)     { visitConstant(e); }
    public void visit(ELNode.NUMBER e)      { visitConstant(e); }
    public void visit(ELNode.SYMBOL e)      { visitConstant(e); }
    public void visit(ELNode.STRINGVAL e)   { visitConstant(e); }
    public void visit(ELNode.REGEXP e)      { visitConstant(e); }
    public void visit(ELNode.LITERAL e)     { visitConstant(e); }
    public void visit(ELNode.NULL e)        { visitConstant(e); }
    public void visit(ELNode.NIL e)         { visitConstant(e); }
    public void visit(ELNode.CLASS e)       { visitConstant(e); }

    public void visit(ELNode.ARRAY e) {
        scan(e.dims);
        scan(e.init);
    }

    public void visit(ELNode.CONS e) {
        scan(e.head);
        scan(e.tail);
    }

    public void visit(ELNode.TUPLE e) {
        scan(e.elems);
    }
    
    public void visit(ELNode.MAP e) {
        scan(e.keys);
        scan(e.values);
    }

    public void visit(ELNode.RANGE e) {
        scan(e.begin);
        scan(e.next);
        scan(e.end);
    }

    public void visit(ELNode.AST e) {
        scan(e.exp);
    }
    
    public void visit(ELNode.XML e) {
        scan(e.tag);
        scan(e.keys);
        scan(e.values);
        scan(e.children);
    }

    public void visit(ELNode.NEW e) {
        scan(e.args);
        scan(e.props);
    }

    public void visit(ELNode.NEWOBJ e) {
        scan(e.vars);
        scan(e.cvars);
        scan(e.ivars);
    }

    public void visit(ELNode.METADATA e) {
        scan(e.values);
    }

    public void visit(ELNode.METASET e) {
        scan(e.metadata);
    }

    public void visitNode(ELNode e) {
        assert false;
    }
}
