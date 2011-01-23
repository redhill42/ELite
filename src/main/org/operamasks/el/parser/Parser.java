/*
 * $Id: Parser.java,v 1.95 2009/06/14 05:06:32 danielyuan Exp $
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

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Modifier;
import java.io.File;
import java.io.Reader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import elite.lang.Decimal;
import elite.lang.Rational;
import elite.lang.Symbol;
import elite.ast.Expression;
import javax.el.ELContext;
import javax.el.ELException;

import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.ELProgram;
import org.operamasks.el.eval.closure.ClassDefinition;
import org.operamasks.el.resolver.ClassResolver;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.util.SimpleCache;
import static org.operamasks.el.parser.Token.*;
import static org.operamasks.el.resources.Resources.*;

/**
 * This class is used to parse EL expressions. The result is a parse tree.
 * This class implements an operator precedence parser.
 */
public class Parser extends Scanner
{
    private ResourceResolver resolver = null;
    private ParseContext env = new ParseContext();

    private static final String SCRIPT_PATH = "META-INF/script/elite/";
    private static final String SCRIPT_EXT = ".xel";

    public Parser(String text) {
        super(text);
    }

    public void setResourceResolver(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    // Generate a unique class tag.
    private static final AtomicInteger clstagid = new AtomicInteger();
    private static String clstag() {
        return "class$" + clstagid.incrementAndGet();
    }

    // Generate a unique temporary variable name.
    private static final AtomicInteger tempid = new AtomicInteger();
    private static String tempvar() {
        return "*" + tempid.incrementAndGet() + "*";
    }

    void open_scope() {
        env.push();
    }

    void close_scope() {
        Map<String,ELNode.DEFINE> vars = env.pop();
        for (ELNode.DEFINE var : vars.values()) {
            restoreOperator(var.operator);
        }
    }

    ELNode.DEFINE new_symbol(int p, String id, String type, ELNode.METASET meta) {
        ELNode.DEFINE var = new ELNode.DEFINE(p, id, type, meta);
        env.put(id, var);
        return var;
    }

    ELNode.DEFINE add_symbol(ELNode.DEFINE var) {
        env.put(var.id, var);
        return var;
    }

    void discard_symbol(String id) {
        ELNode.DEFINE var = env.remove(id);
        if (var != null) {
            restoreOperator(var.operator);
        }
    }

    /**
     * Expect lvalue in an assignment expression.
     */
    private void expect_lvalue(ELNode e) {
        while (e instanceof ELNode.EXPR) {
            e = ((ELNode.EXPR)e).right;
        }

        switch (e.op) {
        case IDENT: case ACCESS: case ASSIGN:
            break; // Ok
        case CONS:
            expect_lvalue(((ELNode.CONS)e).head);
            expect_lvalue(((ELNode.CONS)e).tail);
            break;
        case NIL:
            break;
        case TUPLE:
            for (ELNode a : ((ELNode.TUPLE)e).elems)
                expect_lvalue(a);
            break;
        default:
            throw parseError(e.pos, _T(EL_READONLY_EXPRESSION));
        }
    }

    /**
     * Utility method.
     */

    private static final String[] EMPTY_KEYS = new String[0];
    private static final ELNode[] EMPTY_EXPS = new ELNode[0];
    private static final ELNode.DEFINE[] EMPTY_DEFS = new ELNode.DEFINE[0];

    private static ELNode[] to_a(List<ELNode> exps) {
        return exps.toArray(new ELNode[exps.size()]);
    }

    private static ELNode.DEFINE[] to_a(List<ELNode.DEFINE> defs) {
        return defs.toArray(new ELNode.DEFINE[defs.size()]);
    }

    private static ELNode.Pattern[] to_a(List<ELNode.Pattern> pats) {
        return pats.toArray(new ELNode.Pattern[pats.size()]);
    }

    private static String[] to_a(List<String> ids) {
        return ids.toArray(new String[ids.size()]);
    }

    /**
     * Parse a primary expression.
     */
    ELNode parseTerm() {
        switch (token) {
        case CHARVAL: {
            char v = charValue;
            return new ELNode.CHARVAL(scan(), v);
        }

        case NUMBER: {
            Number v = numberValue;
            return new ELNode.NUMBER(scan(), v);
        }

        case STRINGVAL: {
            String v = stringValue;
            if (charValue == '"') {
                return parseEmbedExpression(scan(), v);
            } else {
                return new ELNode.STRINGVAL(scan(), v);
            }
        }

        case DIV: {
            // consult scanner to parse a regular expression.
            ELNode e = new ELNode.REGEXP(pos, scanRegexp());
            scan();
            return e;
        }

        case VOID: // void is an identifier in an expression, e.g. void(0)
            return new ELNode.IDENT(scan(), "void");

        case IDENT: case ATSIGN: {
            if (token == IDENT) {
                Operator op = getOperator(idValue);
                if (op != null && op.token == PREFIX) {
                    return new ELNode.PREFIX(scan(), op.name, op.token2, parseTerm());
                }
            }

            int p = pos;
            ELNode e = new ELNode.IDENT(p, scanQAName());
            expect(IDENT);
            if (token == LBRACE) {
                e = new ELNode.APPLY(p, e, parseBlock(scan()));
            }
            return e;
        }

        case COLON: {
            if (Character.isJavaIdentifierStart((char)ch)) {
                scan();
                String id = idValue;
                return new ELNode.SYMBOL(scan(), Symbol.valueOf(id));
            } else if (ch == '\'' || ch == '"') {
                scan();
                String id = stringValue;
                return new ELNode.SYMBOL(scan(), Symbol.valueOf(id));
            } else {
                throw parseError(_T(EL_MISSING_TERM));
            }
        }

        case TRUE:
            return new ELNode.BOOLEANVAL(scan(), true);
        case FALSE:
            return new ELNode.BOOLEANVAL(scan(), false);
        case NULL:
            return new ELNode.NULL(scan());

        case NEW:
            return parseNewExpression(scan());

        case LAMBDA:
            return parseLambdaAbstraction(scan());

        case LBRACE: {
            int p = scan();
            ELNode e = parseLambdaExpressionOpt(p);
            if (e != null) {
                expect(RBRACE);
                return e;
            } else {
                return parseMapExpression(p);
            }
        }

        case LBRACKET:
            return parseListOrRangeExpression(scan());

        case PREFIX: {
            Operator op = operator;
            return new ELNode.PREFIX(scan(), op.name, op.token2, parseTerm());
        }

        case ADD: {
            int p = scan();
            if (token == NUMBER) {
                Number v = numberValue;
                return new ELNode.NUMBER(scan(), v);
            } else {
                return new ELNode.POS(p, parseTerm());
            }
        }

        case SUB: {
            int p = scan();
            if (token == NUMBER) {
                Number v = numberValue;
                if (v instanceof BigInteger) {
                    v = ((BigInteger)v).negate();
                } else if (v instanceof BigDecimal) {
                    v = ((BigDecimal)v).negate();
                } else if (v instanceof Decimal) {
                    v = ((Decimal)v).negate();
                } else if (v instanceof Rational) {
                    v = ((Rational)v).negate();
                } else if (v instanceof Double) {
                    v = -v.doubleValue();
                } else if (v instanceof Long) {
                    v = -v.longValue();
                } else {
                    v = -v.intValue();
                }
                return new ELNode.NUMBER(scan(), v);
            } else {
                return new ELNode.NEG(p, parseTerm());
            }
        }

        case INC: {
            int p = scan();
            ELNode e = parseTerm();
            expect_lvalue(e);
            return new ELNode.INC(p, e, true);
        }

        case DEC: {
            int p = scan();
            ELNode e = parseTerm();
            expect_lvalue(e);
            return new ELNode.DEC(p, e, true);
        }

        case NOT:
            return new ELNode.NOT(scan(), parseTerm());
        case BITNOT:
            return new ELNode.BITNOT(scan(), parseTerm());
        case EMPTY:
            return new ELNode.EMPTY(scan(), parseTerm());

        case LET:
            return parseLetExpression(scan(), scan(MUL));

        case CASE:
            return parseMatchExpression(scan());

        case CATCH:
            return parseCatchExpression(scan());

        case LPAREN: {
            int p = scan();
            if (token == RPAREN) {
                scan();
                return new ELNode.TUPLE(p, EMPTY_EXPS);
            }

            switch (token) {
            case CAT: case ADD: case SUB: case MUL: case DIV: case REM: case POW:
            case BITNOT: case BITOR: case BITAND: case XOR: case SHL: case SHR: case USHR:
            case COALESCE: case LT: case LE: case GT: case GE: case EQ: case NE:
            case IDEQ: case IDNE: case NOT: case AND: case OR: case EMPTY:
            case PREFIX: case INFIX:
                String id = (idValue != null) ? idValue : operator.name;
                mark(); scan();
                if (scan(RPAREN)) {
                    return new ELNode.IDENT(p, id);
                } else {
                    reset();
                }
                break;

            case IDENT:
                Operator op = getOperator(idValue);
                if (op != null) {
                    mark(); scan();
                    if (scan(RPAREN)) {
                        return new ELNode.IDENT(p, op.name);
                    } else {
                        reset();
                    }
                }
                break;
            }

            ELNode e = parseSyntaxExpression();
            if (token == COMMA) {
                List<ELNode> elems = new ArrayList<ELNode>();
                elems.add(e);
                while (scan(COMMA) && token != RPAREN) {
                    elems.add(parseSyntaxExpression());
                }
                expect(RPAREN);
                return new ELNode.TUPLE(p, to_a(elems));
            } else {
                expect(RPAREN);
                return new ELNode.EXPR(p, e);
            }
        }

        case BAR: {
            int p = scan();
            ELNode e = parseSyntaxExpression();
            expect(BAR);
            return new ELNode.AST(p, e);
        }
            
        case LT:
            return XMLParser.parse(this);

        case GRAMMAR: {
            int p = scan();
            Grammar g = new GrammarParser(this).parse();
            return new ELNode.CONST(p, new ParserCombinator(g));
        }
        
        case EOI:
            throw incomplete(_T(EL_MISSING_TERM));
        default:
            throw parseError(_T(EL_MISSING_TERM));
        }
    }

    /**
     * Parse an expression in which the colon is a terminal token.
     */
    private ELNode parseNonColonExpression(boolean allowSyntaxRules) {
        if (token == IDENT) {
            mark();
            String id = idValue;
            int p = scan();
            if (token == COLON) {
                return new ELNode.IDENT(p, id);
            } else {
                reset();
            }
        }
        return allowSyntaxRules ? parseSyntaxExpression() : parseExpression();
    }

    /**
     * Parse an identifier with namespace prefix.
     */
    String scanQName() {
        String id = idValue;
        if (id == null)
            return null;
        // ambiguity with ?: expression, so do lookahead
        if (ch == ':' && Character.isJavaIdentifierStart((char)lookahead(0))) {
            scan(); // skip prefix identifier
            assert token == COLON;
            scan(); // skip colon
            assert idValue != null;
            id += ":" + idValue;
            token = IDENT;
        }
        return id;
    }

    /**
     * Parse an identifier with @-prefix.
     */
    String scanQAName() {
        if (idValue != null) {
            return scanQName();
        } else if (token == ATSIGN) {
            scan();
            String id = "@";
            while (token == ATSIGN) {
                id += "@";
                scan();
            }
            if (idValue != null) {
                id += scanQName();
                token = IDENT;
                return id;
            }
        }
        return null;
    }

    /**
     * Given a left-hand term, parse an operator and right-hand term.
     */
    ELNode parseBinaryExpression(ELNode e) {
        if (e == null) {
            return null;
        }

        if (token == IDENT) {
            Operator op = getOperator(idValue);
            if (op != null) {
                operator = op;
                token = op.token;
            }
        }

        switch (token) {
          case LBRACKET: {
            int p = scan();
            ELNode index = parseExpression();
            if (token == RANGE) {
                index = parseRangeExpression(p, index, null);
            } else if (token == COMMA) {
                scan();
                index = parseRangeExpression(p, index, parseExpression());
            } else {
                expect(RBRACKET);
            }

            ELNode e2 = new ELNode.ACCESS(p, e, index);
            if (token == LBRACE) {
                e2 = new ELNode.APPLY(p, e2.order(), parseBlock(scan()));
            }
            return e2;
          }

          case FIELD: {
            int p = scan();

            if (token == LPAREN) {
                // a.(b) ===> a.curry(b)
                return new ELNode.ACCESS(p, e, new ELNode.STRINGVAL(p, "curry"));
            }

            String id;
            switch (token) {
            case CLASSDEF: case NEW: case EMPTY:
                id = scanQName();
                scan();
                break;
            case ATSIGN:
                id = scanQAName();
                expect(IDENT);
                break;
            default:
                id = scanQName();
                expect(IDENT);
                break;
            }

            e = new ELNode.ACCESS(p, e, new ELNode.STRINGVAL(p, id));
            if (token == LBRACE) {
                e = new ELNode.APPLY(p, e.order(), parseBlock(scan()));
            }
            return e;
          }

          case LPAREN:
            return parseApplyExpression(scan(), e);

          case XFORM:
            return new ELNode.XFORM(scan(), e, parseTerm());

          case INC:
            return new ELNode.INC(scan(), e, false);
          case DEC:
            return new ELNode.DEC(scan(), e, false);

          case INSTANCEOF:
            if (idValue.equals("is")) {
                int p = scan();
                if (token == NOT && idValue != null) { // "is not"
                    return new ELNode.INSTANCEOF(scan(), e, parseClassLiteral(false), true);
                } else {
                    return new ELNode.INSTANCEOF(p, e, parseClassLiteral(false), false);
                }
            } else {
                return new ELNode.INSTANCEOF(scan(), e, parseClassLiteral(false), false);
            }

          case IN:
            return new ELNode.IN(scan(), e, parseTerm(), false);

          case NOT: {
              if (idValue == null) {
                  return null; // only 'not' is allowed
              }

              int p = scan();
              if (scan(INSTANCEOF)) {
                  return new ELNode.INSTANCEOF(p, e, parseClassLiteral(false), true);
              } else if (scan(IN)) {
                  return new ELNode.IN(p, e, parseTerm(), true);
              } else if (token == EOI) {
                  throw incomplete(_T(EL_TOKEN_EXPECTED, "in, instanceof", "<EOF>"));
              } else {
                  throw parseError(_T(EL_TOKEN_EXPECTED, "in, instanceof", token_value()));
              }
          }

          case ASSIGN:
            expect_lvalue(e);
            return new ELNode.ASSIGN(scan(), e, parseTerm());

          case ASSIGNOP: {
              expect_lvalue(e);
              int op = operator.token2;
              int p = scan();
              ELNode e2 = parseTerm();
              switch (op) {
                case CAT:    return new ELNode.ASSIGNOP(p, new ELNode.CAT(p, e, e2));
                case ADD:    return new ELNode.ASSIGNOP(p, new ELNode.ADD(p, e, e2));
                case SUB:    return new ELNode.ASSIGNOP(p, new ELNode.SUB(p, e, e2));
                case MUL:    return new ELNode.ASSIGNOP(p, new ELNode.MUL(p, e, e2));
                case DIV:    return new ELNode.ASSIGNOP(p, new ELNode.DIV(p, e, e2));
                case REM:    return new ELNode.ASSIGNOP(p, new ELNode.REM(p, e, e2));
                case POW:    return new ELNode.ASSIGNOP(p, new ELNode.POW(p, e, e2));
                case BITOR:  return new ELNode.ASSIGNOP(p, new ELNode.BITOR(p, e, e2));
                case BITAND: return new ELNode.ASSIGNOP(p, new ELNode.BITAND(p, e, e2));
                case XOR:    return new ELNode.ASSIGNOP(p, new ELNode.XOR(p, e, e2));
                case SHL:    return new ELNode.ASSIGNOP(p, new ELNode.SHL(p, e, e2));
                case SHR:    return new ELNode.ASSIGNOP(p, new ELNode.SHR(p, e, e2));
                case USHR:   return new ELNode.ASSIGNOP(p, new ELNode.USHR(p, e, e2));
                case COALESCE: return new ELNode.ASSIGNOP(p, new ELNode.COALESCE(p, e, e2));
                default:     throw new AssertionError();
              }
          }

          case INFIX: {
            Operator op = operator;
            return new ELNode.INFIX(scan(), op.name, op.token2, e, parseTerm());
          }

          case CAT:
            return new ELNode.CAT(scan(), e, parseTerm());
          case ADD:
            return new ELNode.ADD(scan(), e, parseTerm());
          case SUB:
            return new ELNode.SUB(scan(), e, parseTerm());
          case MUL:
            return new ELNode.MUL(scan(), e, parseTerm());
          case DIV:
            return new ELNode.DIV(scan(), e, parseTerm());
          case IDIV:
            return new ELNode.IDIV(scan(), e, parseTerm());
          case REM:
            return new ELNode.REM(scan(), e, parseTerm());
          case POW:
            return new ELNode.POW(scan(), e, parseTerm());
          case LT:
            return new ELNode.LT(scan(), e, parseTerm());
          case LE:
            return new ELNode.LE(scan(), e, parseTerm());
          case GT:
            return new ELNode.GT(scan(), e, parseTerm());
          case GE:
            return new ELNode.GE(scan(), e, parseTerm());
          case EQ:
            return new ELNode.EQ(scan(), e, parseTerm());
          case NE:
            return new ELNode.NE(scan(), e, parseTerm());
          case IDEQ:
            return new ELNode.IDEQ(scan(), e, parseTerm());
          case IDNE:
            return new ELNode.IDNE(scan(), e, parseTerm());
          case AND:
            return new ELNode.AND(scan(), e, parseTerm());
          case OR:
            return new ELNode.OR(scan(), e, parseTerm());
          case BITAND:
            return new ELNode.BITAND(scan(), e, parseTerm());
          case BITOR:
            return new ELNode.BITOR(scan(), e, parseTerm());
          case XOR:
            return new ELNode.XOR(scan(), e, parseTerm());
          case SHL:
            return new ELNode.SHL(scan(), e, parseTerm());
          case SHR:
            return new ELNode.SHR(scan(), e, parseTerm());
          case USHR:
            return new ELNode.USHR(scan(), e, parseTerm());
          case COALESCE:
            return new ELNode.COALESCE(scan(), e, parseTerm());
          case SAFEREF:
            return new ELNode.SAFEREF(scan(), e, parseTerm());
          case QUESTIONMARK: {
            int p = scan();
            ELNode second = parseNonColonExpression(false);
            expect(COLON);
            ELNode third = parseExpression();
            return new ELNode.COND(p, e, second, third);
          }

          default:
            return null; // mark end of binary expressions
        }
    }

    /**
     * Parse an expression.
     */
    ELNode parseExpression() {
        for (ELNode e = parseTerm(); e != null; e = e.order()) {
            ELNode more = parseBinaryExpression(e);
            if (more == null)
                return e;
            e = more;
        }
        return null;
    }

    /**
     * 分析一个使用了自定义语法的表达式.
     *
     * 自定义语法只能出现在不会引起混淆的地方, 例如以下位置:
     *   1) 顶层表达式语句
     *   2) 括号表达式
     *   3) 使用括号的语句, 如if, while等
     *   4) 函数调用参数表
     *   5) 元组,列表或数组的元素以及关联表的值
     */
    ELNode parseSyntaxExpression() {
        ELNode e = matchPrefixGrammar();
        if (e != null) {
            return e;
        }

        e = parseExpression();

        while (true) {
            ELNode more = matchInfixGrammar(e);
            if (more == null)
                break;
            e = more;
        }

        return e;
    }

    /**
     * Parse an expression statement. A statement separator is either a semicolon
     * or a significant newline.
     */
    ELNode parseExpressionStatement() {
        ELNode e;

        // match prefix pattern
        e = matchPrefixGrammar();
        if (e != null) {
            return e;
        }

        // parse binary expression, stop on significant newline
        loop:
        for (e = parseTerm();;) {
            switch (token) {
            case LBRACE: case LBRACKET: case LPAREN: case INC: case DEC:
                if (token != SEMI && scanLayout()) {
                    // FIXME: need warning logger
                    System.err.println((filename!=null ? (filename+":") : "") +
                                       Position.line(pos) + ": " +
                                       "warning: insert semicolon for unambiguous");
                    return e;
                }
                // fallthrough
            default:
                ELNode more = parseBinaryExpression(e);
                if (more == null)
                    break loop;
                e = more.order();
            }
        }

        // match infix pattern
        while (!scanLayout()) {
            ELNode more = matchInfixGrammar(e);
            if (more == null)
                break;
            e = more;
        }

        return e;
    }

    /**
     * Parse an apply expression.
     */
    private ELNode parseApplyExpression(int p, ELNode e) {
        String[] keys;
        ELNode[] args;

        if (token == RPAREN) {
            scan();
            keys = null;
            if (token == LBRACE) {
                args = new ELNode[] { parseBlock(scan()) };
            } else {
                args = EMPTY_EXPS;
            }
        } else {
            List<ELNode> vlst = new ArrayList<ELNode>();
            List<String> klst = new ArrayList<String>();
            parseNamedArguments(vlst, klst);
            expect(RPAREN);
            if (token == LBRACE) {
                vlst.add(parseBlock(scan()));
                klst.add(null);
            }
            args = to_a(vlst);
            keys = check_keys(klst);
        }

        return new ELNode.APPLY(p, e, args, keys);
    }

    /**
     * Parse a new expression.
     */
    private ELNode parseNewExpression(int p) {
        String cls = parseClassLiteral(false);

        if (token == LPAREN) {
            String[] keys;
            ELNode[] args;

            scan();
            if (token == RPAREN) {
                scan();
                keys = null;
                args = EMPTY_EXPS;
            } else {
                List<ELNode> vlst = new ArrayList<ELNode>();
                List<String> klst = new ArrayList<String>();
                parseNamedArguments(vlst, klst);
                expect(RPAREN);
                keys = check_keys(klst);
                args = to_a(vlst);
            }

            if (token != LBRACE) {
                // new object(args);
                return new ELNode.NEW(p, cls, args, keys, null);
            } else if (looksLikeMap()) {
                // new object(args) { props... }
                ELNode.MAP props = parseMapExpression(scan());
                return new ELNode.NEW(p, cls, args, keys, props);
            } else {
                // new object(args) { class definition... }
                scan();
                open_scope();
                String clstag = clstag();
                ELNode.DEFINE[] body = parseClassBody();
                close_scope();

                if (args.length != 0) {
                    // generate default constructor
                    //   new mytag(args) {
                    //     mytag() {
                    //       super(args);
                    //     }
                    //     class definition...
                    //   }
                    ELNode.DEFINE initproc = new ELNode.DEFINE(
                        p, clstag, null, null,
                        new ELNode.LAMBDA(p, filename, EMPTY_DEFS,
                            new ELNode.APPLY(p, new ELNode.IDENT(p, "super"), args, keys)
                        ),
                        true);

                    ELNode.DEFINE[] tmp = new ELNode.DEFINE[body.length+1];
                    tmp[0] = initproc;
                    System.arraycopy(body, 0, tmp, 1, body.length);
                    body = tmp;
                }

                return new ELNode.NEWOBJ(p, filename, cls, clstag, body);
            }
        }

        // new class[dim?]{init}?
        if (token == LBRACKET) {
            ELNode[] dims = parseArrayDimensions();
            ELNode[] init = null;
            if ((dims == null || dims.length == 1) && token == LBRACE) { // FIXME
                scan();
                init = parseArrayInitializer();
            }
            return new ELNode.ARRAY(p, cls, dims, init);
        }

        if (token != LBRACE) {
            // new class;
            return new ELNode.NEW(p, cls, EMPTY_EXPS, null, null);
        } else if (looksLikeMap()) {
            // new class { props }
            ELNode.MAP props = parseMapExpression(scan());
            return new ELNode.NEW(p, cls, EMPTY_EXPS, null, props);
        } else {
            // new class { class definition... }
            scan();
            open_scope();
            String clstag = clstag();
            ELNode.DEFINE[] body = parseClassBody();
            close_scope();
            return new ELNode.NEWOBJ(p, filename, cls, clstag, body);
        }
    }

    private void parseNamedArguments(List<ELNode> args, List<String> keys) {
        do {
            String key = parseArgumentName();
            ELNode arg = parseSyntaxExpression();
            if (key != null && keys.contains(key))
                throw parseError(_T(EL_DUPLICATE_ARG_NAME, key));
            keys.add(key);
            args.add(arg);
        } while (scan(COMMA));
    }

    private static String[] check_keys(List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) != null) {
                return to_a(keys);
            }
        }
        return null;
    }

    private String parseArgumentName() {
        String key = null;

        // prefix:key = value
        // key = value
        // 'key' = value

        if (token == IDENT) {
            mark();
            key = scanQName();
            scan();
        } else if (token == STRINGVAL) {
            mark();
            key = stringValue;
            scan();
        } else {
            return null;
        }

        if (token == ASSIGN) {
            scan();
            return key;
        } else {
            reset();
            return null;
        }
    }

    private ELNode[] parseArrayDimensions() {
        expect(LBRACKET);
        if (token == RBRACKET) {
            scan();
            return null;
        }

        List<ELNode> dims = new ArrayList<ELNode>();
        do {
            dims.add(parseExpression());
            expect(RBRACKET);
        } while (scan(LBRACKET));
        return to_a(dims);
    }

    private ELNode[] parseArrayInitializer() {
        List<ELNode> elems = new ArrayList<ELNode>();
        while (token != RBRACE) {
            elems.add(parseSyntaxExpression());
            if (!scan(COMMA)) {
                break;
            }
        }
        expect(RBRACE);
        return to_a(elems);
    }

    ELNode parseEmbedExpression(int p, String str) {
        // quick test
        if (str.indexOf('$') == -1 && str.indexOf('#') == -1 && str.indexOf('\\') == -1) {
            return new ELNode.STRINGVAL(p, str);
        }

        Parser parser = new Parser(str);
        parser.setFileName(filename);
        parser.setLineNumber(Position.line(p));
        parser.importSyntaxRules(this);
        parser.env = this.env;
        ELNode exp = parser.parseExpressionString(true);

        if (exp instanceof ELNode.LITERAL) {
            return new ELNode.STRINGVAL(p, ((ELNode.LITERAL)exp).value);
        } else if (exp instanceof ELNode.STRINGVAL) {
            return exp;
        } else if (exp instanceof ELNode.Composite) {
            return exp;
        } else {
            return new ELNode.Composite(p, new ELNode[] { exp });
        }
    }

    private ELNode parseListOrRangeExpression(int p) {
        if (token == RBRACKET) {
            scan();
            return new ELNode.NIL(p);
        }

        ELNode e1 = parseNonColonExpression(true), e2 = null;
        if (token == COMMA) {
            scan();
            e2 = parseNonColonExpression(true);
        }
        if (token == RANGE) {
            return parseRangeExpression(p, e1, e2);
        } else {
            return parseListExpression(p, e1, e2);
        }
    }

    private ELNode parseListExpression(int p, ELNode e1, ELNode e2) {
        if (e2 == null) {
            switch (token) {
            case IDENT:
                if (idValue.equals("where")) {
                    scan();
                    return parseListComprehension(e1);
                }
                break;
            case BAR:
                scan();
                return parseListComprehension(e1);
            case FOR: case LET: case IF:
                return parseListComprehension(e1);
            }
        }

        List<ELNode> elems = new ArrayList<ELNode>();
        ELNode tail = null;
        boolean lazy = false;

        elems.add(e1);
        if (e2 != null) {
            elems.add(e2);
        }

        while (scan(COMMA)) {
            if (token == RBRACKET) {
                break;
            }
            elems.add(parseNonColonExpression(true));
        }
        if (scan(COLON)) {
            lazy = scan(LAZY);
            tail = parseSyntaxExpression();
        } else {
            tail = new ELNode.NIL(p);
        }
        expect(RBRACKET);

        for (int i = elems.size(); --i >= 0; ) {
            tail = new ELNode.CONS(p, elems.get(i), tail, lazy);
            lazy = false;
        }
        return tail;
    }

    private static final int L_GENERATOR = 0;
    private static final int L_DECLARATION = 1;
    private static final int L_FILTER = 2;

    private static class Qualifier {
        int         type;
        ELNode      pat;
        ELNode      exp;
        Qualifier   next;

        Qualifier(int type, ELNode pat, ELNode exp, Qualifier next) {
            this.type = type;
            this.pat = pat;
            this.exp = exp;
            this.next = next;
        }
    }

    private ELNode parseListComprehension(ELNode e) {
        /*
         * 通常，一个列表聚合
         *      [expression 'where' qualifier, ...qualifier]
         * 是由一个表达式和数个由逗号分开的限定词构成，其中的限定词可以是一个生成器或者是
         * 一个过滤器。生成器的形式为var in 列表表达式，它引入在列表上迭代的一个变量，该
         * 变量可以在后边的限定词中使用。一个过滤器是一个布尔表达式，它对由先前限定词生成
         * 的变量进行约束。
         *
         * 列表聚合的转换工作是通过每次从左到右处理一个限定词来完成。这种方法导致以下的递
         * 归模式:
         *
         * T([expr|])           => [expr]                                    (1)
         * T([expr|F,Q])        => F ? T([expr|Q]) : []                      (2)
         * T([expr|v=E,Q])      => {v=>T([expr|Q])}(E)                       (3)
         * T([expr|v in L, Q])  => L.mappend({v=>T([expr|Q])})               (4)
         * T([expr|p=E,Q])      => {v=>match(v,p)?T([expr|Q]):[]}(E)         (5)
         * T([expr|p in L, Q])  => L.mappend({v=>match(v,p)?T([expr|Q]):[]}) (6)
         *
         * 转换规则(1)涉及到了一个在列表聚合中不再有限定词出现的基本事例。其结果是
         * 一个包含表达式expr的单一元素列表。
         *
         * 过滤器限定词在转换规则(2)中被处理，其中F表示过滤器，Q标识限定词的剩余序列。过
         * 滤器被转换成一个条件表达式。如果F中条件成立，我们通过递归调用转换模式T来计算列
         * 表聚合的剩余部分，否则通过返回一个空列表来终止计算。
         *
         * 规则(3)用于声明局部变量，此变量可以用于结果列表或后续的限定词中。局部变量被转换
         * 成对一个lambda表达式的直接调用，此lambda表达式递归调用转换模式T来计算列表聚合的
         * 剩余部分。
         *
         * 规则(4)包含生成器限定词var in L。生成器从列表L中提取零或多个元素e。我们必须生
         * 成能对L中的所有元素e进行迭代的代码，对于e的每一个值计算列表聚合的剩余量Q，并将
         * 得到的结果列表（可能为空）连接成一个列表。其核心思想是生成一个lambda表达式f(Q)，
         * 它取元素e并产生在元素e下使Q（列表聚合的剩余部分）能够成立的值列表，于是以L的每
         * 一个元素作为参数，lambda表达式f(Q)被调用，并对其生成的结果列表进行连接，从而生
         * 成所需要的结果。
         *
         * 规则(5)和(6)是对规则(3)和(4)的扩充，允许使用模式匹配来代替变量。
         *
         * 以上算法来自《The Modern Compiler Design》
         */

        Qualifier Qs = null;

        // 分析
        do {
            ELNode pat;
            switch (token) {
            case FOR:
                scan();
                pat = (ELNode)parsePattern();
                expect(IN);
                Qs = new Qualifier(L_GENERATOR, pat, parseExpression(), Qs);
                break;

            case LET:
                scan();
                pat = (ELNode)parsePattern();
                expect(ASSIGN);
                Qs = new Qualifier(L_DECLARATION, pat, parseExpression(), Qs);
                break;

            case IF:
                scan();
                Qs = new Qualifier(L_FILTER, null, parseExpression(), Qs);
                break;

            default:
                Scanner mark = save();
                pat = (ELNode)parsePatternOpt();
                if (pat != null) {
                    if (scan(IN)) {
                        Qs = new Qualifier(L_GENERATOR, pat, parseExpression(), Qs);
                    } else if (scan(ASSIGN)) {
                        Qs = new Qualifier(L_DECLARATION, pat, parseExpression(), Qs);
                    } else {
                        restore(mark);
                        Qs = new Qualifier(L_FILTER, null, parseExpression(), Qs);
                    }
                } else {
                    restore(mark);
                    Qs = new Qualifier(L_FILTER, null, parseExpression(), Qs);
                }
                break;
            }
        } while (scan(COMMA) || token == FOR || token == LET || token == IF);
        expect(RBRACKET);

        // 转换
        e = new ELNode.CONS(e.pos, e, new ELNode.NIL(e.pos)); // (1)
        for (; Qs != null; Qs = Qs.next) {
            if (Qs.type == L_FILTER) {
                // (2) guard
                ELNode F = Qs.exp;
                e = new ELNode.COND(F.pos, F, e, new ELNode.NULL(F.pos));
            } else {
                // generator or declaration
                ELNode.DEFINE V;

                if (isVariablePattern(Qs.pat)) {
                    // simple case, optimize
                    V = (ELNode.DEFINE)Qs.pat;
                } else {
                    // pattern matching
                    ELNode P = Qs.pat;
                    String t = tempvar();
                    V = new ELNode.DEFINE(P.pos, t);
                    e = new ELNode.MATCH(
                        P.pos,
                        new ELNode.IDENT(P.pos, t),
                        new ELNode.CASE(P.pos, (ELNode.Pattern)P, null, e),
                        new ELNode.NULL(P.pos));
                }

                if (Qs.type == L_GENERATOR) {
                    // (4) generator
                    ELNode L = Qs.exp;
                    e = new ELNode.APPLY(
                        L.pos,
                        new ELNode.ACCESS(L.pos, L, new ELNode.STRINGVAL(L.pos, "mappend")),
                        new ELNode.LAMBDA(L.pos, filename, new ELNode.DEFINE[]{V}, e));
                } else {
                    // (3) local declaration
                    ELNode E = Qs.exp;
                    e = new ELNode.APPLY(
                            E.pos,
                            new ELNode.LAMBDA(E.pos, filename, new ELNode.DEFINE[]{V}, e),
                            E);
                }
            }
        }
        return e;
    }

    private ELNode.RANGE parseRangeExpression(int p, ELNode begin, ELNode next) {
        ELNode end = null;
        boolean exclude = false;

        expect(RANGE);
        if (token == MUL) {
            scan();
            if (token != RBRACKET) {
                end = parseExpression();
                exclude = true;
            }
        } else {
            end = parseExpression();
        }
        expect(RBRACKET);
        return new ELNode.RANGE(p, begin, next, end, exclude);
    }

    private ELNode.MAP parseMapExpression(int p) {
        List<ELNode> keys = new ArrayList<ELNode>();
        List<ELNode> values = new ArrayList<ELNode>();

        while (token != RBRACE) {
            ELNode k = null, v;

            // parse map key is complicated, the key may be
            // an identifier, an @identifier, or an expression
            if (token == IDENT) {
                mark();
                String id = idValue;
                int pp = scan();
                if (token == COLON) {
                    k = new ELNode.STRINGVAL(pp, id);
                } else {
                    reset();
                    k = parseExpression();
                }
            } else if (token == ATSIGN) {
                mark();
                String id = "@";
                int pp = scan();
                while (token == ATSIGN) {
                    scan();
                    id += "@";
                }
                if (idValue != null) {
                    id += idValue;
                    scan();
                    if (token == COLON) {
                        k = new ELNode.STRINGVAL(pp, id);
                    }
                }
                if (k == null) {
                    reset();
                    k = parseExpression();
                }
            } else {
                k = parseExpression();
            }
            expect(COLON);
            v = parseSyntaxExpression();

            keys.add(k);
            values.add(v);

            // The comma at end of line can be ommitted
            if (token == COMMA) {
                scan();
            } else if (token == RBRACE) {
                break;
            } else if (!sawNewLine()) {
                expect(COMMA);
            }
        }

        expect(RBRACE);
        return new ELNode.MAP(p, to_a(keys), to_a(values));
    }

    private boolean looksLikeMap() {
        boolean ret = false;
        mark();
        if (token == LBRACE) {
            scan();
            if (token == IDENT || token == STRINGVAL) {
                scan();
                ret = token == COLON;
            } else if (token == ATSIGN) {
                do {
                    scan();
                } while (token == ATSIGN);
                if (idValue != null) {
                    scan();
                    ret = token == COLON;
                }
            }
        }
        reset();
        return ret;
    }

    private String parseClassLiteral(boolean pkg) {
        String id;
        if (!pkg && (ch == ':' && Character.isJavaIdentifierStart((char)lookahead(0)))) {
            id = scanQName();
            expect(IDENT);
            return id;
        } else {
            id = idValue;
            expect(IDENT);
        }

        StringBuilder buf = new StringBuilder(id);
        while (token == FIELD) {
            scan();
            if (pkg && token == MUL) {
                scan();
                buf.append(".*");
                break;
            } else {
                buf.append('.').append(idValue);
                expect(IDENT);
            }
        }
        return buf.toString();
    }

    private String parseTypeNameOpt() {
        if (token == COLONCOLON) {
            scan();
            return parseClassLiteral(false);
        } else {
            return null;
        }
    }

    /**
     * Parse a lambda abstraction.
     */
    private ELNode parseLambdaAbstraction(int p) {
        List<ELNode.Pattern> pats = new ArrayList<ELNode.Pattern>();
        boolean varargs = false;
        ELNode body;

        if (token != ARROW) {
            do {
                pats.add(parsePattern());
                if (token == ELLIPSIS) {
                    scan();
                    varargs = true;
                    break;
                }
            } while (scan(COMMA));
        }
        expect(ARROW);

        open_scope();
        add_pattern_vars(pats);
        if (token == LBRACE) {
            body = parseCompoundExpression(scan());
            expect(RBRACE);
        } else {
            body = parseSyntaxExpression();
        }
        close_scope();

        return translateLambda(p, null, null, pats, varargs, body);
    }

    /**
     * Parse an optional lambda expression.
     *
     * If the lambda expression was not found then the current position is
     * pointed at '{' character.
     */
    private ELNode parseLambdaExpressionOpt(int p) {
        List<ELNode.Pattern> pats = new ArrayList<ELNode.Pattern>();
        boolean varargs = false;

        if (token == ARROW) {
            scan();
        } else {
            Scanner mark = save();

            do {
                ELNode.Pattern pat = parsePatternOpt();
                if (pat != null) {
                    pats.add(pat);
                } else {
                    restore(mark);
                    return null;
                }
                if (token == ELLIPSIS) {
                    scan();
                    varargs = true;
                    break;
                }
            } while (scan(COMMA));

            if (varargs) {
                expect(ARROW);
            } else if (token == ARROW) {
                scan();
            } else {
                // not a lambda expression
                restore(mark);
                return null;
            }
        }

        open_scope();
        add_pattern_vars(pats);
        ELNode body = parseCompoundExpression(p);
        close_scope();

        return translateLambda(p, null, null, pats, varargs, body);
    }

    /**
     * Parse a block. A block is a syntax sugar of simplified lambda expression.
     */
    private ELNode parseBlock(int p) {
        if (token == BAR) {
            // parse the match patterns, which is a convenient block definition
            ELNode.MATCH body = parseMatchPatterns(p, null);
            expect(RBRACE);
            if (body.args.length == 0) {
                // we cannot determine whether the procedure has no arguments
                // or arguments ignored, so create block in this case
                return new ELNode.BLOCK(p, filename, body);
            } else {
                // Create variable list based on patterns
                ELNode.DEFINE[] vars = new ELNode.DEFINE[body.args.length];
                for (int i = 0; i < vars.length; i++) {
                    ELNode.IDENT arg = (ELNode.IDENT)body.args[i];
                    vars[i] = new ELNode.DEFINE(p, arg.id);
                }
                return new ELNode.LAMBDA(p, filename, vars, body);
            }
        }

        List<ELNode.Pattern> pats = new ArrayList<ELNode.Pattern>();
        boolean varargs = false;
        boolean block = false;

        // parse optional formal parameter list
        if (token == ARROW) {
            scan();
        } else {
            Scanner mark = save();

            do {
                ELNode.Pattern pat = parsePatternOpt();
                if (pat != null) {
                    pats.add(pat);
                } else {
                    restore(mark);
                    block = true;
                    break;
                }
                if (token == ELLIPSIS) {
                    scan();
                    varargs = true;
                    break;
                }
            } while (scan(COMMA));

            if (varargs) {
                expect(ARROW);
            } else if (token == ARROW) {
                scan();
            } else {
                restore(mark);
                block = true;
            }
        }

        open_scope();
        add_pattern_vars(pats);
        ELNode body = parseCompoundExpression(p);
        expect(RBRACE);
        close_scope();

        if (block) {
            return new ELNode.BLOCK(p, filename, body);
        } else {
            return translateLambda(p, null, null, pats, varargs, body);
        }
    }

    /**
     * Translate a lambda expression with pattern-matching.
     */
    private ELNode.LAMBDA translateLambda(int p,
                                          String name,
                                          String type,
                                          List<ELNode.Pattern> patterns,
                                          boolean varargs,
                                          ELNode body)
    {
        int npats = patterns.size();
        ELNode.DEFINE[] vars = new ELNode.DEFINE[npats];
        boolean simple = true;

        // build variable list and checks for simple case
        for (int i = 0; i < npats; i++) {
            ELNode pat = (ELNode)patterns.get(i);
            if (isVariablePattern(pat)) {
                vars[i] = (ELNode.DEFINE)pat;
            } else {
                vars[i] = new ELNode.DEFINE(pat.pos, tempvar());
                simple = false;
            }
        }

        if (!simple) {
            // generate pattern matching
            ELNode.Pattern pats[] = new ELNode.Pattern[npats];
            ELNode.IDENT   args[] = new ELNode.IDENT[npats];
            for (int i = 0; i < npats; i++) {
                pats[i] = patterns.get(i);
                args[i] = new ELNode.IDENT(vars[i].pos, vars[i].id);
            }
            body = new ELNode.MATCH(body.pos, args, new ELNode.CASE(p, pats, null, body), null);
        }

        checkVars(p, vars, varargs);
        return new ELNode.LAMBDA(p, filename, name, type, vars, varargs, body);
    }

    private ELNode parseProcedureDefinition(String name, String rtype, ELNode.METASET meta) {
        int p = pos;
        List<ELNode.DEFINE[]> vars_list = new ArrayList<ELNode.DEFINE[]>();
        BitSet vararg_flags = new BitSet();
        ELNode body;

        open_scope();

        // parse parameter list, may be curried
        expect(LPAREN);
        do {
            List<ELNode.DEFINE> vars = new ArrayList<ELNode.DEFINE>();
            boolean varargs = false;
            if (token != RPAREN) {
                do {
                    vars.add(parseParameter());
                    if (token == ELLIPSIS) {
                        scan();
                        varargs = true;
                        break;
                    }
                } while (scan(COMMA));
            }
            expect(RPAREN);
            vararg_flags.set(vars_list.size(), varargs);
            vars_list.add(checkVars(p, vars, varargs));
        } while (scan(LPAREN));

        // parse procedure body
        if (token == ARROW) {
            // foo(x) => exp; syntax sugar, translate to: foo = {x=>exp}
            scan();
            body = parseExpressionStatement();
        } else if (token == LBRACE) {
            scan();
            if (token == BAR) {
                // translate into a match expression
                ELNode.DEFINE[] vars = vars_list.get(0);
                ELNode[] args = new ELNode[vars.length];
                for (int i = 0; i < args.length; i++) {
                    args[i] = new ELNode.IDENT(vars[i].pos, vars[i].id);
                }
                body = parseMatchPatterns(pos, args);
            } else {
                body = parseCompoundExpression(pos);
            }
            expect(RBRACE);
        } else {
            body = null;
        }

        boolean isAbstract = (meta != null) && (meta.modifiers & Modifier.ABSTRACT) != 0;
        if (isAbstract && body != null) {
            throw parseError(p, _T(EL_INVALID_METHOD_BODY));
        } else if (!isAbstract && body == null) {
            throw parseError(p, _T(EL_NO_METHOD_BODY));
        }

        // for curried function definition:
        //      define f(a)(b)(c) => body
        // translated to:
        //      define f = {a=>{b=>{c=>body}}}
        for (int i = vars_list.size(); --i >= 0; ) {
            ELNode.DEFINE[] vars = vars_list.get(i);
            boolean vararg = vararg_flags.get(i);
            if (i == 0) {
                body = new ELNode.LAMBDA(p, filename, name, rtype, vars, vararg, body);
            } else {
                body = new ELNode.LAMBDA(p, filename, null, null, vars, vararg, body);
            }
        }

        close_scope();
        return body;
    }

    private ELNode.DEFINE parseParameter() {
        int p = pos;
        ELNode.METASET meta = parseMetaData();
        boolean lazy = scan(LAZY);
        String var = scanQName(); expect(IDENT);
        String type = parseTypeNameOpt();
        ELNode exp = scan(ASSIGN) ? parseExpression() : null;
        return new ELNode.DEFINE(p, var, type, meta, exp, !lazy);
    }

    private ELNode.DEFINE[] checkVars(int p, List<ELNode.DEFINE> varlist, boolean varargs) {
        ELNode.DEFINE[] vars = to_a(varlist);
        checkVars(p, vars, varargs);
        return vars;
    }

    private void checkVars(int p, ELNode.DEFINE[] vars, boolean varargs) {
        // check for duplicate argument name
        for (int i = 0; i < vars.length; i++) {
            String id = vars[i].id;
            if ("_".equals(id)) {
                ELNode.DEFINE var = vars[i];
                vars[i] = new ELNode.DEFINE(var.pos, tempvar(), var.type,
                                            var.meta, var.expr, var.immediate);
            } else {
                for (int j = 0; j < i; j++) {
                    if (id.equals(vars[j].id)) {
                        throw parseError(p, _T(EL_DUPLICATE_VAR_NAME, id));
                    }
                }
                add_symbol(vars[i]);
            }
        }

        // check for default argument value
        boolean found_dval = false;
        for (ELNode.DEFINE var : vars) {
            if (var.expr != null) {
                found_dval = true;
            } else if (found_dval) {
                throw parseError(p, _T(EL_NON_DFLT_ARG_FOLLOWS_DFLT_ARG));
            }
        }

        if (found_dval && varargs) {
            throw parseError(p, _T(EL_NON_DFLT_ARG_FOLLOWS_DFLT_ARG));
        }
    }

    /**
     * Parse a compound expression.
     */
    ELNode parseCompoundExpression(int p) {
        return parseCompoundExpression(p, false);
    }

    private ELNode parseCompoundExpression(int p, boolean atexp) {
        List<ELNode> exps = new ArrayList<ELNode>();

        open_scope();
        while (token != RBRACE) {
            if (token == ATSIGN && atexp) {
                // @ is conflict with annotations and @-identifiers. The
                // @-identifier is allowed only in a .{} construction.
                exps.add(parseExpressionStatement());
            } else {
                parseStatementList(exps);
            }
        }
        close_scope();

        if (exps.size() == 0) {
            return new ELNode.NULL(p);
        } else if (exps.size() == 1) {
            return exps.get(0);
        } else {
            return new ELNode.COMPOUND(p, to_a(exps));
        }
    }

    void parseStatementList(List<ELNode> stmts) {
        switch (token) {
        case SEMI:
            scan();
            break;

        case DEFINE:
        case PUBLIC:
        case PROTECTED:
        case PRIVATE:
        case ABSTRACT:
        case STATIC:
        case FINAL:
        case ATSIGN:
            for (ELNode.DEFINE e : parseDefinitions(parseMetaData())) {
                checkVar(e, false);
                stmts.add(e);
            }
            break;

        case CLASSDEF:
            stmts.add(parseClassDefinition(scan(), null));
            break;

        case VOID:
            stmts.add(parseVoidExpression());
            expect(SEMI);
            break;

        case LBRACE: // lambda or map
            stmts.add(parseExpressionStatement());
            expect(SEMI);
            break;

        default:
            ELNode stmt = parseStatement();
            if (stmt instanceof ELNode.COMPOUND) {
                if (((ELNode.COMPOUND)stmt).exps.length != 0)
                    stmts.add(stmt);
            } else {
                stmts.add(stmt);
            }
            break;
        }
    }

    /**
     * Parse a statement expression.
     */
    ELNode parseStatement() {
        int p;
        ELNode e;

        switch (token) {
        case SEMI:
            e = new ELNode.COMPOUND(scan(), EMPTY_EXPS);
            break;

        case LBRACE:
            e = parseCompoundExpression(scan());
            expect(RBRACE);
            break;

        case IF:
            e = parseIfExpression(scan());
            break;

        case WHILE:
            e = parseWhileExpression(scan());
            break;

        case FOR:
            e = parseForExpression(scan());
            break;

        case LET:
            p = scan();
            if (scan(NOT)) { // let!
                ELNode.Pattern pattern = parsePattern();
                expect(ASSIGN);
                ELNode exp = parseExpressionStatement();
                add_pattern_vars(pattern);
                return new ELNode.LET(p, (ELNode)pattern, exp, true);
            } else if (scan(MUL)) { // let*
                return parseLetExpression(p, true);
            } else {
                // conflict with let expression, do lookahead
                Scanner mark = save();
                ELNode.Pattern pattern = parsePatternOpt();
                if (pattern != null && scan(ASSIGN)) {
                    ELNode exp = parseExpressionStatement();
                    add_pattern_vars(pattern);
                    return new ELNode.LET(p, (ELNode)pattern, exp, false);
                } else {
                    restore(mark);
                    return parseLetExpression(p, false);
                }
            }

        case SWITCH:
            e = parseSwitchExpression(scan());
            break;

        case TRY:
            e = parseTryExpression(scan());
            break;

        case SYNCHRONIZED:
            e = parseSynchronizedExpression(scan());
            break;

        case UNDEF:
            p = scan();
            if (token == PREFIX || token == INFIX || token == KEYWORD) {
                String id = operator.name;
                discard_symbol(id);
                e = new ELNode.UNDEF(p, id);
                scan();
            } else {
                String id = scanQName();
                expect(IDENT);
                discard_symbol(id);
                e = new ELNode.UNDEF(p, id);
            }
            expect(SEMI);
            break;

        case BREAK:
            e = new ELNode.BREAK(scan());
            expect(SEMI);
            break;

        case CONTINUE:
            e = new ELNode.CONTINUE(scan());
            expect(SEMI);
            break;

        case RETURN:
            p = scan();
            if (scanLayout()) {
                e = new ELNode.RETURN(p, new ELNode.NULL(p));
            } else {
                e = new ELNode.RETURN(p, parseExpression());
            }
            expect(SEMI);
            break;

        case THROW:
            e = new ELNode.THROW(scan(), parseExpression());
            expect(SEMI);
            break;

        case ASSERT:
            e = parseAssertExpression(scan());
            break;

        default:
            e = parseExpressionStatement();
            expect(SEMI);
            break;
        }

        return e;
    }

    private List<ELNode.DEFINE> parseDefinitions(ELNode.METASET meta) {
        List<ELNode.DEFINE> defs = new ArrayList<ELNode.DEFINE>();
        if (token == CLASSDEF) {
            defs.add(parseClassDefinition(scan(), meta));
        } else if (isMetaDataPresent(meta, "data")) {
            for (ELNode.DEFINE e : parseDataDefinition(meta)) {
                defs.add(e);
            }
        } else {
            do {
                ELNode.DEFINE e = parseSingleDefinition(meta);
                if (e != null) defs.add(e);
            } while (scan(COMMA));
            expect(SEMI);
        }
        return defs;
    }

    private ELNode.DEFINE parseSingleDefinition(ELNode.METASET meta) {
        int p = pos;
        ELNode.DEFINE var;
        String type;

        if (token == VOID) {
            scan();
            var = scanVar(p, meta);
            var.expr = parseProcedureDefinition(var.id, "void", meta);
            return var;
        }

        var = scanVar(p, meta);
        type = parseTypeNameOpt();

        switch (token) {
        case ASSIGN:
            // define id=exp;
            scan();
            var.type = type;
            var.immediate = !scan(LAZY);
            var.expr = parseExpressionStatement();
            break;

        case LPAREN:
            // define foo(x,y) {exp}
            // syntax sugar for foo={x,y=>exp}
            var.expr = parseProcedureDefinition(var.id, type, meta);
            break;

        default:
            if (var.operator != null) {
                // declare operator only, no actual definition
                // e.g. @prefix print;
                return null;
            }

            // define x;
            var.expr = new ELNode.NULL(pos);
            break;
        }

        return var;
    }

    private ELNode.DEFINE scanVar(int p, ELNode.METASET meta) {
        int oper = -1, prec = 0;

        // check for @prefix or @infix
        if (meta != null) {
            for (ELNode.METADATA data : meta.metadata) {
                if (data.type.equals("infix")) {
                    oper = INFIX;
                    prec = precedence(data);
                    break;
                } else if (data.type.equals("prefix")) {
                    oper = PREFIX;
                    prec = precedence(data);
                    break;
                } else if (data.type.equals("keyword")) {
                    oper = KEYWORD;
                    break;
                }
            }
        }

        String id;
        if (oper != -1 && token == STRINGVAL) {
            id = stringValue;
            scan();
        } else if (token == PREFIX || token == INFIX) {
            id = operator.name;
            scan();
        } else {
            id = scanQName();
            expect(IDENT);
        }

        ELNode.DEFINE var = new_symbol(p, id, null, meta);
        if (oper != -1) {
            var.operator = getOperator(id); // save previous operator
            if (var.operator == null)
                var.operator = NULL_OPERATOR;
            addOperator(id, oper, prec);
        }
        return var;
    }

    private int precedence(ELNode.METADATA data) {
        if (data.keys.length == 0) {
            return ELNode.DEFAULT_PREC;
        } else if (data.keys.length == 1 &&
                   data.keys[0].equals("value") &&
                   data.values[0] instanceof ELNode.NUMBER) {
            return ((ELNode.NUMBER)data.values[0]).value.intValue();
        } else {
            throw parseError(data.pos, "Invalid precedence declaration.");
        }
    }

    private ELNode parseVoidExpression() {
        mark();

        // void foo(...)  ===> a declaration
        int p = scan();
        if (token == IDENT) {
            String id = scanQName();
            scan();
            if (token == LPAREN) {
                ELNode.DEFINE var = new_symbol(p, id, null, null);
                var.expr = parseProcedureDefinition(id, "void", null);
                return var;
            }
        }

        // void(...)  ===> an expression
        reset();
        return parseExpressionStatement();
    }

    private ELNode.DEFINE parseClassDefinition(int p, ELNode.METASET meta) {
        String id;
        String base = null;
        String[] ifaces = null;
        ELNode.DEFINE[] vars = null;
        ELNode.DEFINE[] body = null;
        ELNode.DEFINE cdef;

        id = scanQName(); expect(IDENT);
        cdef = new_symbol(p, id, null, meta);

        // parse initialization variables
        if (scan(LPAREN)) {
            List<ELNode.DEFINE> vlist = new ArrayList<ELNode.DEFINE>();
            if (token != RPAREN) {
                do {
                    vlist.add(checkMember(parseParameter(), vlist));
                } while (scan(COMMA));
            }
            expect(RPAREN);
            vars = to_a(vlist);
        }

        // parse base class and interfaces
        if (token == COLONCOLON) {
            scan();
            base = parseClassLiteral(false);
        } else if (token == EXTENDS || token == IMPLEMENTS) {
            if (token == EXTENDS) {
                scan();
                base = parseClassLiteral(false);
            }

            if (token == IMPLEMENTS) {
                scan();
                List<String> iflist = new ArrayList<String>();
                do {
                    iflist.add(parseClassLiteral(false));
                } while (scan(COMMA));
                ifaces = to_a(iflist);
            }
        }

        // parse class body
        if (token == LBRACE) {
            scan();
            open_scope();
            if (vars != null) {
                for (ELNode.DEFINE var : vars)
                    add_symbol(var);
            }
            body = parseClassBody();
            close_scope();
        } else {
            body = EMPTY_DEFS;
        }

        cdef.expr = new ELNode.CLASSDEF(p, filename, id, base, ifaces, vars, body);
        return cdef;
    }

    private ELNode.DEFINE[] parseClassBody() {
        List<ELNode.DEFINE> body = new ArrayList<ELNode.DEFINE>();
        List<ELNode> clinit = new ArrayList<ELNode>();

        do {
            if (token == SEMI) {
                scan();
                continue;
            }

            if (token == RBRACE) {
                scan();
                break;
            }

            if (token == STATIC) {
                mark(); scan();
                if (token == LBRACE) {
                    // static { ... }
                    clinit.add(parseCompoundExpression(scan()));
                    expect(RBRACE);
                    continue;
                } else {
                    reset();
                }
            }

            ELNode.METASET meta = parseMetaData();

            ELNode rule = matchPrefixGrammar();
            if (rule != null) {
                if (rule instanceof ELNode.NULL) {
                    // ignore empty definition list
                } else if (rule instanceof ELNode.DEFINE) {
                    ELNode.DEFINE def = add_symbol(adjoin((ELNode.DEFINE)rule, meta));
                    body.add(checkMember(def, body));
                } else if (rule instanceof ELNode.COMPOUND) {
                    for (ELNode e : ((ELNode.COMPOUND)rule).exps) {
                        if (e instanceof ELNode.DEFINE) {
                            ELNode.DEFINE def = add_symbol(adjoin((ELNode.DEFINE)e, meta));
                            body.add(checkMember(def, body));
                        } else {
                            throw parseError(e.pos, _T(EL_IDENTIFIER_EXPECTED));
                        }
                    }
                } else {
                    throw parseError(rule.pos, _T(EL_IDENTIFIER_EXPECTED));
                }
                expect(SEMI);
                continue;
            }

            if (token == IDENT || token == VOID || token == CLASSDEF) {
                for (ELNode.DEFINE e : parseDefinitions(meta)) {
                    body.add(checkMember(e, body));
                }
            } else {
                ELNode.DEFINE def = parseOperatorProcedure(meta);
                body.add(checkMember(def, body));
                expect(SEMI);
            }
        } while (true);

        if (!clinit.isEmpty()) {
            int p = clinit.get(0).pos;
            ELNode stmt = new ELNode.COMPOUND(p, to_a(clinit));
            ELNode proc = new ELNode.LAMBDA(p, filename, EMPTY_DEFS, stmt);
            ELNode.METASET meta = new ELNode.METASET(p, Modifier.STATIC);
            body.add(new ELNode.DEFINE(p, ClassDefinition.CLINIT_PROC, null, meta, proc, true));
        }

        return to_a(body);
    }

    private ELNode.DEFINE parseOperatorProcedure(ELNode.METASET meta) {
        int p = pos;
        String opname = null;
        boolean reverse = false;
        ELNode.DEFINE def;

        if (token == QUESTIONMARK) {
            scan();
            reverse = true;
        }

        switch (token) {
        case INFIX: case CAT: case ADD: case SUB: case MUL: case DIV: case REM: case POW:
        case BITOR: case BITAND: case XOR: case SHL: case SHR: case USHR:
            opname = operator.name;
            if (reverse) {
                opname = "?" + opname;
            }
            scan();
            break;

        case PREFIX: case ASSIGNOP: case XFORM:
        case NOT: case BITNOT: case INC: case DEC:
        case EQ: case NE: case LT: case LE: case GT: case GE:
            opname = operator.name;
            scan();
            break;

        case LBRACKET:
            scan();
            expect(RBRACKET);
            if (token == ASSIGN) {
                scan();
                opname = "[]=";
            } else {
                opname = "[]";
            }
            break;

        case IDENT:
            assert reverse;
            opname = "?" + idValue;
            scan();
            break;

        case EOI:
            throw incomplete("Unexpected EOF");
        default:
            throw parseError("Unexpected token: " + token_value());
        }

        if (reverse && !opname.startsWith("?")) {
            throw parseError("Unexpected token: " + opname);
        }

        def = new_symbol(p, opname, null, meta);
        def.expr = parseProcedureDefinition(opname, null, meta);
        return def;
    }

    private List<ELNode.DEFINE> parseDataDefinition(ELNode.METASET meta) {
        ELNode.DEFINE cdef;
        List<ELNode.DEFINE> defs = new ArrayList<ELNode.DEFINE>();
        int p = pos;

        String base = scanQName();
        expect(IDENT);
        expect(ASSIGN);

        // The base abstract algebraic data type
        cdef = new_symbol(p, base, null, adjoin(meta, Modifier.ABSTRACT));
        cdef.expr = new ELNode.CLASSDEF(p, filename, base, null, null, null, EMPTY_DEFS);
        defs.add(cdef);

        do {
            ELNode.METASET cmeta = adjoin(meta, parseMetaData());
            String id = scanQName();
            expect(IDENT);

            List<ELNode.DEFINE> vars = new ArrayList<ELNode.DEFINE>();
            if (scan(LPAREN)) {
                if (token != RPAREN) {
                    do {
                        vars.add(checkMember(parseParameter(), vars));
                    } while (scan(COMMA));
                }
                expect(RPAREN);
            }
            if (vars.isEmpty()) {
                cmeta = adjoin(p, cmeta, "Singleton");
            }

            cdef = new_symbol(p, id, null, cmeta);

            ELNode.DEFINE[] body;
            if (token == LBRACE) {
                open_scope();
                for (ELNode.DEFINE var : vars)
                    add_symbol(var);
                body = parseClassBody();
                close_scope();
            } else {
                body = EMPTY_DEFS;
            }

            cdef.expr = new ELNode.CLASSDEF(p, filename, id, base, null, to_a(vars), body);
            defs.add(cdef);
        } while (scan(BAR));

        return defs;
    }

    private ELNode.DEFINE checkMember(ELNode.DEFINE var, List<ELNode.DEFINE> vars) {
        // check duplicate member
        String name = var.id;
        for (ELNode.DEFINE v : vars) {
            if (name.equals(v.id)) {
                throw parseError(var.pos, _T(EL_DUPLICATE_VAR_NAME, name));
            }
        }

        // check modifier combination
        if (var.meta != null) {
            int mod = var.meta.modifiers;

            int mask = MM_MEMBER;
            if (var.expr instanceof ELNode.CLASSDEF) {
                mask |= MM_CLASS;
            } else if (var.expr instanceof ELNode.LAMBDA) {
                mask |= MM_METHOD;
            }

            int illegal = var.meta.modifiers & ~mask;
            if (illegal != 0) {
                throw parseError(var.pos, _T(EL_INVALID_MODIFIER, modifierNames(illegal)));
            }

            if (var.expr instanceof ELNode.CLASSDEF) {
                checkDisjoint(var.pos, mod, Modifier.ABSTRACT, Modifier.FINAL);
            } else if (var.expr instanceof ELNode.LAMBDA) {
                checkDisjoint(var.pos, mod, Modifier.ABSTRACT,
                              Modifier.STATIC | Modifier.FINAL | Modifier.SYNCHRONIZED);
            }

            checkDisjoint(var.pos, mod, Modifier.PUBLIC,
                          Modifier.PRIVATE | Modifier.PROTECTED);
            checkDisjoint(var.pos, mod, Modifier.PRIVATE,
                          Modifier.PUBLIC | Modifier.PROTECTED);
        }

        return var;
    }

    private ELNode.DEFINE checkVar(ELNode.DEFINE var, boolean toplevel) {
        if (var.meta != null) {
            int mod = var.meta.modifiers;

            int mask;
            if (var.expr instanceof ELNode.CLASSDEF) {
                mask = MM_CLASS;
            } else if (var.expr instanceof ELNode.LAMBDA) {
                mask = 0;
            } else {
                mask = MM_VAR;
            }
            if (toplevel) {
                mask |= MM_TOPLEVEL;
            }

            int illegal = mod & ~mask;
            if (illegal != 0) {
                throw parseError(var.pos, _T(EL_INVALID_MODIFIER, modifierNames(illegal)));
            }

            if (var.expr instanceof ELNode.CLASSDEF) {
                checkDisjoint(var.pos, mod, Modifier.ABSTRACT, Modifier.FINAL);
            }
        }

        return var;
    }

    /**
     * Check that modifier set does not contain elements of two conflicting sets.
     */
    private void checkDisjoint(int pos, int mod, int set1, int set2) {
        if ((mod & set1) != 0 && (mod & set2) != 0) {
            throw parseError(pos, _T(EL_INVALID_MODIFIER_COMBINATION,
                                     modifierNames(mod & set1),
                                     modifierNames(mod & set2)));
        }
    }

    private String modifierNames(int mod) {
        StringBuilder buf = new StringBuilder();
        if ((mod & Modifier.PUBLIC) != 0)
            buf.append("public ");
        if ((mod & Modifier.PROTECTED) != 0)
            buf.append("protected ");
        if ((mod & Modifier.PRIVATE) != 0)
            buf.append("private ");
        if ((mod & Modifier.STATIC) != 0)
            buf.append("static ");
        if ((mod & Modifier.FINAL) != 0)
            buf.append("final ");
        if ((mod & Modifier.ABSTRACT) != 0)
            buf.append("abstract ");
        if ((mod & Modifier.SYNCHRONIZED) != 0)
            buf.append("synchronized ");
        return buf.toString().trim();
    }

    private ELNode parseMetaExpression() {
        if (token == ATSIGN) {
            int p = scan();
            String type = idValue;
            expect(IDENT);

            if (token != LPAREN) {
                // @Foo ===> no attributes
                return new ELNode.METADATA(p, type, EMPTY_KEYS, EMPTY_EXPS);
            }

            scan();
            if (token == RPAREN) {
                // @Foo() ===> no attributes
                scan();
                return new ELNode.METADATA(p, type, EMPTY_KEYS, EMPTY_EXPS);
            }

            List<String> keys = new ArrayList<String>();
            List<ELNode> exps = new ArrayList<ELNode>();

            boolean nokey;
            // @Foo(abc)     ===> nokey
            // @Foo(123)     ===> nokey
            // @Foo(abc=123) ===> !nokey
            if (token == IDENT) {
                mark(); scan();
                nokey = (token != ASSIGN);
                reset();
            } else {
                nokey = true;
            }

            if (nokey) {
                keys.add("value");
                exps.add(parseMetaExpression());
            } else {
                do {
                    keys.add(idValue);
                    expect(IDENT);
                    expect(ASSIGN);
                    exps.add(parseMetaExpression());
                } while (scan(COMMA));
            }

            expect(RPAREN);
            return new ELNode.METADATA(p, type, to_a(keys), to_a(exps));

        } else if (token == LBRACKET || token == LBRACE) {
            mark();
            int close = (token == LBRACKET) ? RBRACKET : RBRACE;
            int p = scan();
            if (token == ATSIGN) {
                List<ELNode> elems = new ArrayList<ELNode>();
                do {
                    if (token != ATSIGN) {
                        expect(ATSIGN);
                    } else {
                        elems.add(parseMetaExpression());
                    }
                } while (scan(COMMA));
                expect(close);

                ELNode t = new ELNode.NIL(p);
                for (int i = elems.size(); --i >= 0; ) {
                    t = new ELNode.CONS(p, elems.get(i), t);
                }
                return t;
            } else {
                reset();
                return parseExpression();
            }
        } else {
            return parseExpression();
        }
    }

    private ELNode.METASET parseMetaData() {
        List<ELNode.METADATA> data = new ArrayList<ELNode.METADATA>();
        int mod = 0;

        while (token == ATSIGN) {
            data.add((ELNode.METADATA)parseMetaExpression());
        }

        if (token == DEFINE) {
            scan();
        }

    loop:
        while (true) {
            int nextmod = 0;
            switch (token) {
            case PUBLIC:       nextmod = Modifier.PUBLIC; break;
            case PROTECTED:    nextmod = Modifier.PROTECTED; break;
            case PRIVATE:      nextmod = Modifier.PRIVATE; break;
            case STATIC:       nextmod = Modifier.STATIC; break;
            case FINAL:        nextmod = Modifier.FINAL; break;
            case ABSTRACT:     nextmod = Modifier.ABSTRACT; break;
            case SYNCHRONIZED: nextmod = Modifier.SYNCHRONIZED; break;

            case ATSIGN:
                data.add((ELNode.METADATA)parseMetaExpression());
                break;

            default:
                break loop;
            }

            if (nextmod != 0) {
                if ((mod & nextmod) != 0) {
                    throw parseError(_T(EL_REPEATED_MODIFIER, idValue));
                }
                mod |= nextmod;
                scan();
            }
        }

        if (data.size() == 0 && mod == 0) {
            return null;
        }

        ELNode.METADATA[] meta = data.toArray(new ELNode.METADATA[data.size()]);
        return new ELNode.METASET(pos, meta, mod);
    }

    private boolean isMetaDataPresent(ELNode.METASET meta, String type) {
        if (meta != null) {
            for (ELNode.METADATA data : meta.metadata) {
                if (data.type.equals(type))
                    return true;
            }
        }
        return false;
    }

    private ELNode.METASET adjoin(ELNode.METASET meta1, ELNode.METASET meta2) {
        if (meta1 == null)
            return meta2;
        if (meta2 == null)
            return meta1;
        return meta1.adjoin(meta2);
    }

    private ELNode.METASET adjoin(int p, ELNode.METASET meta, String type) {
        if (isMetaDataPresent(meta, type)) {
            return meta;
        }

        ELNode.METASET newmeta =
            new ELNode.METASET(
                p, new ELNode.METADATA[] {
                       new ELNode.METADATA(p, type, EMPTY_KEYS, EMPTY_EXPS)
                }, 0);
        return adjoin(meta, newmeta);
    }

    private ELNode.METASET adjoin(ELNode.METASET meta, int modifiers) {
        if (modifiers != 0) {
            if (meta == null) {
                meta = new ELNode.METASET(pos, modifiers);
            } else {
                meta = new ELNode.METASET(meta.pos, meta.metadata, meta.modifiers | modifiers);
            }
        }
        return meta;
    }

    private ELNode.DEFINE adjoin(ELNode.DEFINE def, ELNode.METASET meta) {
        if (meta == null)
            return def;
        if (def.meta != null)
            meta = def.meta.adjoin(meta);
        return new ELNode.DEFINE(def.pos, def.id, def.type, meta, def.expr, def.immediate);
    }

    /**
     * Parse a let expression.
     */
    private ELNode parseLetExpression(int p, boolean sequential) {
        String name, type;
        List<ELNode.Pattern> pats = new ArrayList<ELNode.Pattern>();
        List<ELNode> exps = new ArrayList<ELNode>();
        ELNode body;

        // named let?
        if (!sequential && idValue != null) {
            name = scanQName();
            expect(IDENT);
            type = parseTypeNameOpt();
        } else {
            name = type = null;
        }

        // parse declarations
        expect(LPAREN);
        if (token != RPAREN) {
            do {
                pats.add(parsePattern());
                expect(ASSIGN);
                exps.add(parseExpression());
            } while (scan(COMMA));
        }
        expect(RPAREN);

        // parse body
        open_scope();
        add_pattern_vars(pats);
        if (token == LBRACE) {
            body = parseCompoundExpression(scan());
            expect(RBRACE);
        } else {
            body = parseSyntaxExpression();
        }
        close_scope();

        // translate
        if (sequential) {
            // sequential binding:
            //  translate
            //    let* (x=a,y=b) body
            //  into
            //    (\x=>(\y=>body)(b))(a)
            for (int i = pats.size(); --i >= 0; ) {
                ELNode.DEFINE[] vars = new ELNode.DEFINE[1];
                ELNode.Pattern pat = pats.get(i);

                if (isVariablePattern(pat)) {
                    // simple case
                    vars[0] = (ELNode.DEFINE)pat;
                } else {
                    // pattern matching
                    String tvar = tempvar();
                    vars[0] = new ELNode.DEFINE(p, tvar);
                    body = new ELNode.MATCH(p,
                            new ELNode.IDENT(p, tvar),
                            new ELNode.CASE(p, pat, null, body),
                            null);
                }

                body = new ELNode.APPLY(p,
                        new ELNode.LAMBDA(p, filename, vars, body),
                        exps.get(i));
            }
            return body;
        } else {
            // parallel binding:
            //  translate
            //    let (x=a,y=b) body
            //  into
            //    (\x,y=>body)(a,b)
            ELNode.LAMBDA lambda = translateLambda(p, name, type, pats, false, body);
            return new ELNode.APPLY(p, lambda, to_a(exps), null);
        }
    }

    /**
     * Parse an if expression.
     */
    private ELNode parseIfExpression(int p) {
        expect(LPAREN);
        ELNode c = parseSyntaxExpression();
        expect(RPAREN);
        ELNode t = parseStatement();
        if (token == ELSE) {
            scan();
            return new ELNode.COND(p, c, t, parseStatement());
        } else {
            return new ELNode.COND(p, c, t, new ELNode.NULL(pos));
        }
    }

    /**
     * Parse a for loop expression.
     */
    private ELNode parseForExpression(int p) {
        boolean foreach = false;

        ELNode idx_pat = null;
        ELNode var_pat = null;
        ELNode range   = null;

        ELNode[] init  = null;
        ELNode   cond  = null;
        ELNode[] step  = null;

        ELNode body;

        expect(LPAREN);
        boolean local = scan(DEFINE);

        // for ([idx,]var in range) body
        Scanner mark = save();
        var_pat = (ELNode)parsePatternOpt();
        if (var_pat != null) {
            if (scan(COMMA)) {
                idx_pat = var_pat;
                var_pat = (ELNode)parsePatternOpt();
            }
            if (var_pat != null && scan(IN)) {
                foreach = true;
                range = parseExpression();
                expect(RPAREN);
            }
        }

        if (!foreach) {
            restore(mark);
            open_scope();

            if (token != SEMI) {
                if (local) {
                    init = to_a(parseDefinitions(null));
                } else {
                    List<ELNode> exps = new ArrayList<ELNode>();
                    do {
                        exps.add(parseExpression());
                    } while (scan(COMMA));
                    init = to_a(exps);
                    expect(SEMI);
                }
            } else {
                scan();
            }

            if (token != SEMI) {
                cond = parseExpression();
            } else {
                cond = new ELNode.BOOLEANVAL(pos, true);
            }
            expect(SEMI);

            if (token != RPAREN) {
                List<ELNode> args = new ArrayList<ELNode>();
                do {
                    args.add(parseExpression());
                } while (scan(COMMA));
                step = to_a(args);
            }
            expect(RPAREN);

            body = parseStatement();
            close_scope();
            return new ELNode.FOR(p, init, cond, step, body, local);
        } else {
            ELNode.DEFINE var_def, idx_def = null;

            open_scope();
            add_pattern_vars((ELNode.Pattern)var_pat);
            if (idx_pat != null)
                add_pattern_vars((ELNode.Pattern)idx_pat);
            body = parseStatement();
            close_scope();

            if (idx_pat == null) {
                if (isVariablePattern(var_pat)) {
                    var_def = (ELNode.DEFINE)var_pat;
                } else {
                    String var_t = tempvar();
                    var_def = new ELNode.DEFINE(p, var_t);
                    body = new ELNode.MATCH(
                               p,
                               new ELNode.IDENT(p, var_t),
                               new ELNode.CASE(p, (ELNode.Pattern)var_pat, null, body),
                               new ELNode.NULL(p));
                }
            } else {
                if (isVariablePattern(var_pat) && isVariablePattern(idx_pat)) {
                    var_def = (ELNode.DEFINE)var_pat;
                    idx_def = (ELNode.DEFINE)idx_pat;
                } else {
                    String var_t = tempvar();
                    String idx_t = tempvar();
                    var_def = new ELNode.DEFINE(p, var_t);
                    idx_def = new ELNode.DEFINE(p, idx_t);
                    body = new ELNode.MATCH(
                               p,
                               new ELNode[] {
                                   new ELNode.IDENT(p, idx_t),
                                   new ELNode.IDENT(p, var_t)
                               },
                               new ELNode.CASE(
                                   p,
                                   new ELNode.Pattern[] {
                                       (ELNode.Pattern)idx_pat,
                                       (ELNode.Pattern)var_pat
                                   },
                                   null,
                                   body),
                               new ELNode.NULL(p));
                }
            }

            return new ELNode.FOREACH(p, idx_def, var_def, range, body);
        }
    }

    /**
     * Parse a while loop expression.
     */
    private ELNode parseWhileExpression(int p) {
        expect(LPAREN);
        ELNode cond = parseSyntaxExpression();
        expect(RPAREN);
        return new ELNode.WHILE(p, cond, parseStatement());
    }

    /**
     * Parse a switch expression.
     */
    private ELNode parseSwitchExpression(int p) {
        ELNode arg;
        List<ELNode.CASE> cases = new ArrayList<ELNode.CASE>();
        List<ELNode> stmts = new ArrayList<ELNode>();
        ELNode deflt = null;

        expect(LPAREN);
        arg = parseSyntaxExpression();
        expect(RPAREN);

        expect(LBRACE);
        while (token != EOI && token != RBRACE) {
            int p2 = pos;
            ELNode pattern = null;
            boolean isdeflt = false;

            do {
                if (token == CASE) {
                    scan();
                    ELNode exp = parseNonColonExpression(false);
                    expect(COLON);
                    if (!(exp instanceof ELNode.Constant)) {
                        exp = new ELNode.EXPR(exp.pos, exp);
                    }
                    if (pattern == null) {
                        pattern = exp;
                    } else {
                        pattern = new ELNode.OR(exp.pos, pattern, exp);
                    }
                } else if (token == DEFAULT) {
                    if (deflt != null || isdeflt) {
                        throw parseError("duplicate default label");
                    } else {
                        scan();
                        expect(COLON);
                        isdeflt = true;
                    }
                } else {
                    expect(CASE);
                }
            } while (token == CASE || token == DEFAULT);

            open_scope();
            stmts.clear();
            while (token != CASE && token != DEFAULT && token != RBRACE) {
                parseStatementList(stmts);
            }
            close_scope();

            ELNode body;
            if (stmts.size() == 0) {
                body = new ELNode.NULL(p2);
            } else if (stmts.size() == 1) {
                body = stmts.get(0);
            } else {
                body = new ELNode.COMPOUND(p2, to_a(stmts));
            }

            if (pattern != null)
                cases.add(new ELNode.CASE(p2, (ELNode.Pattern)pattern, null, body));
            if (isdeflt)
                deflt = body;
        }
        expect(RBRACE);

        if (deflt == null) {
            deflt = new ELNode.NULL(p);
        }

        ELNode.CASE[] alts = cases.toArray(new ELNode.CASE[cases.size()]);
        if (canOptimizeMatchExpression(alts)) {
            return new ELNode.CONST_MATCH(p, new ELNode[]{arg}, alts, deflt);
        } else {
            return new ELNode.MATCH(p, new ELNode[]{arg}, alts, deflt);
        }
    }

    /**
     * Parse a match expression.
     */
    private ELNode parseMatchExpression(int p) {
        List<ELNode> args = new ArrayList<ELNode>();

        expect(LPAREN);
        if (token != RPAREN) {
            do {
                args.add(parseSyntaxExpression());
            } while (scan(COMMA));
        }
        expect(RPAREN);

        expect(LBRACE);
        ELNode exp = parseMatchPatterns(p, to_a(args));
        expect(RBRACE);
        return exp;
    }

    private ELNode.MATCH parseMatchPatterns(int p, ELNode[] args) {
        int nargs = args != null ? args.length : -1;
        List<ELNode.Pattern> pats = new ArrayList<ELNode.Pattern>();
        List<ELNode.CASE> cases = new ArrayList<ELNode.CASE>();
        List<ELNode> stmts = new ArrayList<ELNode>();
        ELNode deflt = null;

        while (token == BAR) {
            ELNode guard = null, body;
            boolean isdeflt = false;
            int p2 = scan();

            if (token == ELSE) {
                scan();
                isdeflt = true;
            } else {
                if (token != IF) {
                    pats.clear();
                    do {
                        pats.add(parsePattern());
                    } while (scan(COMMA));
                    if (nargs == -1) {
                        nargs = pats.size();
                    } else if (pats.size() != nargs) {
                        throw parseError("argument pattern doesn't match.");
                    }
                }
                if (scan(IF)) {
                    guard = parseExpression();
                }
            }

            expect(ARROW);

            int p3 = pos;
            open_scope();
            add_pattern_vars(pats);
            stmts.clear();
            while (token != BAR && token != RBRACE) {
                parseStatementList(stmts);
            }
            close_scope();

            if (stmts.size() == 0) {
                body = new ELNode.NULL(p3);
            } else if (stmts.size() == 1) {
                body = stmts.get(0);
            } else {
                body = new ELNode.COMPOUND(p3, to_a(stmts));
            }

            if (isdeflt) {
                deflt = body;
                break; // the else must be a last clause
            } else {
                cases.add(new ELNode.CASE(p2, to_a(pats), guard, body));
            }
        }

        if (args == null) {
            if (nargs == -1) nargs = 0;
            args = new ELNode[nargs];
            for (int i = 0; i < nargs; i++) {
                args[i] = new ELNode.IDENT(p, tempvar());
            }
        }

        ELNode.CASE[] alts = cases.toArray(new ELNode.CASE[cases.size()]);
        if (canOptimizeMatchExpression(alts)) {
            return new ELNode.CONST_MATCH(p, args, alts, deflt);
        } else {
            return new ELNode.MATCH(p, args, alts, deflt);
        }
    }

    private void add_pattern_vars(ELNode.Pattern pattern) {
        ((ELNode)pattern).accept(new DefaultVisitor() {
            public void visit(ELNode.DEFINE e) {
                if (!"_".equals(e.id)) {
                    env.putIfAbsent(e.id, e);
                }
            }
        });
    }

    private void add_pattern_vars(List<ELNode.Pattern> patterns) {
        ELNode.Visitor v = new DefaultVisitor() {
            public void visit(ELNode.DEFINE e) {
                if (!"_".equals(e.id)) {
                    env.putIfAbsent(e.id, e);
                }
            }
        };
        for (ELNode.Pattern pat : patterns) {
            ((ELNode)pat).accept(v);
        }
    }

    private boolean canOptimizeMatchExpression(ELNode.CASE[] alts) {
        // optimized if no variable patterns found
        final boolean[] optimized = {true};
        ELNode.Visitor v = new DefaultVisitor() {
            public void visit(ELNode.DEFINE e) {
                if (!"_".equals(e.id) || e.expr != null) {
                    optimized[0] = false;
                }
            }
        };

        for (ELNode.CASE b : alts) {
            if (b.patterns != null) {
                for (ELNode.Pattern p : b.patterns) {
                    ((ELNode)p).accept(v);
                    if (!optimized[0]) return false;
                }
            }
        }
        return true;
    }

    private static boolean isVariablePattern(Object pat) {
        // returns true if the pattern is a variable and the variable
        // doesn't have an associated as-pattern.
        return (pat instanceof ELNode.DEFINE) && ((ELNode.DEFINE)pat).expr == null;
    }

    private ELNode.Pattern parsePatternOpt() {
        try {
            return parsePattern();
        } catch (Exception ex) {
            return null;
        }
    }

    private ELNode.Pattern parsePattern() {
        ELNode pat = (ELNode)parseSubPattern();
        while (token == BAR) {
            pat = new ELNode.OR(scan(), pat, (ELNode)parseSubPattern());
        }
        return (ELNode.Pattern)pat;
    }
    
    private ELNode.Pattern parseSubPattern() {
        switch (token) {
        case IDENT: {
            String id = idValue;
            int p = scan();
            if (token == LPAREN) {
                scan();
                return parseConstructorPattern(p, id);
            } else {
                String type = parseTypeNameOpt();
                ELNode apat = scan(ATSIGN) ? (ELNode)parsePattern() : null;
                if (Character.isUpperCase(id.charAt(0)) && type == null && apat == null) {
                    return new ELNode.NEW(p, id, EMPTY_EXPS, null, null); // constructor
                } else {
                    return new ELNode.DEFINE(p, id, type, null, apat, true); // variable
                }
            }
        }

        case LAZY: {
            int p = scan();
            String id = idValue;
            expect(IDENT);
            String type = parseTypeNameOpt();
            return new ELNode.DEFINE(p, id, type, null, null, false);
        }

        case COLONCOLON:
            return new ELNode.CLASS(scan(), parseClassLiteral(false), null);
        
        case CHARVAL: {
            char val = charValue;
            return new ELNode.CHARVAL(scan(), val);
        }

        case NUMBER: {
            Number val = numberValue;
            return new ELNode.NUMBER(scan(), val);
        }

        case STRINGVAL: {
            String val = stringValue;
            return new ELNode.STRINGVAL(scan(), val);
        }

        case COLON: {
            if (Character.isJavaIdentifierStart((char)ch)) {
                scan();
                String id = idValue;
                return new ELNode.SYMBOL(scan(), Symbol.valueOf(id));
            } else if (ch == '\'' || ch == '"') {
                scan();
                String id = stringValue;
                return new ELNode.SYMBOL(scan(), Symbol.valueOf(id));
            } else {
                break;
            }
        }

        case DIV: {
            ELNode.REGEXP exp = new ELNode.REGEXP(pos, scanRegexp());
            scan();
            return exp;
        }

        case TRUE:
            return new ELNode.BOOLEANVAL(scan(), true);
        case FALSE:
            return new ELNode.BOOLEANVAL(scan(), false);
        case NULL:
            return new ELNode.NULL(scan());

        case ADD: {
            int p = scan();
            Number v = numberValue;
            expect(NUMBER);
            return new ELNode.NUMBER(p, v);
        }

        case SUB: {
            int p = scan();
            Number v = numberValue;
            expect(NUMBER);
            if (v instanceof BigInteger) {
                v = ((BigInteger)v).negate();
            } else if (v instanceof BigDecimal) {
                v = ((BigDecimal)v).negate();
            } else if (v instanceof Decimal) {
                v = ((Decimal)v).negate();
            } else if (v instanceof Rational) {
                v = ((Rational)v).negate();
            } else if (v instanceof Double) {
                v = -v.doubleValue();
            } else if (v instanceof Long) {
                v = -v.longValue();
            } else {
                v = -v.intValue();
            }
            return new ELNode.NUMBER(p, v);
        }

        case HASH: {
            int p = scan();
            ELNode e;
            if (token == IDENT) {
                e = new ELNode.IDENT(p, scanQName());
                expect(IDENT);
            } else {
                expect(LPAREN);
                e = parseExpression();
                expect(RPAREN);
            }
            return new ELNode.EXPR(p, e);
        }

        case NOT:
            return new ELNode.NOT(scan(), (ELNode)parsePattern());

        case LBRACKET:
            return parseListPattern(scan());
        case LPAREN:
            return parseTuplePattern(scan());
        case LBRACE:
            return parseMapPattern(scan());
        }

        throw parseError("Invalid pattern.");
    }

    private ELNode.Pattern parseListPattern(int p) {
        if (token == RBRACKET) {
            scan();
            return new ELNode.NIL(p);
        }

        ELNode e1 = (ELNode)parsePattern(), e2 = null;
        if (token == COMMA) {
            scan();
            e2 = (ELNode)parsePattern();
        }

        if (token == RANGE) {
            ELNode.RANGE r = parseRangeExpression(p, e1, e2);
            if (!r.isConstant())
                throw parseError(p, "invalid range pattern");
            return r;
        }

        List<ELNode> head = new ArrayList<ELNode>();
        ELNode tail = null;
        head.add(e1);
        if (e2 != null)
            head.add(e2);
        while (scan(COMMA))
            head.add((ELNode)parsePattern());
        if (scan(COLON))
            tail = (ELNode)parsePattern();
        else
            tail = new ELNode.NIL(p);
        expect(RBRACKET);

        for (int i = head.size(); --i >= 0; )
            tail = new ELNode.CONS(p, head.get(i), tail);
        return (ELNode.Pattern)tail;
    }

    private ELNode.Pattern parseTuplePattern(int p) {
        List<ELNode> elems = new ArrayList<ELNode>();
        if (token != RPAREN) {
            do {
                elems.add((ELNode)parsePattern());
            } while (scan(COMMA));
        }
        expect(RPAREN);
        return new ELNode.TUPLE(p, to_a(elems));
    }

    private ELNode.Pattern parseMapPattern(int p) {
        List<ELNode> keys = new ArrayList<ELNode>();
        List<ELNode> values = new ArrayList<ELNode>();

        do {
            if (token == IDENT) {
                keys.add(new ELNode.STRINGVAL(pos, idValue));
                scan();
            } else if (token == STRINGVAL) {
                keys.add(new ELNode.STRINGVAL(pos, stringValue));
                scan();
            } else {
                expect(IDENT);
                return null;
            }
            expect(COLON);
            values.add((ELNode)parsePattern());
        } while (scan(COMMA));

        expect(RBRACE);
        return new ELNode.MAP(p, to_a(keys), to_a(values));
    }

    private ELNode.Pattern parseConstructorPattern(int p, String id) {
        List<ELNode> args = new ArrayList<ELNode>();
        List<String> keys = new ArrayList<String>();

        if (token != RPAREN) {
            do {
                String key = parseArgumentName();
                if (key != null) {
                    if (keys.contains(key))
                        throw parseError(_T(EL_DUPLICATE_ARG_NAME, key));
                    keys.add(key);
                }
                args.add((ELNode)parsePattern());
            } while (scan(COMMA));
        }

        if (!keys.isEmpty() && keys.size() != args.size()) {
            throw parseError(p, "Missing parameter key");
        }

        expect(RPAREN);
        return new ELNode.NEW(p, id, to_a(args), (keys.isEmpty() ? null : to_a(keys)), null);
    }

    /**
     * Parse a try expression (statement).
     */
    private ELNode parseTryExpression(int p) {
        ELNode body;
        ELNode.DEFINE[] handlers = null;
        ELNode finalizer = null;

        expect(LBRACE);
        body = parseCompoundExpression(pos);
        expect(RBRACE);

        if (token == CATCH) {
            List<ELNode.DEFINE> hlist = new ArrayList<ELNode.DEFINE>();
            while (token == CATCH) {
                int p2 = scan();
                expect(LPAREN);
                String id = idValue; expect(IDENT);
                String type = parseTypeNameOpt();
                expect(RPAREN);
                open_scope();
                ELNode.DEFINE h = new_symbol(p2, id, type, null);
                expect(LBRACE);
                h.expr = parseCompoundExpression(pos);
                expect(RBRACE);
                close_scope();
                hlist.add(h);
            }
            handlers = to_a(hlist);
        }

        if (token == FINALLY) {
            scan();
            expect(LBRACE);
            finalizer = parseCompoundExpression(pos);
            expect(RBRACE);
        }

        if (handlers == null && finalizer == null) {
            if (token == EOI) {
                throw incomplete(_T(EL_TOKEN_EXPECTED, "catch"));
            } else {
                throw parseError(_T(EL_TOKEN_EXPECTED, "catch"));
            }
        }

        return new ELNode.TRY(p, body, handlers, finalizer);
    }

    /**
     * Parse a catch(exit) { ... exit(value) } expression.
     */
    private ELNode parseCatchExpression(int p) {
        expect(LPAREN);
        String var = idValue;
        expect(IDENT);
        expect(RPAREN);
        expect(LBRACE);
        ELNode body = parseCompoundExpression(pos);
        expect(RBRACE);
        return new ELNode.CATCH(p, var, body);
    }

    /**
     * Parse a synchronized(exp) { body... } statement.
     */
    private ELNode parseSynchronizedExpression(int p) {
        expect(LPAREN);
        ELNode exp = parseExpression();
        expect(RPAREN);
        expect(LBRACE);
        ELNode body = parseCompoundExpression(pos);
        expect(RBRACE);
        return new ELNode.SYNCHRONIZED(p, exp, body);
    }

    /**
     * Parse an assert statement.
     *      assert expression [, message]
     */
    @SuppressWarnings("ConstantConditions")
    private ELNode parseAssertExpression(int p) {
        ELNode exp = parseExpression(), msg = null;
        if (scan(COMMA)) msg = parseExpression();
        expect(SEMI);

        // generate assert statement only if assertion enabled
        boolean assertionEnabled = false;
        assert assertionEnabled = true;
        if (assertionEnabled) {
            return new ELNode.ASSERT(p, exp, msg);
        } else {
            return new ELNode.COMPOUND(p, EMPTY_EXPS);
        }
    }

    /**
     * Parse a top-level expression.
     */
    ELNode parseTopLevelExpression() {
        nextchar();

        int p = scan();
        ELNode e = parseLambdaExpressionOpt(p);
        if (e == null) {
            e = parseCompoundExpression(p);
        }
        if (token == EOI) {
            throw incomplete(_T(EL_TOKEN_EXPECTED, "}"));
        } else if (token != RBRACE) {
            throw parseError(_T(EL_TOKEN_EXPECTED, "}"));
        }
        return e;
    }

    /**
     * Parse the EL program.
     */
    void parseProgram(ELProgram prog) {
        // If the first line starts with '#!' then ignore this line
        if (nextchar() == '#' && lookahead(0) == '!') {
            do {
                nextchar();
            } while (ch != '\r' && ch != '\n' && ch != EOI);
        }

        scan();
        while (token != EOI) {
            if (token == ATSIGN) {
                mark(); scan();
                if (token == IDENT && "compile_time".equals(idValue)) {
                    int p = scan();
                    Object obj = executeCompileTimeProcessor();
                    if (obj instanceof Expression) {
                        ELNode exp = ((Expression)obj).getNode(p);
                        if (exp instanceof ELNode.COMPOUND) {
                            for (ELNode e : ((ELNode.COMPOUND)exp).exps) {
                                prog.addExpression(e);
                            }
                        } else {
                            prog.addExpression(exp);
                        }
                    }
                } else {
                    reset();
                    parseProgramElement(prog);
                }
            } else {
                parseProgramElement(prog);
            }
        }
    }

    void parseProgramElement(ELProgram prog) {
        switch (token) {
        case SEMI:
            scan();
            break;

        case REQUIRE: {
            scan();
            String file = stringValue;
            expect(STRINGVAL);
            expect(SEMI);
            parseScript(prog, file);
            break;
        }

        case IMPORT: {
            int p = scan();
            if (scan(STATIC)) {
                prog.addLibrary(parseClassLiteral(true));
            } else if (scan(MODULE)) {
                String prefix = null;
                if (token == IDENT) {
                    prefix = idValue;
                    mark(); scan();
                    if (!scan(COLON)) {
                        reset();
                        prefix = null;
                    }
                }
                prog.addModule(parseClassLiteral(false), prefix);
            } else {
                String name = parseClassLiteral(true);
                prog.addImport(name);
                if (!name.endsWith(".*")) {
                    // add a data class
                    String id = name.substring(name.lastIndexOf('.')+1);
                    String[] slots = null;
                    if (token == LPAREN) {
                        scan();
                        List<String> lst = new ArrayList<String>();
                        if (token != RPAREN) {
                            do {
                                lst.add(idValue);
                                expect(IDENT);
                            } while (scan(COMMA));
                        }
                        expect(RPAREN);
                        slots = to_a(lst);
                    }
                    ELNode.CLASS c = new ELNode.CLASS(p, name, slots);
                    prog.addExpression(new ELNode.DEFINE(p, id, null, null, c, true));
                }
            }
            expect(SEMI);
            break;
        }

        case GRAMMAR:
            scan();
            parseGrammar();
            break;

        case DEFINE:
        case PUBLIC:
        case PROTECTED:
        case PRIVATE:
        case ABSTRACT:
        case STATIC:
        case FINAL:
        case ATSIGN:
            for (ELNode.DEFINE e : parseDefinitions(parseMetaData())) {
                checkVar(e, true);
                prog.addExpression(e);
            }
            break;

        case CLASSDEF:
            prog.addExpression(parseClassDefinition(scan(), null));
            break;

        case UNDEF: {
            int p = scan();
            if (token == PREFIX || token == INFIX || token == KEYWORD) {
                String id = operator.name;
                discard_symbol(id);
                prog.addExpression(new ELNode.UNDEF(p, id));
                scan();
            } else {
                String id = scanQName();
                expect(IDENT);
                discard_symbol(id);
                prog.addExpression(new ELNode.UNDEF(p, id));
            }
            expect(SEMI);
            break;
        }

        case VOID:
            prog.addExpression(parseVoidExpression());
            expect(SEMI);
            break;

        case LBRACE: // lambda or map
            prog.addExpression(parseExpressionStatement());
            expect(SEMI);
            break;

        default:
            ELNode stmt = parseStatement();
            if (stmt instanceof ELNode.COMPOUND) {
                if (((ELNode.COMPOUND)stmt).exps.length != 0)
                    prog.addExpression(stmt);
            } else {
                prog.addExpression(stmt);
            }
            break;
        }
    }

    private Object executeCompileTimeProcessor() {
        ELProgram processor = new ELProgram();
        int line = Position.line(pos);

        // parse compile time processor
        open_scope();
        expect(LBRACE);
        while (token != EOI && token != RBRACE) {
            parseProgramElement(processor);
        }
        expect(RBRACE);
        close_scope();

        // execute compile time processor
        ELContext elctx = getParseContext().getELContext();
        return processor.execute(elctx, filename, line);
    }

    /*-------------------------------------------------------------------------*/

    private Map<String,Grammar> prefix_grammars = new HashMap<String,Grammar>();
    private Map<String,Grammar> infix_grammars  = new HashMap<String,Grammar>();

    // the evaluation context used by parser
    private EvaluationContext parse_context;

    private EvaluationContext getParseContext() {
        if (parse_context == null) {
            ELContext elctx = ELEngine.createELContext();
            ClassResolver.getInstance(elctx).addImport("elite.ast.*");
            MethodResolver.getInstance(elctx).addGlobalMethods(Expression.class);
            parse_context = new EvaluationContext(elctx);
        }

        return parse_context;
    }

    private void parseGrammar() {
        Grammar grammar = new GrammarParser(this).parse();

        Set<String> prefix_keys = grammar.getPrefixKeywords();
        Set<String> infix_keys = grammar.getInfixKeywords();

        if (prefix_keys.isEmpty() && infix_keys.isEmpty())
            throw parseError("the grammar must have at least one leading or infix keyword");

        for (String key : prefix_keys)
            prefix_grammars.put(key, grammar);

        for (String key : infix_keys)
            infix_grammars.put(key, grammar);
    }

    private ELNode matchPrefixGrammar() {
        String key;
        Grammar grammar;

        key = token == IDENT ? idValue : token == KEYWORD ? operator.name : null;
        if (key != null && (grammar = prefix_grammars.get(key)) != null) {
            int p = pos;
            Object result = grammar.parse(this);
            return result instanceof ELNode     ? (ELNode)result :
                   result instanceof Expression ? ((Expression)result).getNode(p)
                                                : Expression.CONST(result).getNode(p);
        }
        return null;
    }

    private ELNode matchInfixGrammar(ELNode exp) {
        String key;
        Grammar grammar;

        key = idValue != null ? idValue : token == KEYWORD ? operator.name : null;
        if (key != null && (grammar = infix_grammars.get(key)) != null) {
            int p = pos;
            Object result = grammar.parse_infix(this, exp);
            return result instanceof ELNode     ? (ELNode)result :
                   result instanceof Expression ? ((Expression)result).getNode(p)
                                                : Expression.CONST(result).getNode(p);
        }
        return null;
    }

    public void importSyntaxRules(Parser from) {
        prefix_grammars.putAll(from.prefix_grammars);
        infix_grammars.putAll(from.infix_grammars);
        lexer.importFrom(from.lexer);
    }

    private void parseScript(ELProgram prog, String path) {
        try {
            String script = readScript(path);
            Parser parser = new Parser(script);
            parser.setFileName(path);
            parser.setResourceResolver(resolver);
            parser.allowComment(true);
            parser.env = this.env;
            parser.parseProgram(prog);
            importSyntaxRules(parser);
        } catch (IOException ex) {
            throw parseError(ex.getMessage());
        }
    }

    private String readScript(final String path) throws IOException {
        String resname;
        Reader reader = null;

        // read script from file system
        try {
            if (!path.startsWith("/") && this.filename != null) {
                String base = filename.replace(File.separatorChar, '/');
                resname = base.substring(0, base.lastIndexOf('/')+1).concat(path);
            } else {
                resname = path;
            }

            if (resolver != null) {
                reader = resolver.open(resname);
            } else {
                reader = new InputStreamReader(new FileInputStream(resname), "UTF-8");
            }
        } catch (FileNotFoundException ex) {
            // fallthrough
        }

        // read script from system class loader
        if (reader == null) {
            if (path.indexOf('.') == -1) {
                resname = SCRIPT_PATH + path + SCRIPT_EXT;
            } else {
                resname = path.replace('.', '/') + SCRIPT_EXT;
            }

            InputStream stream = getClass().getClassLoader().getResourceAsStream(resname);
            if (stream != null) {
                reader = new InputStreamReader(stream, "UTF-8");
            }
        }

        if (reader != null) {
            return readScript(reader);
        } else {
            throw parseError(path + ": resource not found.");
        }
    }

    private String readScript(Reader reader) throws IOException {
        StringBuilder buf = new StringBuilder();
        char[] cbuf = new char[8192];
        for (int len; (len = reader.read(cbuf)) != -1; ) {
            buf.append(cbuf, 0, len);
        }
        reader.close();
        return buf.toString();
    }

    /**
     * Parse the EL program.
     */
    public ELProgram parse() {
        ELProgram prog = new ELProgram();
        allowComment(true);
        open_scope();
        parseProgram(prog);
        close_scope();
        return prog;
    }

    /**
     * Parse a string that contains static strings and expressions.
     */
    ELNode parseExpressionString(boolean embed) {
        List<ELNode> elems = new ArrayList<ELNode>();
        StringBuilder buf = new StringBuilder();
        int delimiter = 0;
        int p = pos;
        nextchar();
        while (ch != EOI) {
            if (ch == '$' || ch == '#') {
                int delim = ch;
                if (nextchar() == '{') { // begining of embedded expression
                    if (delimiter == 0) {
                        delimiter = delim;
                    } else if (delimiter != delim) {
                        throw parseError(_T(EL_MIXED_SYNTAX));
                    }
                    if (buf.length() > 0) {
                        elems.add(new ELNode.LITERAL(p, buf.toString()));
                        buf.setLength(0);
                    }
                    elems.add(parseTopLevelExpression());
                    p = pos;
                } else if (embed && Character.isJavaIdentifierStart((char)ch)) {
                    if (buf.length() > 0) {
                        elems.add(new ELNode.LITERAL(p, buf.toString()));
                        buf.setLength(0);
                    }
                    do {
                        buf.append((char)ch); // XXX buf reused
                    } while (Character.isJavaIdentifierPart((char)nextchar()));
                    elems.add(new ELNode.IDENT(pos, buf.toString()));
                    buf.setLength(0);
                    p = pos;
                } else {
                    buf.append((char)delim);
                    if (ch != EOI && ch != '$' && ch != '#') { // handle special case '$$' or '##'
                        buf.append((char)ch);
                        nextchar();
                    }
                }
            } else if (ch == '\\') {
                nextchar();
                if (ch != '$' && ch != '#' && ch != '\\') {
                    buf.append('\\');
                }
                if (ch != EOI) {
                    buf.append((char)ch);
                    nextchar();
                }
            } else {
                buf.append((char)ch);
                nextchar();
            }
        }
        if (buf.length() > 0) {
            elems.add(new ELNode.LITERAL(p, buf.toString()));
        }

        if (elems.size() == 0) {
            return new ELNode.LITERAL(0, "");
        } else if (elems.size() == 1) {
            return elems.get(0);
        } else {
            return new ELNode.Composite(0, to_a(elems));
        }
    }

    private static final SimpleCache<String,ELNode> cache = SimpleCache.make(5000);

    /**
     * Parse an expression.
     */
    public static ELNode parseExpression(String expression)
        throws ELException
    {
        Parser parser = new Parser(expression);
        parser.allowComment(false);
        parser.nextchar();
        parser.scan();
        ELNode e = parser.parseSyntaxExpression();
        parser.expect(EOI);
        return e;
    }

    /**
     * Parse an expression string.
     */
    public static ELNode parse(String expression)
        throws ELException
    {
        return parse(expression, false);
    }

    /**
     * Parse an expression string.
     */
    public static ELNode parse(String expression, boolean allowKeywords)
        throws ELException
    {
        ELNode e = cache.get(expression);
        if (e == null) {
            Parser parser = new Parser(expression);
            parser.allowComment(false);
            parser.allowKeywords(allowKeywords);
            e = parser.parseExpressionString(false);
            cache.put(expression, e);
        }
        return e;
    }
}
