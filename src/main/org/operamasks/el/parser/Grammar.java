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

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Map;
import java.io.Serializable;
import javax.el.ELContext;
import javax.el.ValueExpression;

import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.ELProgram;
import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.el.eval.closure.DelayClosure;
import org.operamasks.el.resolver.ClassResolver;
import org.operamasks.el.resolver.MethodResolver;
import elite.ast.Expression;
import elite.lang.Closure;
import elite.lang.Symbol;

class Grammar implements Serializable
{
    private static final long serialVersionUID = 1729022573853055909L;

    static final int MINTERM    = 1;    // Token values assigned to terminals start here.
    static final int MINNONTERM = 512;  // nonterminals start here
    static final int MINACT     = 1024; // acts start here

    /* Perdefined terminals. */
    static final int _EOI_       = 0;
    static final int _IDENT_     = 1;
    static final int _NUMBER_    = 2;
    static final int _STRING_    = 3;
    static final int _EXPR_      = 4;
    static final int _STMT_      = 5;
    static final int MINUSERTERM = 6;

    /* Maximum numeric values used for terminals and nonterminals (MAXTERM and
     * MINTERM), as well as the maximum number of terminals and nonterminals
     * (NUMTERMS and NUMNONTERMS). Finally, USED_TERMS and USED_NONTERMS are
     * the number of these actually in use (i.e. were declared in the input file).
     */
    static final int MAXTERM     = MINNONTERM - 2;
    static final int MAXNONTERM  = MINACT - 1;

    static final int NUMTERMS    = ((MAXTERM-MINTERM)+1);
    static final int NUMNONTERMS = ((MAXNONTERM-MINNONTERM)+1);

    static final int MAXPROD    = 512;  // Maximum number of productions in the input grammar

    /* 语义动作 */
    static class YYACT implements Serializable {
        ELNode   action;        // 执行语义动作的代码块
        String[] ids;           // 向代码块传递的右部符号参数名
        boolean  trans;         // 为true时转换AST, 为false时执行代码块
        short    optflg;        // 表构造参数
    }

    static final short OPT_LIST_HEAD = 1;
    static final short OPT_LIST_CONS = 2;

    /* 词法元素 */
    static class YYSTOK implements Serializable
    {
        String  name;           // 词法元素名
        String  value;          // 词法元素值
        int     pos;            // 源文件位置
        boolean rule;           // true: 以正则表达式表示的词法规则;
                                // false: 原始字符串

        public YYSTOK(String value, int pos, boolean rule) {
            this.value = value;
            this.pos   = pos;
            this.rule  = rule;
        }

        public int hashCode() {
            int hash = value.hashCode();
            if (rule) hash = ~hash;
            return hash;
        }

        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (!(obj instanceof YYSTOK))
                return false;
            YYSTOK other = (YYSTOK)obj;
            return value.equals(other.value) && rule == other.rule;
        }

