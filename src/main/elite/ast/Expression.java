/*
 * $Id: Expression.java,v 1.16 2009/05/26 10:36:25 danielyuan Exp $
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

import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.ArrayList;
import javax.el.ELContext;

import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;
import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.StackTrace;
import org.operamasks.el.eval.Frame;
import org.operamasks.el.eval.VariableMapperImpl;
import elite.lang.Symbol;
import elite.lang.Range;
import elite.lang.annotation.Expando;
import static elite.lang.annotation.ExpandoScope.OPERATOR;

/**
 * Provides the base class from which the classes that represent expression
 * tree nodes are derived. It also contains static factory methods to create
 * various node types. This is an abstract class.
 */
public abstract class Expression
{
    protected ExpressionType nodeType;
    protected ELNode node;

    /**
     * Initalizes a new instance of the Expression class.
     * @param nodeType The node type of expression.
     */
    protected Expression(ExpressionType nodeType) {
        this.nodeType = nodeType;
    }

    /**
     * Initalizes a new instance of the Expression class.
     * @param nodeType The node type of expression.
     * @param node the internal node of expression.
     */
    protected Expression(ExpressionType nodeType, ELNode node) {
        this.nodeType = nodeType;
        this.node = node;
    }

    /**
     * Returns the node type of this expression.
     * @return The node type.
     */
    public ExpressionType getNodeType() {
        return nodeType;
    }

    /**
     * Convert this expression into an internal node representation.
     * @return the internal node representation.
     */
    protected abstract ELNode toInternal(int pos);

    /**
     * Returns the internal node representation of this expression.
     * @return the internal node representation.
     */
    public ELNode getNode(int pos) {
        if (node == null)
            node = toInternal(pos);
        return node;
    }

    /**
     * Evaluate this expression using global environment.
     * @param elctx the evaluation context
     * @return the result of evaluation
     */
    public Object eval(ELContext elctx) {
        return eval(new EvaluationContext(elctx));
    }

    /**
     * Evaluate this expression using the specified variable bindings.
     * @param elctx the evaluation context
     * @param bindings the variable bindings
     * @return the result of evaluation
     */
    public Object eval(ELContext elctx, Map<String,Object> bindings) {
        VariableMapperImpl vm = new VariableMapperImpl(bindings);
        return eval(new EvaluationContext(elctx, null, vm));
    }

    /**
     * Evaluate this expression using the specified environment.
     * @param env the evaluation environment
     * @return the result of evaluation
     */
    public Object eval(EvaluationContext env) {
        Frame frame = StackTrace.getFrame(env.getELContext());
        return getNode(frame.getPos()).getValue(env);
    }

    /**
     * Convert an internal node representation into an expression.
     * @param node the internal node representation.
     * @return the expression
     */
    public static Expression valueOf(ELNode node) {
        return new ExpressionTransformer().transform(node);
    }

    /**
     * Parse an expression string and return the abstract syntax tree.
     * @param text the expression string
     * @return the abstract syntax tree.
     */
    public static Expression parse(String text) {
        return valueOf(Parser.parseExpression(text));
    }

    /**
     * Creates a BinaryExpression that represents an arithmetic addition.
     * @param left the left operand
     * @param right the right operand
     * @return the BinaryExpression
     */
    public static Expression ADD(Object left, Object right) {
        return new BinaryExpression(ExpressionType.ADD, arg(left), arg(right));
    }

    /**
     * The overloaded operator to creates a BinaryExpression that represents
     * an arithmetic addition operation.
     * @param left the left operand
     * @param right the right operand
     * @return the BinaryExpression
     */
    @Expando(name="+", scope=OPERATOR)
    public static Expression __add__(Object left, Object right) {
        return ADD(left, right);
    }

    /**
     * Creates a BinaryExpression that represents a conditional AND operation.
     * @param left the left operand
     * @param right the right operand
     * @return the conditional AND expression
     */
    public static Expression AND(Object left, Object right) {
        return new BinaryExpression(ExpressionType.AND, arg(left), arg(right));
    }

    /**
     * Creates an ApplyExpression.
     * @param expression the expression to be applied
     * @param arguments the arguments to the ApplyExpression
     * @return the ApplyExpression
     */
    public static Expression APPLY(Object expression, Object... arguments) {
        return new ApplyExpression(arg(expression), args(arguments));
    }

