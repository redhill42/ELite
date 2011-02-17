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

package elite.ast;

import static org.operamasks.el.parser.ELNode.*;

/**
 * Describes the node types of the nodes of an expression tree.
 */
public enum ExpressionType
{
    /** A node that represents arithmetic addition. */
    ADD("+", ADD_PREC),

    /** A node that represents a short-circuiting conditional AND operation. */
    AND("&&", AND_PREC),

    /** A node that represents a function call. */
    APPLY,

    /** A node that represents a sequential operation. */
    THEN("then", THEN_PREC),

    /** A node that represents an assignment operation. */
    ASSIGN("=", ASSIGN_PREC),

    /** A node that represents a bitwise AND operation. */
    BITWISE_AND("^&", BITAND_PREC),

    /** A node that represents a bitwise OR operation. */
    BITWISE_OR("^|", BITOR_PREC),

    /** A node that represents a bitwise NOT operation. */
    BITWISE_NOT("^!", PREFIX_PREC),

    /** A node that represents a concatenation operation. */
    CAT("~", SHIFT_PREC),
    
    /** A node that represents a null coalescing operation. */
    COALESCE("??", COALESCE_PREC),

    /** A node that represents a compound expression. */
    COMPOUND,

    /** A node that represents a conditional operation. */
    CONDITIONAL("?", COND_PREC),

    /** A node that represents an expression that has a constant value. */
    CONSTANT,

    /** A node that represents a variable declaration. */
    DECLARATION,
    
    /** A node that represents arithmetic division. */
    DIVIDE("/", MUL_PREC),

    /** A node that represents an empty test. */
    EMPTY("empty", PREFIX_PREC),
    
    /** A node that represents an equality comparison. */
    EQUAL("==", EQ_PREC),

    /** A node that represents a "greater than" comparison. */
    GREATER_THAN(">", ORD_PREC),

    /** A node that represents a "greater than or equal" comparison. */
    GREATER_THAN_OR_EQUAL(">=", ORD_PREC),

    /** A node that represents an identifier expression. */
    ID,

    /** A node that represents an IN expression. */
    IN("in", ORD_PREC),

    /** A node that represents an infix expression. */
    INFIX,

    /** A ndde that represents a type test. */
    INSTANCEOF("instanceof", ORD_PREC),

    /** A node that represents a lambda expression. */
    LAMBDA,

    /** A node that represents a bitwise left-shift operation. */
    LEFT_SHIFT("<<", SHIFT_PREC),

    /** A node that represents a "less than" comparison. */
    LESS_THAN("<", ORD_PREC),

    /** A node that represents a "less than or equal" comparison. */
    LESS_THAN_OR_EQUAL("<=", ORD_PREC),

    /** A node that represents a list expression. */
    LIST,

    /** A node that represents a map expression. */
    MAP,

    /** A node that represents reading from a field or property. */
    MEMBER,

    /** A node that represents an arithmetic remainder operation. */
    REMAINDER("%", MUL_PREC),

    /** A node that represents an arithmetic multiplication operation. */
    MULTIPLY("*", MUL_PREC),

    /** A node that represents an arithmetic negation operation. */
    NEGATE("-", PREFIX_PREC),

    /** A node that represents calling a constructor to creae a new object. */
    NEW,

    /** A node that represents creating a new array. */
    NEW_ARRAY,

    /** A node that represents a NOT operation. */
    NOT("!", PREFIX_PREC),

    /** A node that represents an inequality comparison. */
    NOT_EQUAL("!=", EQ_PREC),

    /** A node that represents short-circuiting conditional OR operation. */
    OR("||", OR_PREC),

    /** A node that represents a parenthesis expression. */
    PARENTHESIS,
    
    /** A node that represents a post-decrement operation. */
    POST_DECREMENT("--", POSTFIX_PREC),

    /** A node that represents a post-increment operation. */
    POST_INCREMENT("++", POSTFIX_PREC),

    /** A node that represents raising a number to a power. */
    POWER("^", POW_PREC),

    /** A node that represents a pre-decrement operation. */
    PRE_DECREMENT("--", PREFIX_PREC),

    /** A node that represents a pre-increment operation. */
    PRE_INCREMENT("++", PREFIX_PREC),

    /** A node that represents a prefix operation. */
    PREFIX,

    /** A node that represents range expression. */
    RANGE,
    
    /** A node that represents a bitwise right-shift operation. */
    RIGHT_SHIFT(">>", SHIFT_PREC),

    /** A node that represents safe reference operation. */
    SAFEREF("!?", COALESCE_PREC),

    /** A node that represents arithmetic subtraction operation. */
    SUBTRACT("-", ADD_PREC),

    /** A node that represents tuple expression. */
    TUPLE,
    
    /** A node that represents a unary plus operation. */
    UNARY_PLUS("+", PREFIX_PREC),

    /** A node that represents a bitwise unsigned right-shift operation. */
    UNSIGNED_RIGHT_SHIFT(">>>", SHIFT_PREC),

    /** A node that represents a bitwise XOR operation. */
    XOR("^^", XOR_PREC),

    /** A generic expression tree node. */
    GENERIC;

    private String op;
    private int prec;

    private ExpressionType() {
        this(null, NO_PREC);
    }

    private ExpressionType(String op, int prec) {
        this.op = op;
        this.prec = prec;
    }

    public String op() {
        return op;
    }

    public int prec() {
        return prec;
    }
}