        public String toString() {
            return rule ? "/" + value + "/" : "<" + value + ">";
        }
    }

    // 内部分析器
    private transient Parser S;

    // 内部分析器所使用的运算符管理器
    private transient Lexer original;

    // 当前文法所使用的运算符管理器
    private transient Lexer current;

    // 初始代码块
    List<ELProgram> blocks;

    // 前导及中缀关键字
    Set<String> prefix_keys, infix_keys;

    // 词法宏片段, 用于构造词法分析器
    Map<String,String> fragments;

    // 超前查看符号
    private transient int yy_lookahead;

    /* Constants used in the tables. Note that the parsing algorithm assumes that
     * the start state is State 0. Consequently, since the start state is shifted
     * only once when we start up the parser, we can use 0 to signify an accept.
     * This is handy in pratice because an accept is, by definition, a reduction
     * into the start state. Consequently, a YYR(0) in the parse table represents an
     * accepting action and the table-generation code doesn't have to treat the
     * accepting action any differently than a normal reduct.
     */

    static final int YY_ACCEPT = 0;                 // 接受动作
    static final int YY_FAIL   = Integer.MAX_VALUE; // 失败动作

    // 状态栈和值栈的最大深度
    static final int YYMAXDEPTH = 256;

    // 状态栈及值栈
    private transient int[]     yy_stack = new int[YYMAXDEPTH];
    private transient Closure[] yy_vstack = new Closure[YYMAXDEPTH];
    private transient int       yy_sp;

    // 动作返回值
    private transient Closure yy_val;

    // 终结符词素
    private transient Closure yy_lval;

    /* Yy_stok is used for operator declaration. It is indexed by the internal
     * value used for a token (as used for a column index in the transition matrix)
     * and evaluates to a string naming that token.
     */
    YYSTOK[][] yy_stok;

    /* The yy_action table is action part of the LALR(1) transition matrix. It's
     * compressed and can be accessed using the yy_next() subroutine, below.
     *
     *             yya000[]={       5,3    ,    2,2    ,    1,1    };
     * state number ---+            | |
     * input symbol (terminal)------+ |
     * action-------------------------+
     *
     * action = yy_next(yy_action, cur_state, lookahead_symbol);
     *
     *     action <  0   -- Reduce by production n
     *     action == 0   -- Accept (ie. reduce by production 0)
     *     action >  0   -- Shift to state n
     *     action == YYF -- error
     */
    int[][] yy_action;

    /* The yy_goto table is goto part of the LALR(1) transition matrix. It's
     * compressed and can be accessed using the yy_next() subroutine, declared below.
     *
     *             yyg000[]={       5,3    ,    2,2    ,    1,1    };
     * uncovered state-+            | |
     * nonterminal------------------+ |
     * goto this state----------------+
     * goto_state = yy_next(yy_goto, cur_state, nonterminal);
     */
    int[][] yy_goto;

    /* The yy_lhs array is used for reductions. It is indexed by production number
     * and holds the associated left-hand side, adjusted so that the number can be
     * used as an index into yy_goto.
     */
    int[] yy_lhs;

    /* The yy_reduce[] array is indexed by production number and holds
     * the number of symbols on the right hand side of the production.
     */
    int[] yy_reduce;

    // 动作表, 规约时调用动作代码
    YYACT[] yy_acts;

    // 调用动作代码所使用的环境
    private transient EvaluationContext env;

    // 用于错误恢复的状态信息
    private transient int[]       xx_stack = new int[YYMAXDEPTH];
    private transient Closure[]   xx_vstack = new Closure[YYMAXDEPTH];
    private transient int         xx_sp;
    private transient Closure     xx_val;
    private transient Closure     xx_lval;
    private transient Scanner     state;

    /*------------------------------------------------------------------------*/

    public Set<String> getPrefixKeywords() {
        return prefix_keys;
    }

    public Set<String> getInfixKeywords() {
        return infix_keys;
    }

    /* Next-state routine for the compressed tables. Given current state and
     * input symbol (inp), return next state.
     */
    private int yy_next(int[][] table, int cur_state, int inp) {
        int[] p = table[cur_state];

        if (p != null)
            for (int i = 0, n = p.length; i < n; i += 2)
                if (inp == p[i])
                    return p[i+1];
        return YY_FAIL;
    }

    private int yy_next_action(int inp) {
        return yy_next(yy_action, yy_stack[yy_sp], inp);
    }

    private void yy_shift(int new_state) {
        yy_stack [--yy_sp] = new_state;
        yy_vstack[  yy_sp] = yy_lval;
    }

    private void yy_reduce(int prod_num, int amount) {
        int next_state;

        // Pop n items off the state stack and the value stack
        yy_sp  += amount;

        next_state = yy_next(yy_goto, yy_stack[yy_sp], yy_lhs[prod_num]);

        // Push next state
        yy_stack [--yy_sp] = next_state;
        yy_vstack[  yy_sp] = yy_val;
    }

    private void yy_init_stack() {
        if (yy_stack == null) {
            yy_stack  = new int[YYMAXDEPTH];
            yy_vstack = new Closure[YYMAXDEPTH];
            xx_stack  = new int[YYMAXDEPTH];
            xx_vstack = new Closure[YYMAXDEPTH];
        }

        yy_sp = YYMAXDEPTH - 1;
        yy_stack [yy_sp] = 0;
        yy_vstack[yy_sp] = null;

        yy_val = null;
        yy_lval = null;
    }

    private void yy_init_occs(Parser parser) {
        this.S        = parser;
        this.state    = S.save();
        this.original = S.lexer;

        if (current == null) {
            LexBuilder builder = new LexBuilder();
            for (Map.Entry<String,String> e : fragments.entrySet())
                builder.add_macro(e.getKey(), e.getValue());
            for (int i = 0; i < yy_stok.length; i++) {
                if (yy_stok[i] != null)
                    for (YYSTOK stok : yy_stok[i])
                        if (stok.rule)
                            builder.add_rule(stok.value, stok.pos, i);
                        else
                            builder.add_str(stok.value, stok.pos, i);
            }
            current = builder.getLexer();
        }

        S.lexer = current;
        if (S.token == Token.KEYWORD)
            S.rescan();

        ELContext elctx = ELEngine.createELContext();
        ClassResolver.getInstance(elctx).addImport("elite.ast.*");
        MethodResolver.getInstance(elctx).addGlobalMethods(Expression.class);
        env = new EvaluationContext(elctx);

        if (blocks != null) {
            for (ELProgram prog : blocks) {
                prog.execute(elctx);
            }
        }
    }

    private void yy_restore_occs() {
        S.lexer = original;
        S.rescan();
    }

    /**
     * General-purpose LALR parser.
     */
    public Object parse(Parser parser) {
        yy_init_stack();
        yy_init_occs(parser);

        yy_save();
        yy_lookahead = yy_nexttoken();

        return do_parse();
    }

    public Object parse_infix(Parser parser, ELNode expr) {
        yy_init_stack();
        yy_init_occs(parser);

        yy_lookahead = _EXPR_;
        yy_lval = new ExpressionClosure(expr);

        return do_parse();
    }

    private Object do_parse() {
        int act_num;
        int rhs_len;

        while (true) {
            act_num = yy_next_action(yy_lookahead);

            if (act_num == YY_FAIL) { // error recovery
                int tok = yy_lookahead;
                if (yy_lookahead != _EOI_) {
                    yy_restore();
                    act_num = yy_next_action(yy_lookahead = _EOI_);
                }
                if (act_num == YY_FAIL) {
                    yy_error(tok); // no return
                    return null;
                }
            }

            if (act_num > 0) {
                yy_shift(act_num);
                yy_save();
                yy_lookahead = yy_nexttoken();
            } else {
                act_num = -act_num;
                rhs_len = yy_reduce[act_num];
                yy_val  = rhs_len != 0 ? yy_vstack[yy_sp] : null;

                yy_act(act_num);

                if (act_num == YY_ACCEPT)
                    break;
                else
                    yy_reduce(act_num, rhs_len);
            }
        }

        yy_restore_occs();

        if (yy_val != null) {
            if (yy_val instanceof ExpressionClosure) {
                return ((ExpressionClosure)yy_val).getNode();
            } else {
                return yy_val.getValue(env.getELContext());
            }
        }

        return null;
    }

    private void yy_error(int tok) {
        String errtok, errmsg;

        if (yy_stok[tok] != null) {
            errtok = yy_stok[tok][0].name; // FIXME
        } else if (yy_lval instanceof LiteralClosure) {
            errtok = yy_lval.getValue(null).toString();
        } else {
            errtok = null;
        }

        if (errtok != null) {
            errmsg = "syntax error, unexpected " + errtok;
        } else {
            errmsg = "syntax error";
        }

        throw S.parseError(errmsg);
    }

    private int yy_nexttoken() {
        Operator op;

        yy_lval = null;

        if (S.token == Token.EOI) {
            return _EOI_;
        }

        if (S.operator != null) {
            op = S.operator;
        } else if (S.idValue != null) {
            op = S.getOperator(S.idValue);
        } else {
            op = null;
        }

        if (op != null && op.token == Token.LALR) {
            if (op.token2 == _EXPR_) {
                yy_lval = new ExpressionClosure(parse_embed_expression());
                return _EXPR_;
            } else {
                S.scan();
                yy_lval = new LiteralClosure(op.name);
                return op.token2;
            }
        }

        if (S.token == Token.IDENT && yy_next_action(_IDENT_) != YY_FAIL) {
            // The current production accepts an identifier
            yy_lval = new LiteralClosure(Symbol.valueOf(S.idValue));
            S.scan();
            return _IDENT_;
        }

        if (S.token == Token.STRINGVAL && yy_next_action(_STRING_) != YY_FAIL) {
            // The current production accepts a string constant
            yy_lval = new LiteralClosure(S.stringValue);
            S.scan();
            return _STRING_;
        }

        if (S.token == Token.NUMBER && yy_next_action(_NUMBER_) != YY_FAIL) {
            // The current production accepts a number constant
            yy_lval = new LiteralClosure(S.numberValue);
            S.scan();
            return _NUMBER_;
        }

        if (yy_next_action(_EXPR_) != YY_FAIL) {
            // The current production accepts a subexpression
            yy_lval = new ExpressionClosure(parse_subexpression());
            return _EXPR_;
        }

        if (yy_next_action(_STMT_) != YY_FAIL) {
            // The current production accepts a statement
            yy_lval = new ExpressionClosure(parse_statement());
            return _STMT_;
        }

        return _EOI_;       // mark the end of input
    }

    private ELNode parse_embed_expression() {
        S.lexer = original;
        S.scan();
        ELNode exp = S.parseExpression();
        S.lexer = current;
        S.expect(Token.RPAREN);
        return exp;
    }

    private ELNode parse_subexpression() {
        S.lexer = original;
        S.rescan();
        ELNode exp = S.parseExpression();
        S.lexer = current;
        S.rescan();
        return exp;
    }

    private ELNode parse_statement() {
        S.lexer = original;
        S.rescan();
        ELNode stmt = S.parseStatement();
        S.lexer = current;
        S.rescan();
        return stmt;
    }
    
    private void yy_act(int prod_num) {
        YYACT act = yy_acts[prod_num];

        if (act != null) {
            EvaluationContext ctx = env.pushContext();
            ELContext elctx = ctx.getELContext();
            int rhs_len = act.ids.length;

            for (int i = 0; i < rhs_len; i++) {
                if (act.ids[i] != null) {
                    Closure val = yy_vstack[yy_sp+rhs_len-i-1];
                    if (val == null)
                        val = new LiteralClosure(null);
                    ctx.setVariable(act.ids[i], val);
                }
            }

            if (act.action != null) {
                if (act.trans) {
                    TreeTransformer trans = new GrammarTransformer(ctx);
                    yy_val = new ExpressionClosure(trans.transform(act.action));
                } else {
                    yy_val = act.action.closure(ctx);
                    yy_val.getValue(elctx); // force the side effect
                }
            } else {
                yy_val = null;
            }

            if (act.optflg != 0) {
                yy_val = make_list(elctx, act, rhs_len);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Closure make_list(ELContext elctx, YYACT act, int rhs_len) {
        /* s : A (B)* C;       s  : A Bs C
         *                     Bs : Bs B          { Bs ++ B }
         *                        | <epsilon>     { [] }
         *
         * S : A (B)+ C;       s  : A Bs C
         *                     Bs : Bs B          { Bs ++ B }
         *                        | B             { [B] }
         */

        if (act.optflg == OPT_LIST_HEAD)
        {
            List list = new ArrayList();
            if (yy_val != null) {
                list.add(yy_val.getValue(elctx));           // action in B
            } else if (rhs_len != 0) {
                Closure e = yy_vstack[yy_sp];               // B
                list.add((e != null) ? e.getValue(elctx) : null);
            }
            return new LiteralClosure(list);
        }
        else if (act.optflg == OPT_LIST_CONS)
        {
            Closure lhs = yy_vstack[yy_sp+rhs_len-1];       // Bs
            List list = (List)lhs.getValue(elctx);
            if (yy_val != null) {
                list.add(yy_val.getValue(elctx));           // action in B
            } else {
                Closure e = yy_vstack[yy_sp];               // B
                list.add((e != null) ? e.getValue(elctx) : null);
            }
            return lhs;
        }

        return null;
    }

    static class GrammarTransformer extends TreeTransformer {
        private EvaluationContext ctx;

        GrammarTransformer(EvaluationContext ctx) {
            this.ctx = ctx;
        }

        public void visit(ELNode.IDENT e) {
            ValueExpression ve;
            Object v;

            if (e.id.startsWith("$")) { // transform to string name of the identifier
                ve = ctx.resolveVariable(e.id.substring(1));
                if (ve != null && !(ve instanceof ExpressionClosure)) {
                    v = ve.getValue(ctx.getELContext());
                    if (v instanceof Symbol) {
                        result = new ELNode.STRINGVAL(e.pos, ((Symbol)v).getName());
                        return;
                    }
                }
                result = e;
            } else {
                ve = ctx.resolveVariable(e.id);
                if (ve == null) {
                    result = e;
                } else if (ve instanceof ExpressionClosure) {
                    result = ((ExpressionClosure)ve).getNode();
                } else {
                    v = ve.getValue(ctx.getELContext());
                    if (v instanceof Expression) {
                        result = ((Expression)v).getNode(e.pos);
                    } else if (v instanceof Symbol) {
                        result = new ELNode.IDENT(e.pos, ((Symbol)v).getName());
                    } else {
                        result = Expression.CONST(v).getNode(e.pos);
                    }
                }
            }
        }

        public String transform(String id) {
            if (id != null) {
                ValueExpression ve = ctx.resolveVariable(id);
                if (ve != null && !(ve instanceof ExpressionClosure)) {
                    Object value = ve.getValue(ctx.getELContext());
                    if (value instanceof Symbol) {
                        id = ((Symbol)value).getName();
                    }
                }
            }
            return id;
        }
    }

    static class ExpressionClosure extends DelayClosure {
        private ELNode node;
        private Expression expr;

        ExpressionClosure(ELNode node) {
            this.node = node;
        }

        public ELNode getNode() {
            return node;
        }

        protected Object force(ELContext elctx) {
            if (expr == null)
                expr = Expression.valueOf(node);
            return expr;
        }

        protected void forget() {
            // do nothing
        }
    }

    private void yy_save() {
        System.arraycopy(yy_stack, yy_sp, xx_stack, yy_sp, YYMAXDEPTH - yy_sp);
        System.arraycopy(yy_vstack, yy_sp, xx_vstack, yy_sp, YYMAXDEPTH - yy_sp);

        xx_sp   = yy_sp;
        xx_val  = yy_val;
        xx_lval = yy_lval;

        S.save(state);
    }

    private void yy_restore() {
        System.arraycopy(xx_stack, xx_sp, yy_stack, xx_sp, YYMAXDEPTH - xx_sp);
        System.arraycopy(xx_vstack, xx_sp, yy_vstack, xx_sp, YYMAXDEPTH - xx_sp);

        yy_sp   = xx_sp;
        yy_val  = xx_val;
        yy_lval = xx_lval;

        S.lexer = original;
        S.restore(state);
        S.rescan();
    }
}
