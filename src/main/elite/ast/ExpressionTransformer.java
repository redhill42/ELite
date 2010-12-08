/*
 * $Id: ExpressionTransformer.java,v 1.7 2009/05/11 07:42:33 danielyuan Exp $
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

package elite.ast;

import org.operamasks.el.parser.ELNode;

final class ExpressionTransformer extends ELNode.Visitor
{
    protected Expression result;

    public Expression transform(ELNode exp) {
        if (exp == null) {
            return null;
        } else {
            exp.accept(this);
            Expression result = this.result;
            this.result = null;
            result.node = exp;
            return result;
        }
    }

    public Expression[] transform(ELNode[] args) {
        if (args == null) {
            return null;
        } else {
            Expression[] result = new Expression[args.length];
            for (int i = 0; i < args.length; i++)
                result[i] = transform(args[i]);
            return result;
        }
    }

    public void visit(ELNode.DEFINE e) {
        result = new DeclarationExpression(e.id, transform(e.expr));
    }
    
    public void visit(ELNode.IDENT e) {
        result = new IdentifierExpression(e.id);
    }

    public void visit(ELNode.ACCESS e) {
        result = new MemberExpression(transform(e.right), transform(e.index));
    }

    public void visit(ELNode.APPLY e) {
        result = new ApplyExpression(transform(e.right), transform(e.args));
    }

    public void visit(ELNode.NEW e) {
        result = new NewExpression(e.base, transform(e.args));
    }

    public void visit(ELNode.LAMBDA e) {
        String[] params = new String[e.vars.length];
        for (int i = 0; i < params.length; i++)
            params[i] = e.vars[i].id;
        result = new LambdaExpression(e.name, params, transform(e.body));
    }

    public void visit(ELNode.COMPOUND e) {
        result = new CompoundExpression(transform(e.exps));
    }

    public void visit(ELNode.ASSIGN e) {
        result = new BinaryExpression(ExpressionType.ASSIGN,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.COND e) {
        result = new ConditionalExpression(transform(e.cond),
                                           transform(e.left),
                                           transform(e.right));
    }

    public void visit(ELNode.COALESCE e) {
        result = new BinaryExpression(ExpressionType.COALESCE,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.SAFEREF e) {
        result = new BinaryExpression(ExpressionType.SAFEREF,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.OR e) {
        result = new BinaryExpression(ExpressionType.OR,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.AND e) {
        result = new BinaryExpression(ExpressionType.AND,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.BITOR e) {
        result = new BinaryExpression(ExpressionType.BITWISE_OR,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.BITAND e) {
        result = new BinaryExpression(ExpressionType.BITWISE_AND,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.XOR e) {
        result = new BinaryExpression(ExpressionType.XOR,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.SHL e) {
        result = new BinaryExpression(ExpressionType.LEFT_SHIFT,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.SHR e) {
        result = new BinaryExpression(ExpressionType.RIGHT_SHIFT,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.USHR e) {
        result = new BinaryExpression(ExpressionType.UNSIGNED_RIGHT_SHIFT,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.EQ e) {
        result = new BinaryExpression(ExpressionType.EQUAL,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.NE e) {
        result = new BinaryExpression(ExpressionType.NOT_EQUAL,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.IDEQ e) {
        result = new BinaryExpression(ExpressionType.EQUAL, // FIXME
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.IDNE e) {
        result = new BinaryExpression(ExpressionType.NOT_EQUAL, // FIXME
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.LT e) {
        result = new BinaryExpression(ExpressionType.LESS_THAN,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.LE e) {
        result = new BinaryExpression(ExpressionType.LESS_THAN_OR_EQUAL,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.GT e) {
        result = new BinaryExpression(ExpressionType.GREATER_THAN,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.GE e) {
        result = new BinaryExpression(ExpressionType.GREATER_THAN_OR_EQUAL,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.IN e) {
        result = new BinaryExpression(ExpressionType.IN, 
                                      transform(e.left),
                                      transform(e.right));
        if (e.negative) {
            result = new UnaryExpression(ExpressionType.NOT, result);
        }
    }

    public void visit(ELNode.INSTANCEOF e) {
        result = new BinaryExpression(ExpressionType.INSTANCEOF,
                                      transform(e.right),
                                      Expression.CONST(e.type));
        if (e.negative) {
            result = new UnaryExpression(ExpressionType.NOT, result);
        }
    }

    public void visit(ELNode.CAT e) {
        result = new BinaryExpression(ExpressionType.CAT,
                                      transform(e.left),
                                      transform(e.right));
    }
    
    public void visit(ELNode.ADD e) {
        result = new BinaryExpression(ExpressionType.ADD,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.SUB e) {
        result = new BinaryExpression(ExpressionType.SUBTRACT,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.MUL e) {
        result = new BinaryExpression(ExpressionType.MULTIPLY,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.DIV e) {
        if (e instanceof ELNode.IDIV) {
            result = new BinaryExpression(ExpressionType.DIVIDE, // FIXME
                                          transform(e.left),
                                          transform(e.right));
        } else {
            result = new BinaryExpression(ExpressionType.DIVIDE,
                                          transform(e.left),
                                          transform(e.right));
        }
    }

    public void visit(ELNode.REM e) {
        result = new BinaryExpression(ExpressionType.REMAINDER,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.POW e) {
        result = new BinaryExpression(ExpressionType.POWER,
                                      transform(e.left),
                                      transform(e.right));
    }

    public void visit(ELNode.BITNOT e) {
        result = new UnaryExpression(ExpressionType.BITWISE_NOT, transform(e.right));
    }

    public void visit(ELNode.POS e) {
        result = new UnaryExpression(ExpressionType.UNARY_PLUS, transform(e.right));
    }

    public void visit(ELNode.NEG e) {
        result = new UnaryExpression(ExpressionType.NEGATE, transform(e.right));
    }

    public void visit(ELNode.NOT e) {
        result = new UnaryExpression(ExpressionType.NOT, transform(e.right));
    }

    public void visit(ELNode.INC e) {
        result = new UnaryExpression(e.is_preincrement
                                        ? ExpressionType.PRE_INCREMENT
                                        : ExpressionType.POST_INCREMENT,
                                     transform(e.right));
    }

    public void visit(ELNode.DEC e) {
        result = new UnaryExpression(e.is_preincrement
                                        ? ExpressionType.PRE_DECREMENT
                                        : ExpressionType.POST_DECREMENT,
                                     transform(e.right));
    }

    public void visit(ELNode.EMPTY e) {
        result = new UnaryExpression(ExpressionType.EMPTY, transform(e.right));
    }

    public void visit(ELNode.EXPR e) {
        result = new UnaryExpression(ExpressionType.PARENTHESIS, transform(e.right));
    }

    public void visit(ELNode.BOOLEANVAL e) {
        result = new ConstantExpression(e);
    }

    public void visit(ELNode.CHARVAL e) {
        result = new ConstantExpression(e);
    }

    public void visit(ELNode.NUMBER e) {
        result = new ConstantExpression(e);
    }

    public void visit(ELNode.SYMBOL e) {
        result = new ConstantExpression(e);
    }

    public void visit(ELNode.STRINGVAL e) {
        result = new ConstantExpression(e);
    }

    public void visit(ELNode.REGEXP e) {
        result = new ConstantExpression(e);
    }

    public void visit(ELNode.LITERAL e) {
        result = new ConstantExpression(e);
    }

    public void visit(ELNode.NULL e) {
        result = new ConstantExpression(e);
    }

    public void visit(ELNode.CONS e) {
        result = new ListExpression(transform(e.head), transform(e.tail));
    }

    public void visit(ELNode.NIL e) {
        result = new ListExpression(null, null);
    }
    
    public void visit(ELNode.TUPLE e) {
        result = new TupleExpression(transform(e.elems));
    }

    public void visit(ELNode.MAP e) {
        result = new MapExpression(transform(e.keys), transform(e.values));
    }

    public void visit(ELNode.RANGE e) {
        result = new RangeExpression(transform(e.begin), transform(e.next), transform(e.end), e.exclude);
    }
    
    public void visitNode(ELNode e) {
        result = new GenericExpression(e);
    }
}