    /**
     * Creates a BinaryExpression that represents an assignment operation.
     * @param left the left operand
     * @param right the right operand
     * @return the assignment expression
     */
    public static Expression ASSIGN(Object left, Object right) {
        return new BinaryExpression(ExpressionType.ASSIGN, arg(left), arg(right));
    }

    /**
     * Creates a BinaryExpression that represents a bitwise AND operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise AND expression
     */
    public static Expression BITWISE_AND(Object left, Object right) {
        return new BinaryExpression(ExpressionType.BITWISE_AND, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents a bitwise
     * AND operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise AND expression
     */
    @Expando(name="^&", scope=OPERATOR)
    public static Expression __bitand__(Object left, Object right) {
        return BITWISE_AND(left, right);
    }

    /**
     * Creates a BinaryExpression that represents a bitwise OR operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise AND expression
     */
    public static Expression BITWISE_OR(Object left, Object right) {
        return new BinaryExpression(ExpressionType.BITWISE_OR, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents a bitwise
     * OR operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise AND expression
     */
    @Expando(name="^|", scope=OPERATOR)
    public static Expression __bitor__(Object left, Object right) {
        return BITWISE_OR(left, right);
    }

    /**
     * Creates a BinaryExpression that represents a concatenation operation.
     * @param left the left operand
     * @param right the right operand
     * @return the BinaryExpression
     */
    public static Expression CAT(Object left, Object right) {
        return new BinaryExpression(ExpressionType.CAT, arg(left), arg(right));
    }

    /**
     * The overloaded operator to creates a BinaryExpression that represents
     * a concatenation operation.
     * @param left the left operand
     * @param right the right operand
     */
    @Expando(name="~", scope=OPERATOR)
    public static Expression __cat__(Object left, Object right) {
        return CAT(left, right);
    }

    /**
     * Creates a BinaryExpression that represents a null coalescing operation.
     * @param left the left operand
     * @param right the right operand
     * @return the null coalsecing expression
     */
    public static Expression COALESCE(Object left, Object right) {
        return new BinaryExpression(ExpressionType.COALESCE, arg(left), arg(right));
    }

    /**
     * Creates a CompoundExpression.
     * @param expressions expressions
     * @return the compound expression
     */
    public static Expression COMPOUND(Expression... expressions) {
        return new CompoundExpression(expressions);
    }

    /**
     * Appends two expression to create a CompoundExpression
     * @param expresson expression to append
     * @return the compound expression
     */
    public Expression append(Expression expression) {
        List<Expression> stmts = new ArrayList<Expression>();
        add_to_list(stmts, this);
        add_to_list(stmts, expression);
        return new CompoundExpression(stmts.toArray(new Expression[stmts.size()]));
    }

    private void add_to_list(List<Expression> stmts, Expression exp) {
        if (exp instanceof CompoundExpression) {
            for (Expression subexp : ((CompoundExpression)exp).elements) {
                add_to_list(stmts, subexp);
            }
        } else {
            stmts.add(exp);
        }
    }

    /**
     * Creates a ConditionalExpression.
     * @param test the test expression of the conditional expression
     * @param left evaluate this expression if test is true
     * @param right evaluate this expression if test is false
     * @return the conditional expression
     */
    public static Expression CONDITION(Object test, Object left, Object right) {
        return new ConditionalExpression(arg(test), arg(left), arg(right));
    }

    /**
     * Creates a ConstantExpression
     * @param value the constant value
     * @return the constant expression
     */
    public static Expression CONST(Object value) {
        return new ConstantExpression(const_node(value));
    }

    private static ELNode const_node(Object value) {
        if (value == null) {
            return new ELNode.NULL(0);
        } else if (value instanceof Boolean) {
            return new ELNode.BOOLEANVAL(0, (Boolean)value);
        } else if (value instanceof Character) {
            return new ELNode.CHARVAL(0, (Character)value);
        } else if (value instanceof Number) {
            return new ELNode.NUMBER(0, (Number)value);
        } else if (value instanceof String) {
            return new ELNode.STRINGVAL(0, (String)value);
        } else if (value instanceof Symbol) {
            return new ELNode.SYMBOL(0, (Symbol)value);
        }

        if (value instanceof Object[]) {
            Object[] tuple = (Object[])value;
            ELNode[] elems = new ELNode[tuple.length];
            for (int i = 0; i < tuple.length; i++) {
                elems[i] = const_node(tuple[i]);
            }
            return new ELNode.TUPLE(0, elems);
        }

        if (value instanceof Range) {
            Range r = (Range)value;
            ELNode begin = const_node(r.getBegin());
            ELNode next = const_node(r.getBegin() + r.getStep());
            ELNode end = r.isUnbound() ? null : const_node(r.getEnd());
            return new ELNode.RANGE(0, begin, next, end, r.isExcludeEnd());
        }

        if (value instanceof List) {
            List list = (List)value;
            ELNode seq = new ELNode.NIL(0);
            for (int i = list.size(); --i >= 0; ) {
                seq = new ELNode.CONS(0, const_node(list.get(i)), seq);
            }
            return seq;
        }

        if (value instanceof Map) {
            Map map = (Map)value;
            int size = map.size();
            ELNode[] keys = new ELNode[size];
            ELNode[] values = new ELNode[size];
            Iterator it = map.entrySet().iterator();
            for (int i = 0; it.hasNext(); i++) {
                Map.Entry e = (Map.Entry)it.next();
                keys[i] = const_node(e.getKey());
                values[i] = const_node(e.getValue());
            }
            return new ELNode.MAP(0, keys, values);
        }

        return new ELNode.CONST(0, value);
    }

    /**
     * Create a DeclarationExpression that represents a variable declaration.
     * @param name the variable name
     * @param expression the initial variable value
     * @return the DeclarationExpression
     */
    public static Expression DEFINE(Object name, Object expression) {
        return new DeclarationExpression(name(name), arg(expression));
    }

    /**
     * Create a BinaryExpression that represents an arithmetic division operation.
     * @param left the left operand
     * @param right the right operand
     * @return the division expression
     */
    public static Expression DIVIDE(Object left, Object right) {
        return new BinaryExpression(ExpressionType.DIVIDE, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents
     * an arithmetic division operation.
     * @param left the left operand
     * @param right
     * @return the division expression
     */
    @Expando(name="/", scope=OPERATOR)
    public static Expression __div__(Object left, Object right) {
        return DIVIDE(left, right);
    }

    /**
     * Creates a UnaryExpression that represents an empty test.
     * @param right the operand
     * @return the empty test expression
     */
    public static Expression EMPTY(Object right) {
        return new UnaryExpression(ExpressionType.EMPTY, arg(right));
    }

    /**
     * Creates a BinaryExpression that represents an equality comparison.
     * @param left the left operand
     * @param right the right operand
     * @return the equality expression
     */
    public static Expression EQUAL(Object left, Object right) {
        return new BinaryExpression(ExpressionType.EQUAL, arg(left), arg(right));
    }

    /**
     * Creates a BinaryExpression that represents a "greater than" comparison.
     * @param left the left operand
     * @param right the right operand
     * @return the "greater-than" expression
     */
    public static Expression GREATER_THAN(Object left, Object right) {
        return new BinaryExpression(ExpressionType.GREATER_THAN, arg(left), arg(right));
    }

    /**
     * Creates a BinaryExpression that represents a "greater than or equal" comparison.
     * @param left the left operand
     * @param right the right operand
     * @return the "greater than or equal" expression
     */
    public static Expression GREATER_THAN_OR_EQUAL(Object left, Object right) {
        return new BinaryExpression(ExpressionType.GREATER_THAN, arg(left), arg(right));
    }

    /**
     * Create an expression that represents an identifier.
     * @param name the identifier name
     * @return the identifier expression
     */
    public static Expression ID(String name) {
        return new IdentifierExpression(name);
    }

    /**
     * Create an expression that represents an identifier.
     * @param name the identifier name
     * @return the identifier expression
     */
    public static Expression ID(Symbol name) {
        return new IdentifierExpression(name.getName());
    }

    /**
     * Create a BinaryExpression that represents an IN expression.
     * @param left the left operand
     * @param right the right operand
     * @return the IN expression
     */
    public static Expression IN(Object left, Object right) {
        return new BinaryExpression(ExpressionType.IN, arg(left), arg(right));
    }

    /**
     * Create a BinaryExpression that represents a type test.
     * @param expression the operand
     * @param type the type name to test
     * @return the type test expression
     */
    public static Expression INSTANCEOF(Object expression, String type) {
        return new BinaryExpression(ExpressionType.INSTANCEOF, arg(expression), CONST(type));
    }

    /**
     * Creates a LambdaExpression.
     * @return the lambda expression.
     */
    public static Expression LAMBDA(Object name, Object params, Object body) {
        Expression lambda =  new LambdaExpression(name(name), names(params), arg(body));
        return new DeclarationExpression(name(name), lambda);
    }

    /**
     * Creates a LambdaExpression.
     * @return the lambda expression.
     */
    public static Expression LAMBDA(Object params, Object body) {
        return new LambdaExpression(null, names(params), arg(body));
    }

    /**
     * Creates a BinaryExpression that represents a bitwise left-shift operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise left-shift expression
     */
    public static Expression LEFT_SHIFT(Object left, Object right) {
        return new BinaryExpression(ExpressionType.LEFT_SHIFT, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents a bitwise
     * left-shift operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise left-shift expression
     */
    @Expando(name="<<", scope=OPERATOR)
    public static Expression __shl__(Object left, Object right) {
        return LEFT_SHIFT(left, right);
    }

    /**
     * Creates a BinaryExpression that represents a "less than" comparison.
     * @param left the left operand
     * @param right the right operand
     * @return the "less than" expression
     */
    public static Expression LESS_THAN(Object left, Object right) {
        return new BinaryExpression(ExpressionType.LESS_THAN, arg(left), arg(right));
    }

    /**
     * Creates a BinaryExpression that represents a "less than or equal" comparison.
     * @param left the left operand
     * @param right the right operand
     * @return the "less than or equal" expression
     */
    public static Expression LESS_THAN_OR_EQUAL(Object left, Object right) {
        return new BinaryExpression(ExpressionType.LESS_THAN_OR_EQUAL, arg(left), arg(right));
    }

    /**
     * Creates a ListExpression.
     * @param elements the list elements
     * @return the list expression.
     */
    public static Expression LIST(Object... elements) {
        Expression t = new ListExpression(null, null);
        for (int i = elements.length; --i >= 0; ) {
            t = new ListExpression(arg(elements[i]), t);
        }
        return t;
    }

    /**
     * Creates a ListExpression.
     * @param head the list head
     * @param tail the list tail
     * @return the list expression
     */
    public static Expression CONS(Object head, Object tail) {
        return new ListExpression(arg(head), arg(tail));
    }

    /**
     * Create a empty ListExpression.
     * @return the list expression
     */
    public static Expression NIL() {
        return new ListExpression(null, null);
    }
    
    /**
     * Creates a MapExpression.
     * @param map the map elements
     * @return the map expression.
     */
    public static Expression MAP(Map map) {
        int size = map.size();
        Expression[] keys = new Expression[size];
        Expression[] values = new Expression[size];
        Iterator it = map.entrySet().iterator();
        for (int i = 0; it.hasNext(); i++) {
            Map.Entry e = (Map.Entry)it.next();
            keys[i] = arg(e.getKey());
            values[i] = arg(e.getValue());
        }
        return new MapExpression(keys, values);
    }

    /**
     * Creates a MemberAccessExpression.
     * @param expression the expression
     * @param field the member field of the expression
     * @return the member access expression
     */
    public static Expression MEMBER(Object expression, Object field) {
        return new MemberExpression(arg(expression), arg(field));
    }

    /**
     * Creates a BinaryExpression that represents an arithmetic remainder operation.
     * @param left the left operand
     * @param right the right operand
     * @return the arithmetic remainder expression
     */
    public static Expression REMAINDER(Object left, Object right) {
        return new BinaryExpression(ExpressionType.REMAINDER, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents an arithmetic
     * remainder operation.
     * @param left the left operand
     * @param right the right operand
     * @return the arithmetic remainder expression
     */
    @Expando(name="%", scope=OPERATOR)
    public static Expression __rem__(Object left, Object right) {
        return REMAINDER(left, right);
    }

    /**
     * Creates a BinaryExpression that represents an arithmetic multiplication operation.
     * @param left the left operand
     * @param right the right operand
     * @return the arithmetic multiplication expression
     */
    public static Expression MULTIPLY(Object left, Object right) {
        return new BinaryExpression(ExpressionType.MULTIPLY, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents an arithmetic
     * multiplication operation.
     * @param left the left operand
     * @param right the right operand
     * @return the arithmetic multiplication expression
     */
    @Expando(name="*")
    public static Expression __mul__(Expression left, Object right) {
        return MULTIPLY(left, right);
    }

    /**
     * Create a UnaryExpression that represents an arithmetic negation operation.
     * @param right the operand
     * @return the negation expression
     */
    public static Expression NEGATE(Object right) {
        return new UnaryExpression(ExpressionType.NEGATE, arg(right));
    }

    /**
     * Overloaded operator to create a UnaryExpression that represents an arithmetic
     * negation operation.
     * @param right the operand
     * @return the negation expression
     */
    @Expando
    public static Expression __neg__(Object right) {
        return NEGATE(right);
    }

    /**
     * Creates a NewExpression.
     * @return the NewExpression
     */
    public static Expression NEW(Object type, Object... arguments) {
        return new NewExpression(name(type), args(arguments));
    }

    /**
     * Creates a UnaryExpression that represents a logical complement operation. 
     * @param right the operand
     * @return the logical complement expression
     */
    public static Expression NOT(Object right) {
        return new UnaryExpression(ExpressionType.NOT, arg(right));
    }

    /**
     * Creates a BinaryExpression that represents an inequality comparison.
     * @param left the left operand
     * @param right the right operand
     * @return the inequality comparison
     */
    public static Expression NOT_EQUAL(Object left, Object right) {
        return new BinaryExpression(ExpressionType.NOT_EQUAL, arg(left), arg(right));
    }

    /**
     * Creates a BinaryExpression that represents a conditional OR operation
     * that evaluates the second operand only if it has to.
     * @param left the left operand
     * @param right the right operand
     * @return the conditional OR expression
     */
    public static Expression OR(Object left, Object right) {
        return new BinaryExpression(ExpressionType.OR, arg(left), arg(right));
    }

    /**
     * Create a UnaryExpression that represents a parenthesis expression.
     * @param expression the expression
     * @return the parenthesis expression
     */
    public static Expression PARENTHESIS(Object expression) {
        return new UnaryExpression(ExpressionType.PARENTHESIS, arg(expression));
    }

    /**
     * Create a UnaryExpression that represents a post-decrement opertation.
     * @param left the operand
     * @return the post-decrement expression
     */
    public static Expression POST_DECREMENT(Object left) {
        return new UnaryExpression(ExpressionType.POST_DECREMENT, arg(left));
    }

    /**
     * Creates a UnaryExpression that represents a post-increment operation.
     * @param left the operand
     * @return the post-increment expression
     */
    public static Expression POST_INCREMENT(Object left) {
        return new UnaryExpression(ExpressionType.POST_INCREMENT, arg(left));
    }

    /**
     * Create a BinaryExpression that represents raising a number to a power.
     * @param left the left operand
     * @param right the right operand
     * @return the power expression
     */
    public static Expression POWER(Object left, Object right) {
        return new BinaryExpression(ExpressionType.POWER, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents raising
     * a number to a power.
     * @param left the left operand
     * @param right the right operand
     * @return the power expression
     */
    @Expando(name="^", scope=OPERATOR)
    public static Expression __pow__(Object left, Object right) {
        return POWER(left, right);
    }

    /**
     * Create a UnaryExpression that represents a pre-decrement opertation.
     * @param right the operand
     * @return the pre-decrement expression
     */
    public static Expression PRE_DECREMENT(Object right) {
        return new UnaryExpression(ExpressionType.PRE_DECREMENT, arg(right));
    }

    /**
     * Creates a UnaryExpression that represents a pre-increment operation.
     * @param right the operand
     * @return the pre-increment expression
     */
    public static Expression PRE_INCREMENT(Object right) {
        return new UnaryExpression(ExpressionType.PRE_INCREMENT, arg(right));
    }

    /**
     * Creates a RangeExpression.
     * @param begin the begin value in the range
     * @param next the next value in the range
     * @param end the end value in the range
     */
    public static Expression RANGE(Object begin, Object next, Object end) {
        return new RangeExpression(arg(begin),
                                   next==null ? null : arg(next),
                                   end==null ? null : arg(end));
    }
    
    /**
     * Creates a RangeExpression.
     * @param begin the begin value in the range
     * @param end the end value in the range
     */
    public static Expression RANGE(Object begin, Object end) {
        return new RangeExpression(arg(begin), null, arg(end));
    }

    /**
     * Creates a BinaryExpression that represents a bitwise right-shift operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise right-shift expression
     */
    public static Expression RIGHT_SHIFT(Object left, Object right) {
        return new BinaryExpression(ExpressionType.RIGHT_SHIFT, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents a bitwise
     * right-shift operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise right-shift expression
     */
    @Expando(name=">>", scope=OPERATOR)
    public static Expression __shr__(Object left, Object right) {
        return RIGHT_SHIFT(left, right);
    }

    /**
     * Creates a BinaryExpression that represents a safe reference operation.
     * @param left the left operand
     * @param right the right operand
     * @return the safe reference expression
     */
    public static Expression SAFEREF(Object left, Object right) {
        return new BinaryExpression(ExpressionType.SAFEREF, arg(left), arg(right));
    }

    /**
     * Creates a BinaryExpression that represents an arithmetic subtraction
     * operation.
     * @param left the left operand
     * @param right the right operand
     * @return the arithmetic subtraction expression
     */
    public static Expression SUBTRACT(Object left, Object right) {
        return new BinaryExpression(ExpressionType.SUBTRACT, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents
     * an arithmetic subtraction operation.
     * @param left the left operand
     * @param right the right operand
     * @return the arithmetic subtraction expression
     */
    @Expando(name="-", scope=OPERATOR)
    public static Expression __sub__(Object left, Object right) {
        return SUBTRACT(left, right);
    }

    /**
     * Creates a TupleExpression.
     * @params elements the tuple elements
     * @return the tuple expression.
     */
    public static Expression TUPLE(Object... elements) {
        return new TupleExpression(args(elements));
    }

    /**
     * Creates a UnaryExpression that represents a unary plus operation.
     * @param right the operand
     * @return the unary plus expression
     */
    public static Expression UNARY_PLUS(Object right) {
        return new UnaryExpression(ExpressionType.UNARY_PLUS, arg(right));
    }

    /**
     * Overloaded operator to create a UnaryExpression that represents
     * an unary plus operation.
     * @param right the operand
     * @return the unary plus expression
     */
    @Expando
    public static Expression __pos__(Object right) {
        return UNARY_PLUS(right);
    }

    /**
     * Create a BinaryExpression that represents a bitwise unsigned right-shift
     * operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise unsigned right-shift expression
     */
    public static Expression UNSIGNED_RIGHT_SHIFT(Object left, Object right) {
        return new BinaryExpression(ExpressionType.UNSIGNED_RIGHT_SHIFT, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents a bitwise
     * unsigned right-shift operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise unsigned right-shift expression
     */
    @Expando(name=">>>", scope=OPERATOR)
    public static Expression __ushr__(Object left, Object right) {
        return UNSIGNED_RIGHT_SHIFT(left, right);
    }

    /**
     * Creates a BinaryExpression that represents a bitwise XOR operation.
     * @param left the left operand
     * @param right the right operand
     * @return the bitwise XOR expression
     */
    public static Expression XOR(Object left, Object right) {
        return new BinaryExpression(ExpressionType.XOR, arg(left), arg(right));
    }

    /**
     * Overloaded operator to create a BinaryExpression that represents
     * an bitwise xor operation.
     * @param left the left operand
     * @param right the right operand
     * @return the arithmetic subtraction expression
     */
    @Expando(name="^^", scope=OPERATOR)
    public static Expression __xor__(Object left, Object right) {
        return XOR(left, right);
    }

    private static Expression arg(Object arg) {
        if (arg instanceof Expression) {
            return (Expression)arg;
        } else if (arg instanceof Symbol) {
            return ID((Symbol)arg);
        } else {
            return CONST(arg);
        }
    }

    private static Expression[] args(Object... args) {
        if (args instanceof Expression[]) {
            return (Expression[])args;
        } else if (args.length == 1 && args[0] instanceof Expression[]) {
            return (Expression[])args[0];
        } else {
            Expression[] exps = new Expression[args.length];
            for (int i = 0; i < args.length; i++) {
                exps[i] = arg(args[i]);
            }
            return exps;
        }
    }

    private static String name(Object param) {
        if (param instanceof String) {
            return (String)param;
        } else if (param instanceof Symbol) {
            return ((Symbol)param).getName();
        } else {
            throw new IllegalArgumentException(""+param);
        }
    }

    private static String[] names(Object param) {
        if (param instanceof String) {
            return new String[] {(String)param};
        } else if (param instanceof Symbol) {
            return new String[] {((Symbol)param).getName()};
        } else if (param instanceof Object[]) {
            Object[] array = (Object[])param;
            String[] names = new String[array.length];
            for (int i = 0; i < names.length; i++)
                names[i] = name(array[i]);
            return names;
        } else if (param instanceof List) {
            List list = (List)param;
            String[] names = new String[list.size()];
            for (int i = 0; i < names.length; i++)
                names[i] = name(list.get(i));
            return names;
        } else {
            throw new IllegalArgumentException(""+param);
        }
    }
}
