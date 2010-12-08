/*
 * $Id: GrammarParser.java,v 1.12 2010/03/18 17:37:34 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.parser;

import java.util.BitSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.lang.reflect.Array;

import org.operamasks.el.eval.ELProgram;
import static org.operamasks.el.parser.Grammar.*;
import static org.operamasks.el.parser.Token.*;
import static java.lang.String.format;

public class GrammarParser
{
    /**
     * SYMBOL structure (used for symbol table).
     */
    static class SYMBOL {
        String      name;               // symbol name
        Set<YYSTOK> literals;           // termainal token literals
        int         val;                // numberic value of symbol
        int         level;              // relative precedence, 0=none, 1=lowest
        char        assoc;              // associativity: 'l'=left,'r'=right,'\0'=none
        int         used;               // symbol used on an rhs
        int         set;                // symbol defined
        ELNode      action;             // action node
        boolean     trans;              // transformer node or code node
        short       optflg;             // list construction flags
        PRODUCTION  productions;        // right-hand sides if nonterm
        BitSet      first;              // FIRST set
    }

    /**
     * PRODUCTION structure. Represents right-hand sides.
     */
    static class PRODUCTION {
        int         num;                // production number
        PROD_SYM[]  rhs=new PROD_SYM[8];// Tokenized right-hand side
        SYMBOL      lhs;                // Left-hand side
        int         rhs_len;            // # of elements in the rhs[]
        int         non_act;            //        "      that are not actions
        PRODUCTION  next;               // pointer to next production
                                        // for this left-hand side
        int         prec;               // Relative precedence
    }

    static class PROD_SYM {
        String      id;                 // The identifier of rhs element
        SYMBOL      sym;                // the rhs symbol

        PROD_SYM(String id, SYMBOL sym) {
            this.id = id; this.sym = sym;
        }
    }

    /* These macros evaluate to true if x represents a terminal
     * (isterm), nonterminal (isnonterm) or action (isact)
     */
    static boolean isterm(SYMBOL x) {
        return MINTERM <= x.val && x.val <= MAXTERM;
    }

    static boolean isnonterm(SYMBOL x) {
        return MINNONTERM <= x.val && x.val <= MAXNONTERM;
    }

    static boolean isact(SYMBOL x) {
        return MINACT <= x.val;
    }

    private boolean nullable(SYMBOL sym) {
        return isnonterm(sym) && sym.first.get(EPSILON());
    }

    /* The following macros are used to adjust the nontermal values so that
     * the smallest nonterminal is zero. (You need to do this when you output
     * the tables). adj_val does the adjustment, unadj_val translaes the
     * adjust value back to the original value.
     */
    static int adj_val(int x)   { return x - MINNONTERM; }
    static int unadj_val(int x) { return x + MINNONTERM; }

    /* This array is indexed by terminal or nonterminal value and evaluates
     * to a pointer to the equivalent symbol-table entry.
     */
    private SYMBOL[] _terms = new SYMBOL[MINACT];

    /* The table that maintains literal terminal symbols. */
    private Map<YYSTOK,String> _literals = new HashMap<YYSTOK,String>(157);
    /* The table that maintains terminal symbols. */
    private Map<String,SYMBOL> _termtab = new HashMap<String,SYMBOL>(157);
    /* The table that maintains nonterminal symbols. */
    private Map<String,SYMBOL> _nontermtab = new HashMap<String,SYMBOL>(157);

    /* Pointer to symbol-table entry for the start (goal) symbol. */
    private SYMBOL _goal_symbol;

    /* if true, the next nonterminal is the goal symbol. */
    private boolean _goal_symbol_is_next = true;

    private int _cur_term        = MINUSERTERM-1; // Current terminal
    private int _cur_nonterm     = MINNONTERM-1;  // "       nonterminal
    private int _cur_act         = MINACT-1;      // "       action
    private int _num_productions = 0;             // Number of productions

    /* Initialization code blocks */
    private List<ELProgram> _code_blocks = new ArrayList<ELProgram>();

    /* Lexical fragments */
    private Map<String,String> _fragments = new HashMap<String,String>(17);

    /* Epsilon's value is one more than the largest terminal actually used.
    * We can get away with this only because EPSILON is not used until
    * after all the terminals have been entered into the symbol table.
    */
    private int EPSILON() { return _cur_term +1; }

    private Parser S;      // The input scanner

    /* Tokens */
    static final int LEFT_SPEC      = MAX_TOKEN + 1;
    static final int RIGHT_SPEC     = LEFT_SPEC + 1;
    static final int NONASSOC_SPEC  = RIGHT_SPEC + 1;
    static final int PREC_SPEC      = NONASSOC_SPEC + 1;
    static final int FRAGMENT       = PREC_SPEC + 1;
    static final int OPTIONAL       = FRAGMENT + 1;
    static final int STAR_CLOSE     = OPTIONAL + 1;
    static final int PLUS_CLOSE     = STAR_CLOSE + 1;
    static final int CODE_BLOCK     = PLUS_CLOSE + 1;
    static final int TILDE          = CODE_BLOCK + 1;
    static final int ACCENT         = TILDE + 1;

    /** Constructor */
    public GrammarParser(Parser s) {
        this.S = s;
    }

    /*-----------------------------------------------------------------------*/
    /* A recursive-descent parser for grammar. */

    public Grammar parse() {
        grammar();                  // Parse the input grammar
        problems();                 // Find problems in the input grammar
        first();                    // Find FIRST sets
        patch();                    // Patch up the grammar
        return make_grammar();      // Generate parse tables
    }

    private static final Lexer _grammar_lexer = mk_lexer();
    private Lexer _original_lexer;

    private static Lexer mk_lexer() {
        DefaultLexer l = new DefaultLexer();
        l.addOperator("%left",      LEFT_SPEC,      -1);
        l.addOperator("%right",     RIGHT_SPEC,     -1);
        l.addOperator("%nonassoc",  NONASSOC_SPEC,  -1);
        l.addOperator("%prec",      PREC_SPEC,      -1);
        l.addOperator("%fragment",  FRAGMENT,       -1);
        l.addOperator(":",          COLON,          -1);
        l.addOperator("|",          BAR,            -1);
        l.addOperator("#",          HASH,           -1);
        l.addOperator("@",          ATSIGN,         -1);
        l.addOperator("~",          TILDE,          -1);
        l.addOperator("^",          ACCENT,         -1);
        l.addOperator("/",          DIV,            -1);
        l.addOperator("=",          ASSIGN,         -1);
        l.addOperator("(",          LPAREN,         -1);
        l.addOperator(")",          RPAREN,         -1);
        l.addOperator(")?",         OPTIONAL,       -1);
        l.addOperator(")*",         STAR_CLOSE,     -1);
        l.addOperator(")+",         PLUS_CLOSE,     -1);
        l.addOperator("{",          LBRACE,         -1);
        l.addOperator("}",          RBRACE,         -1);
        l.addOperator("%{",         CODE_BLOCK,     -1);
        l.addOperator("->",         XFORM,          -1);
        l.addOperator(";",          SEMI,           -1);
        return l;
    }

    private void grammar() {
        // save operator manager and use grammar operator manager
        _original_lexer = S.lexer;
        S.lexer = _grammar_lexer;

        // add predefined terminals
        for (int i = 0; i < MINUSERTERM; i++) {
            _terms[i] = new SYMBOL();
            _terms[i].val = i;
        }

        // parse grammar
        S.expect(LBRACE);
        definitions();
        body();

        // restore operator manager
        S.lexer = _original_lexer;
        S.expect(RBRACE);
    }

    private void definitions() {
        do {
            switch (S.token) {
            case LEFT_SPEC:
                S.scan();
                new_lev('l');
                pnames();
                break;

            case RIGHT_SPEC:
                S.scan();
                new_lev('r');
                pnames();
                break;

            case NONASSOC_SPEC:
                S.scan();
                new_lev('n');
                pnames();
                break;

            case FRAGMENT:
                S.scan();
                fragment();
                break;
                
            case CODE_BLOCK:
                ELProgram prog = new ELProgram();
                S.lexer = _original_lexer;
                S.scan();
                while (S.token != EOI && S.token != RBRACE)
                    S.parseProgramElement(prog);
                S.lexer = _grammar_lexer;
                S.expect(RBRACE);
                _code_blocks.add(prog);
                break;

            default:
                return;
            }
        } while (true);
    }

    private void pnames() {
        while (true) {
            if (S.token == STRINGVAL) {
                SYMBOL sym = make_literal(new YYSTOK(S.stringValue, S.pos, false));
                S.scan();
                prec_list(sym.name);
            } else if (S.token == IDENT) {
                String id = S.idValue;
                S.mark(); S.scan();
                if (S.token == COLON) { // lookahead for production
                    S.reset();
                    break;
                }
                prec_list(id);
            } else {
                break;
            }
        }
    }

    private void fragment() {
        if (S.token == LBRACE) {
            S.scan();
            while (S.token != RBRACE) {
                new_macro();
            }
            S.scan();
        } else {
            new_macro();
        }
    }

    private void new_macro() {
        String name = S.idValue;
        S.expect(IDENT);
        String text = S.stringValue;
        S.expect(STRINGVAL);
        _fragments.put(name, text);
    }

    private void body() {
        while (S.token != RBRACE && S.token != EOI) {
            if (S.token == FRAGMENT) {
                S.scan();
                fragment();
            } else {
                String name = S.idValue;
                S.expect(IDENT);
                S.expect(COLON);
                if (is_token(name)) {
                    term_rhs(name);
                } else {
                    new_nonterm(name, true);
                    nonterm_rhs();
                }
                S.expect(SEMI);
            }
        }
    }

    private void term_rhs(String name) {
        SYMBOL term = make_term(name);
        term.set = S.pos;

        do {
            if (S.token == STRINGVAL) {
                String value = S.stringValue;
                add_term_rhs(term, new YYSTOK(value, S.pos, false));
                S.scan();
            } else if (S.token == DIV) {
                String value = S.scanRegexp();
                add_term_rhs(term, new YYSTOK(value, S.pos, true));
                S.scan();
            } else {
                S.expect(STRINGVAL);
            }
        } while (S.scan(BAR));
    }

    private void nonterm_rhs() {
        do {
            new_rhs();
            rhs();
        } while (S.scan(BAR));
    }

    private void rhs() {
        String id;

        while (true) {
            switch (S.token) {
            case IDENT:         // id or nontermainl
                id = S.idValue;
                S.mark(); S.scan();
                if (id.startsWith("$")) {
                    add_to_rhs(id.substring(1), _terms[_IDENT_]);
                } else if (S.token == COLON) {
                    // the production may end with a newline
                    S.reset();
                    return;
                } else if (S.token == ASSIGN) {
                    S.scan();
                    switch (S.token) {
                    case IDENT:
                        add_token(id, S.idValue);
                        S.scan();
                        break;
                    case STRINGVAL:
                        add_to_rhs(id, make_literal(new YYSTOK(S.stringValue, S.pos, false)));
                        S.scan();
                        break;
                    case DIV:
                        add_to_rhs(id, make_literal(new YYSTOK(S.scanRegexp(), S.pos, true)));
                        S.scan();
                        break;
                    case LPAREN:
                        S.scan();
                        opt(id);
                        break;
                    case LBRACE:
                        action(id);
                        break;
                    default:
                        S.expect(IDENT);
                        return;
                    }
                } else {
                    add_token(id, id);
                }
                break;

            case STRINGVAL:         // literal terminal
                add_to_rhs(null, make_literal(new YYSTOK(S.stringValue, S.pos, false)));
                S.scan();
                break;

            case DIV:               // regular expression terminal
                add_to_rhs(null, make_literal(new YYSTOK(S.scanRegexp(), S.pos, true)));
                S.scan();
                break;

            case ACCENT:            // string terminal
                S.scan();
                id = S.idValue;
                S.expect(IDENT);
                add_to_rhs(id, _terms[_STRING_]);
                break;

            case TILDE:             // number terminal
                S.scan();
                id = S.idValue;
                S.expect(IDENT);
                add_to_rhs(id, _terms[_NUMBER_]);
                break;
            
            case HASH:              // subexpression
                S.scan();
                id = S.idValue;
                S.expect(IDENT);
                add_to_rhs(id, _terms[_EXPR_]);
                break;

            case ATSIGN:            // substatement
                S.scan();
                id = S.idValue;
                S.expect(IDENT);
                add_to_rhs(id, _terms[_STMT_]);
                break;

            case PREC_SPEC:         // precedence specification
                String tok;
                S.scan();
                if (S.token == STRINGVAL) {
                    tok = _literals.get(new YYSTOK(S.stringValue, S.pos, false));
                    if (tok == null)
                        throw S.parseError(format("%s undefined", S.stringValue));
                    S.scan();
                } else {
                    tok = S.idValue;
                    S.expect(IDENT);
                }
                prec(tok);
                break;

            case LPAREN:            // start of optional subexpression
                S.scan();
                opt(null);
                break;

            case LBRACE:            // action
                action(null);
                break;

            case XFORM:             // transformer
                transformer(null);
                return; // The transformer must at end of production

            case FRAGMENT:  // the fragment may appear in the body
                return;

            default:
                return;
            }
        }
    }

    private void opt(String id) {
        start_opt();
        nonterm_rhs();

        switch (S.token) {
          case RPAREN: case OPTIONAL: case STAR_CLOSE: case PLUS_CLOSE:
            end_opt(id, S.token);
            S.scan();
            break;
          default:
            S.expect(RPAREN);
            break;
        }
    }

    private void action(String id) {
        ELNode node;

        S.lexer = _original_lexer;
        node = S.parseCompoundExpression(S.scan());
        S.lexer = _grammar_lexer;
        S.expect(RBRACE);

        add_action(id, node, false, (short)0);
    }

    private void transformer(String id) {
        ELNode node;

        S.lexer = _original_lexer;
        S.scan();
        if (S.token == LBRACE) {
            node = S.parseCompoundExpression(S.scan());
            S.lexer = _grammar_lexer;
            S.expect(RBRACE);
        } else {
            node = S.parseExpression();
            S.lexer = _grammar_lexer;
            S.rescan();
        }

        add_action(id, node, true, (short)0);
    }

    /*--------------------------------------------------------------------------------*/
    /* Action routines. These build up the symbol table from the input specification. */

    private char _associativity; // Current associativity direction.
    private int _prec_lev = 0;   // Precedence level. Incremented
                                 // after finding %left, etc.,
                                 // but before the names are done.

    /* The following stuff (that's a technical term) is used for processing
     * nested optinal (or repeating) productions defined with the [] and []*
     * operators. A stack is kept and, every time you go down another layer
     * of nesting, current nonterminal is stacked and an new nonterminal is
     * allocated. The previous state is restored when you're done with a level.
     */
    static class CUR_SYM {
        String      lhs_name;   // Name associated with left-hand side.
        SYMBOL      lhs;        // Pointer to symbol-table entry for
                                //      the current left-hand side.
        PRODUCTION  rhs;        // Pointer to current production.
        CUR_SYM     next;       // Pointer to next element in the stack

        CUR_SYM(CUR_SYM next) { this.next = next; }
    }

    private CUR_SYM _sp = new CUR_SYM(null);

    /* Problems() and find_problems work together to find unused symbols and
     * symbols that are used but not defined.
     */
    private void problems() {
        for (SYMBOL sym : _termtab.values()) {
            if (sym.used == 0)
                System.err.printf("warning: <%s> not used (defined on line %d)\n",
                                  sym.name, Position.line(sym.set));

            if (sym.set == 0)
                throw S.parseError(format("<%s> not defined (used on line %d)",
                                   sym.name, Position.line(sym.used)));
        }

        for (SYMBOL sym : _nontermtab.values()) {
            if (sym.used == 0 && sym != _goal_symbol)
                System.err.printf("warning: <%s> not used (defined on line %d)\n",
                                  sym.name, Position.line(sym.set));

            if (sym.set == 0 && !isact(sym))
                throw S.parseError(format("<%s> not defined (used on line %d)",
                                   sym.name, Position.line(sym.used)));
        }
    }

    private SYMBOL make_term(String name) {
        SYMBOL p;

        if ((p = _termtab.get(name)) == null) {
            if (_cur_term >= MAXTERM)
                throw S.parseError("Too many terminal symbols (" + MAXTERM + " max.)");

            p = new SYMBOL();
            p.name = name;
            p.val = ++_cur_term;
            _terms[_cur_term] = p;
            _termtab.put(name, p);
        }
        return p;
    }

    private SYMBOL make_literal(YYSTOK tok) {
        String name;
        SYMBOL p;

        if ((name = _literals.get(tok)) == null) {
            name = tok.toString();
            _literals.put(tok, name);
        }

        p = make_term(name);
        if (p.literals == null) {
            p.literals = new HashSet<YYSTOK>();
            p.literals.add(tok);
        } else if (!p.literals.contains(tok)) {
            System.err.printf("warning: the literal token '%s' should be " +
                              "replaced by terminal symbol %s.\n",
                              tok.value, name);
            p.literals.add(tok);
        }

        if (p.set == 0)
            p.set = S.pos;

        return p;
    }

    private void add_term_rhs(SYMBOL term, YYSTOK tok) {
        String dummy = _literals.get(tok);

        if (dummy != null && !dummy.equals(term.name)) {
            // the literal token already used, there is a
            // duplicate token, eliminate it.
            SYMBOL p = _termtab.get(dummy);
            p.name = null;
            p.literals = null;
            p.val = term.val;
            p.set = 0;
            _termtab.remove(dummy);

            term.level = p.level;
            term.assoc = p.assoc;
            term.used  = p.used;

            System.err.printf("warning: the literal token '%s' should be " +
                              "replaced by terminal symbol %s.\n",
                              tok.value, term.name);
        }

        if (term.literals == null)
            term.literals = new HashSet<YYSTOK>();
        term.literals.add(tok);
        _literals.put(tok, term.name);
    }

    /* Create, and initialize, a new nonterminal. is_lhs is used to
     * differentiate between implicit and explicit declarations. It's
     * false if the nonterminal is added because it was found on a
     * right-hand side. It's true if the nonterminal is on a left-hand
     * side.
     *
     * Reutrn a pointer to the new symbol or null if an ateempt is made
     * to use a terminal symbol on a left-hand side.
     */
    private SYMBOL new_nonterm(String name, boolean is_lhs) {
        SYMBOL p;

        if (_goal_symbol_is_next) {
            // Create the goal symbol that have only one right-hand side
            _goal_symbol_is_next = false;
            _goal_symbol = new_nonterm(" goal_" + name, true);
            new_rhs();
            add_nonterm(null, name);
        }

        if ((p = _nontermtab.get(name)) == null) {
            if (_cur_nonterm >= MAXNONTERM)
                throw S.parseError("Too many nonterminal symbols (" + MAXNONTERM + " max.)");

            p = new SYMBOL();
            p.name = name;
            p.val = ++_cur_nonterm;
            _terms[_cur_nonterm] = p;
            _nontermtab.put(name, p);
        }

        if (p.first == null)
            p.first = new BitSet();

        if (is_lhs) {
            _sp.lhs_name = name;
            _sp.lhs      = p;
            _sp.rhs      = null;

            p.set        = S.pos;
        }

        return p;
    }

    /**
     * Get a new PRODUCTION and link it to the head of the production chain
     * of the current nonterminal. Note that the start production MUST be
     * production 0. As a consequence, the first rhs associated with the first
     * nonterminal MUST be the start production. Num_productions is initialized
     * to 0 when it's declared.
     */
    private void new_rhs() {
        PRODUCTION    p;

        p             = new PRODUCTION();
        p.num         = _num_productions++;
        p.lhs         = _sp.lhs;
        p.next        = _sp.lhs.productions;
        _sp.lhs.productions = p;
        _sp.rhs        = p;
    }

    private void add_token(String id, String name) {
        SYMBOL p;

        if (is_token(name)) {
            if ((p = _termtab.get(name)) == null) {
                p = make_term(name);
            }
        } else {
            if ((p = _nontermtab.get(name)) == null) {
                p = new_nonterm(name, false);
            }
        }
        add_to_rhs(id, p);
    }
    
    private void add_nonterm(String id, String name) {
        SYMBOL p;
        if ((p = _nontermtab.get(name)) == null) {
            p = new_nonterm(name, false);
        }
        add_to_rhs(id, p);
    }

    private void add_action(String id, ELNode node, boolean trans, short optflg) {
        SYMBOL p = new SYMBOL();
        p.name   = "{" + (++_cur_act - MINACT) + "}";
        p.val    = _cur_act;
        p.action = node;
        p.trans  = trans;
        p.optflg = optflg;
        add_to_rhs(id, p);
    }

    /**
     * Add a new element to the RHS currently at top of stack. First deal with
     * forward references. If the item isn't in the table, add it. Note that,
     * since terminal symbols must be declared with a %term directive, forward
     * references always refer to nonterminals or action items. When we exit the
     * if statement, p points at the symbol table entry for the current object.
     */
    private void add_to_rhs(String id, SYMBOL p) {
        PRODUCTION rhs = _sp.rhs;
        int i;

        if ((i = rhs.rhs_len++) >= rhs.rhs.length) {
            PROD_SYM[] tmp = new PROD_SYM[rhs.rhs.length * 2];
            System.arraycopy(rhs.rhs, 0, tmp, 0, i);
            rhs.rhs = tmp;
        }
        rhs.rhs[i] = new PROD_SYM(id, p);

        if (isterm(p))
            rhs.prec = p.level;

        if (!isact(p))
            ++rhs.non_act;
        
        p.used = S.pos;
    }

    private static boolean is_token(String name) {
        return Character.isUpperCase(name.charAt(0));
    }

    /* The next two subroutines handle repeating or optional subexpressions.
     * The following mappings are done, depending on the operator:
     *
     * S : A (B)  C;        S   -> A 001 C
     *                      001 -> B
     *
     * S : A (B)? C;        S   -> A 001 C
     *                      001 -> B | epsilon
     *
     * S: A (B)* C;         S   -> A 001 C
     *                      001 -> 001 B | epsilon
     *
     * S: A (B)+ C;         S   -> A 001 C
     *                      001 -> 001 B | B
     *
     * In all situations, the right hand side that we've collected so far is
     * pushed and a new right-hand side is started for the subexpression. Note that
     * the first character of the created rhs name (001 in the previous examples)
     * is a space, which is illegal in a user-supplied production name so we don't
     * have to worry about conflicts. Subsequent symbols are added to this new
     * right-hand side. When the ), )?, )*, or )+ is found, we finish the new
     * right-hand side, pop the stack and add the name of the new right-hand side
     * to the previously collected left-hand side.
     */

    private int _opt_num = 0;

    /* Start an optional subexpression. */
    private void start_opt() {
        String name = format(" %06d", _opt_num++);   // Make name for new production
        _sp = new CUR_SYM(_sp);                      // Push current stack element
        new_nonterm(name, true);                     // Create a nonterminal for it
    }

    /* End optional subexpression. */
    private void end_opt(String id, int lex) {
        if (_sp.rhs.next != null && (lex == STAR_CLOSE || lex == PLUS_CLOSE)) {
            String lhs_name = _sp.lhs_name;

            /* Convert multi-alternates subexpression into a single
             * alternate subexpression:
             *
             * S : A (B|C)* D;      S   -> A 002 D
             *                      001 -> B|C
             *                      002 -> 002 001 | epsilon
             */

            // Create a new nonterminal to reference the subexpression
            new_nonterm(format(" %06d", _opt_num++), true);
            new_rhs();
            add_nonterm(null, lhs_name);
        }

        String      name    = _sp.lhs_name;
        PRODUCTION  rhs     = _sp.rhs;
        int         n       = _sp.rhs.rhs_len;
        ELNode      action  = null;
        boolean     trans   = false;
        PROD_SYM    p;

        if (n > 0 && (lex == STAR_CLOSE || lex == PLUS_CLOSE)) {
            // Remove last action and rebuild it for list construction
            p = rhs.rhs[n-1];
            if (isact(p.sym)) {
                action = p.sym.action;
                trans  = p.sym.trans;
                rhs.rhs[n = --rhs.rhs_len] = null;
            }
        }

        if (lex == PLUS_CLOSE) {
            // Add action for list head construction
            add_action(null, action, trans, OPT_LIST_HEAD);

            // Make copy of right-hand side
            new_rhs();
            _sp.rhs.rhs = new PROD_SYM[n+2];
            _sp.rhs.rhs_len = n;
            System.arraycopy(rhs.rhs, 0, _sp.rhs.rhs, 0, n);
            rhs = _sp.rhs;
        }

        if (lex == STAR_CLOSE || lex == PLUS_CLOSE) {
            // Add left-recursive reference
            add_nonterm(id, name);
            p = rhs.rhs[n];
            System.arraycopy(rhs.rhs, 0, rhs.rhs, 1, n);
            rhs.rhs[0] = p;

            // Add action for list construction
            add_action(null, action, trans, OPT_LIST_CONS);
        }

        if (lex == OPTIONAL) {
            // Create epsilon production
            new_rhs();
        } else if (lex == STAR_CLOSE) {
            // Create nil list construction
            new_rhs();
            add_action(null, null, false, OPT_LIST_HEAD);
        }

        _sp = _sp.next;         // discard top-of-stack element
        add_nonterm(id, name);  // add the optional nonterm to original production
    }

    /* Increment the current precedence level and modify "Associativity"
     * to remember if we're going left, right or neither.
     */
    private void new_lev(char how) {
        if ((_associativity = how) != 0) /* 'l', 'r', 'n', (0 if unspecified) */
            ++_prec_lev;
    }

    /* Add current name (in yytext) to the precedence list. "Associativity" is
     * set to 'l', 'r', or 'n', depending on whether we're doing a %left,
     * %right, or %nonassoc. Also make a nonterminal if it doesn't exist
     * already.
     */
    private void prec_list(String name) {
        SYMBOL sym = make_term(name);
        sym.level  = _prec_lev;
        sym.assoc  = _associativity;
        sym.used   = sym.set = S.pos; // FIXME
    }

    /* Change the precedence level for the current right-hand side, using
     * (1) an explicit number if one is specified, or (2) an element from the
     * Precedence() table otherwise.
     */
    private void prec(String name) {
        SYMBOL sym;

        if ((sym = _termtab.get(name)) == null)
            throw S.parseError(format("%s (used in %%prec) undefined", name));

        _sp.rhs.prec = sym.level;
    }

    /*-----------------------------------------------------------------------*/
    /* Compute FIRST sets for all productions in a symbol table. */

    /* Construct FIRST sets for all nonterminal symbols in the symbol table. */
    private void first() {
        boolean did_something;
        do {
            did_something = false;
            for (SYMBOL sym : _nontermtab.values()) {
                did_something |= first_closure(sym);
            }
        } while (did_something);
    }

    /* Called for every element in the FIRST sets. Adds elements to the first
     * sets. The following rules are used:
     *
     * 1) given lhs->...Y... where Y is a terminal symbol preceded by any number
     *   (including 0) of nullable nonterminal symbols or actions, add Y to FIRST(x)
     *
     * 2) given lhs->...y... where y is a nonterminal symbol preceded by any
     *    number (including 0) of nullable nonterminal symbols or actions, add
     *    FIRST(y) to FIRST(lhs).
     */
    private boolean first_closure(SYMBOL lhs) {
        PRODUCTION prod;
        BitSet set;

        set = new BitSet();
        set.or(lhs.first);

        for (prod = lhs.productions; prod != null; prod = prod.next) {
            if (prod.non_act == 0) {        // no non-action symbols
                set.set(EPSILON());         // add epsilon to first set
                continue;
            }

            for (int i = 0; i < prod.rhs_len; i++) {
                SYMBOL y = prod.rhs[i].sym;

                if (isact(y))
                    continue;

                if (isterm(y))
                    set.set(y.val);
                else
                    set.or(y.first);

                if (!nullable(y))
                    break;
            }
        }

        if (!set.equals(lhs.first)) {
            lhs.first.clear();
            lhs.first.or(set);
            return true;
        }

        return false;
    }

    /* Fill the destination set with FIRST(rhs) where rhs is the right-hand side
     * of a production represented as an array of pointers to symbol-table
     * elements. Return true if the entire right-hand side is nullable, otherwise
     * return false.
     */
    private boolean first_rhs(BitSet dest, PROD_SYM[] rhs, int off, int len) {
        if (len <= 0) {
            dest.set(EPSILON());
            return true;
        }

        for (; --len >= 0; ++off) {
            SYMBOL sym = rhs[off].sym;

            if (isact(sym))
                continue;

            if (isterm(sym))
                dest.set(sym.val);
            else
                dest.or(sym.first);

            if (!nullable(sym))
                break;
        }

        return len < 0;
    }

    /*-----------------------------------------------------------------------*/

    /* Find all keywords that leading the goal production. */
    private Set<String> prefix_keys() {
        Set<String> keys = new HashSet<String>();
        int i;

        if (_goal_symbol != null) {
            for (i = _goal_symbol.first.nextSetBit(0); i>=0;
                 i = _goal_symbol.first.nextSetBit(i+1))
            {
                SYMBOL sym = _terms[i];
                if (sym != null && sym.literals != null) {
                    for (YYSTOK tok : sym.literals)
                        if (!tok.rule && is_keyword(tok.value))
                            keys.add(tok.value);
                }
            }
        }

        return keys;
    }

    /* Find all keywords that following subexpression. */
    private Set<String> infix_keys() {
        Set<String> keys    = new HashSet<String>();
        BitSet      set     = new BitSet();
        BitSet      visited = new BitSet();

        infix(_goal_symbol, set, visited);

        for (int i = set.nextSetBit(0); i>=0; i = set.nextSetBit(i+1)) {
            SYMBOL sym = _terms[i];
            if (sym != null && sym.literals != null) {
                for (YYSTOK tok : sym.literals)
                    if (!tok.rule && is_keyword(tok.value))
                        keys.add(tok.value);
            }
        }

        return keys;
    }

    private void infix(SYMBOL lhs, BitSet set, BitSet visited) {
        PRODUCTION prod;
        int        i, j;
        SYMBOL     x, y;

        if (visited.get(lhs.val)) {
            return;
        } else {
            visited.set(lhs.val);
        }

        for (prod = lhs.productions; prod != null; prod = prod.next) {
            for (i = 0; i < prod.rhs_len; i++) {
                x = prod.rhs[i].sym;

                if (isact(x))
                    continue;

                if (x.val == _EXPR_) {
                    for (j = i+1; j < prod.rhs_len; j++) {
                        y = prod.rhs[j].sym;

                        if (isact(y))
                            continue;

                        if (isterm(y)) {
                            set.set(y.val);
                            break;
                        } else {
                            set.or(y.first);
                            if (!nullable(y))
                                break;
                        }
                    }
                    break;
                }

                if (isnonterm(x))
                    infix(x, set, visited);

                if (!nullable(x))
                    break;
            }
        }
    }

    private boolean is_keyword(String name) {
        if (Character.isJavaIdentifierStart(name.charAt(0))) {
            for (int i = 1; i < name.length(); i++)
                if (!Character.isJavaIdentifierPart(name.charAt(i)))
                    return false;
            return true;
        } else {
            Operator op = _original_lexer.getOperator(name);
            return op != null && op.token == Token.KEYWORD;
        }
    }

    /*-------------------------------------------------------------------*/

    private YYACT[] _yy_acts;

    private void patch() {
        Map<Integer,YYACT> tab = new HashMap<Integer,YYACT>();

        int last_real_nonterm = _cur_nonterm;
        for (int i = MINNONTERM; i <= last_real_nonterm; i++) {
            dopatch(tab, _terms[i]);
        }

        _yy_acts = new YYACT[_num_productions];
        for (Map.Entry<Integer,YYACT> e : tab.entrySet()) {
            _yy_acts[e.getKey()] = e.getValue();
        }
    }

    private void dopatch(Map<Integer,YYACT> tab, SYMBOL sym) {
        PRODUCTION prod;

        for (prod = sym.productions; prod != null; prod = prod.next) {
            if (prod.rhs_len == 0)
                continue;

            int pp = prod.rhs_len - 1;
            SYMBOL cur = prod.rhs[pp].sym;

            if (isact(cur)) {                   // Check rightmost symbol
                make_one_case(prod.num, --prod.rhs_len, cur, prod, tab);
                prod.rhs[pp--] = null;
            }

            // Cur is no longer valid because of the --pp above.
            // Count the number of nonactions in the right-hand side.
            for (; pp >= 0; --pp) {
                cur = prod.rhs[pp].sym;

                if (!isact(cur))
                    continue;

                if (_cur_nonterm >= MAXNONTERM)
                    throw S.parseError("Too many nonterminals & actions");
                else {
                    // Transform the action into a nonterminal

                    _terms[cur.val = ++_cur_nonterm] = cur;
                    _nontermtab.put(cur.name, cur);
                    make_one_case(_num_productions, pp, cur, prod, tab);

                    cur.productions = new PRODUCTION();
                    cur.productions.num = _num_productions++;
                    cur.productions.lhs = cur;
                    cur.productions.rhs_len = 0;
                    cur.productions.rhs = null;
                    cur.productions.next = null;
                    cur.productions.prec = 0;

                    /* Since the new production goes to epsilon and nothing else,
                     * FIRST(new) == { epsilon }. Don't bother to refigure the
                     * follow sets because they won't be used in the LALR(1) state-
                     * machine routines.
                     */
                    cur.first = new BitSet();
                    cur.first.set(EPSILON());
                }
            }
        }
    }

    private void make_one_case(int prod_num, int rhs_size, SYMBOL sym, PRODUCTION prod,
                               Map<Integer,YYACT> tab)
    {
        String[] ids = new String[rhs_size];
        for (int i = 0; i < rhs_size; i++) {
            ids[i] = prod.rhs[i].id;
        }

        YYACT act = new YYACT();
        act.action = sym.action;
        act.ids    = ids;
        act.trans  = sym.trans;
        act.optflg = sym.optflg;
        tab.put(prod_num, act);
    }

    /*---------------------------------------------------------------------------*/

    static final int INITSTATE = 16;    // Initial # of LALR(1) states

    static class ITEM {                 // LR(1) item:
        int         prod_num;           // production number
        PRODUCTION  prod;               // the production itself
        SYMBOL      right_of_dot;       // symbol to the right of the dot
        int         dot_posn;           // offset of dot from start of production
        BitSet      lookaheads;         // set of lookahead symbols for this item
    }

    private static int right_of_dot(ITEM p) {
        return p.right_of_dot != null ? p.right_of_dot.val : 0;
    }

    static final int MAXKERNEL = 32;    // Maximum number of kernel items in a state
    static final int MAXCLOSE = 128;    // Maximum number of closure items in a state
                                        // (less the epsilon productions).
    static final int MAXEPSILON = 8;    // Maximum number of epsilon productions that
                                        // can be in a closure set for any given state

    static class STATE {                // LR(1) state
        ITEM[] kernel_items  = new ITEM[MAXKERNEL];  // Set of kernel items
        ITEM[] epsilon_items = new ITEM[MAXEPSILON]; // Set of epsilon items.

        int nkitems;                    // # items in kernel_items[]
        int neitems;                    // # items in epsilon_items[]
        int closed;                     // State has had closure performed.

        int num;                        // State number (0 is start state).
    }

    /* Possible value of STATE.closed */
    static final int NEW        = 0;
    static final int UNCLOSED   = 1;
    static final int CLOSED     = 2;

    static class ACT {
        int sym;                        // Given this input symbol
        int do_this;                    // do this. >0 == shift, <0 == reduce
        ACT next;                       // Pointer to next ACT in the linked list.
    }

    /* Array of pointers to the head of the action chains. Indexed by state number. */
    private ACT[] _actions = new ACT[INITSTATE];

    /* Array of pointers to the head of the goto chains. */
    private ACT[] _gotos = new ACT[INITSTATE];

    /* Return a pointer to the existing ACT structure representing the
     * indicated state and input symbol (or NULL if no such symbol exists).
     */
    private ACT p_action(int state, int input_sym) {
        ACT p;

        for (p = _actions[state]; p != null; p = p.next)
            if (p.sym == input_sym)
                return p;
        return null;
    }

    /* Add an element tot he action part of the parse table. The cell is
     * indexed by the state number and input symbol, and holds do this.
     */
    private void add_action(int state, int input_sym, int do_this) {
        ACT p          = new ACT();
        p.sym          = input_sym;
        p.do_this      = do_this;
        p.next         = _actions[state];
        _actions[state] = p;
    }

    /* Return a pointer to the existing GOTO structure representing the
     * indicated state and nonterminal (or NULL if no such symbol exists). The
     * value used for the nonterminal is the one in the symbol table; it is
     * adjusted down (so that the smallest nonterminal has the value 0)
     * before doing the table look up, however.
     */
    private ACT p_goto(int state, int nonterminal) {
        ACT p;

        nonterminal = adj_val(nonterminal);

        for (p = _gotos[state]; p != null; p = p.next)
            if (p.sym == nonterminal)
                return p;
        return null;
    }

    /* Add an element to the goto part of the parse table, the cell is indexed
     * by current state number and nonterminal value, and holds go_here. Note
     * that the input nonterminal value is the one that appears in the symbol
     * table. It is adjusted downwards (so that the smallest nonterminal will
     * have the value 0) before being inserted into the table, however.
     */
    private void add_goto(int state, int nonterminal, int go_here) {
        ACT p;

        nonterminal = adj_val(nonterminal);

        p            = new ACT();
        p.sym        = nonterminal;
        p.do_this    = go_here;
        p.next       = _gotos[state];
        _gotos[state] = p;
    }

    private Map<STATE_KEY,STATE> _states = new HashMap<STATE_KEY,STATE>();
    private int _nstates;

    static class TNODE {
        STATE state;
        TNODE left, right;
    }

    private TNODE _unfinished;

    static class STATE_KEY {
        ITEM[]  items;
        int     nitems;

        STATE_KEY(ITEM[] items, int nitems) {
            this.items  = items;
            this.nitems = nitems;
        }

        public int hashCode() {
            int total = 0;
            for (int i = 0; i < nitems; i++) {
                total += items[i].prod_num + items[i].dot_posn;
            }
            return total;
        }

        public boolean equals(Object obj) {
            if (obj == this)
                return true;

            if (obj instanceof STATE_KEY) {
                STATE_KEY that = (STATE_KEY)obj;

                if (nitems != that.nitems)
                    return false;

                for (int i = 0; i < nitems; i++) {
                    if (items[i].prod_num != that.items[i].prod_num)
                        return false;
                    if (items[i].dot_posn != that.items[i].dot_posn)
                        return false;
                }

                return true;
            }

            return false;
        }
    }

    private STATE newstate(ITEM[] items, int nitems) {
        STATE state;

        if (nitems > MAXKERNEL)
            throw S.parseError("Kernel of new state " + _nstates + " too large");

        if ((state = _states.get(new STATE_KEY(items, nitems))) != null) {
            if (state.closed == NEW)
                state.closed = UNCLOSED;
            return state;
        } else {
            state = new STATE();
            state.kernel_items = new ITEM[nitems];
            System.arraycopy(items, 0, state.kernel_items, 0, nitems);
            state.nkitems = nitems;
            state.neitems = 0;
            state.closed = NEW;
            state.num = _nstates++;

            _states.put(new STATE_KEY(state.kernel_items, nitems), state);
            _actions = ensure_capacity(_actions, _nstates);
            _gotos = ensure_capacity(_gotos, _nstates);
            return state;
        }
    }

    private void add_unfinished(STATE state) {
        if (_unfinished == null) {
            _unfinished = new TNODE();
            _unfinished.state = state;
            return;
        }

        for (TNODE root = _unfinished; root != null; ) {
            int cmp = state.num - root.state.num;
            if (cmp == 0) {
                break;
            } else if (cmp < 0) {
                if (root.left != null) {
                    root = root.left;
                } else {
                    root.left = new TNODE();
                    root.left.state = state;
                    break;
                }
            } else {
                if (root.right != null) {
                    root = root.right;
                } else {
                    root.right = new TNODE();
                    root.right.state = state;
                    break;
                }
            }
        }
    }

    /* Returns a pointer to the next unfinished state and deletes that
     * state from the unfinished tree. Returns NULL if the tree is empty.
     */
    private STATE get_unfinished() {
        if (_unfinished == null) {
            return null;
        }

        // find leftmost node
        TNODE parent = null;
        TNODE root = _unfinished;
        while (root.left != null) {
            parent = root;
            root = root.left;
        }

        // Unlink node from the tree
        if (parent == null) {
            _unfinished = root.right;
        } else {
            parent.left = root.right;
        }

        return root.state;
    }

    private ITEM newitem(PRODUCTION production) {
        ITEM item         = new ITEM();
        item.prod         = production;
        item.prod_num     = production.num;
        item.dot_posn     = 0;
        item.right_of_dot = production.rhs_len > 0 ? production.rhs[0].sym : null;
        item.lookaheads   = new BitSet();
        return item;
    }

    /* Moves the dot one position to the right and updates the right_of_dot symbol */
    private void movedot(ITEM item) {
        int i = ++item.dot_posn;
        item.right_of_dot =  i < item.prod.rhs_len ? item.prod.rhs[i].sym : null;
    }

    /* Return the relative weight of two items, 0 if they're equivalent. */
    static final Comparator<ITEM> item_cmp = new Comparator<ITEM>() {
        public int compare(ITEM item1, ITEM item2) {
            int rval;
            if ((rval = right_of_dot(item1) - right_of_dot(item2)) == 0)
                if ((rval = item1.prod_num - item2.prod_num) == 0)
                    rval = item1.dot_posn - item2.dot_posn;
            return rval;
        }
    };

    /* Make an LALR(1) transition matrix for the grammar currently
     * represented in the symbol table.
     */
    private Grammar make_grammar() {
        ITEM[]          items;
        STATE           state;
        PRODUCTION      start_prod;
        PRODUCTION[]    prodtab;
        Grammar         grammar;

        /* Create an initial LR(1) item containing the start production and
         * the end-of-input marker as a lookahead symbol.
         */

        if (_goal_symbol == null)
            throw S.parseError("No goal symbol.");

        start_prod = _goal_symbol.productions;

        if (start_prod.next != null)
            throw S.parseError("Start symbol must have only one right-hand side.");

        items = new ITEM[1];                // Make item for start production
        items[0] = newitem(start_prod);
        items[0].lookaheads.set(_EOI_);
        state = newstate(items, 1);

        lr(state);                          // Add shifts and gotos to the table
        reductions();                       // Add the reductions

        grammar = new Grammar();
        prodtab = mkprodtab();

        grammar.prefix_keys = prefix_keys();
        grammar.infix_keys  = infix_keys();
        grammar.blocks      = _code_blocks;
        grammar.fragments   = _fragments;
        grammar.yy_action   = make_tab(_actions);
        grammar.yy_goto     = make_tab(_gotos);
        grammar.yy_stok     = make_yy_stok();
        grammar.yy_lhs      = make_yy_lhs(prodtab);
        grammar.yy_reduce   = make_yy_reduce(prodtab);
        grammar.yy_acts     = _yy_acts;

        return grammar;
    }

    /* Make LALR(1) state machine. The shifts and gotos are done here, the
     * reductions are done elsewhere. Return the number of states.
     */
    private void lr(STATE cur_state) {
        ITEM[]  closure_items = new ITEM[MAXCLOSE];
        STATE   next;           // Next state
        int     nclose;         // Number of items in closure items
        int     nitems;         // # items with same symbol to right of dot
        int     val;            // Value of symbol to right of dot
        SYMBOL  sym;            // Actual symbol to right of dot.

        add_unfinished(cur_state);

        while ((cur_state = get_unfinished()) != null) {

            /* closure()  adds normal closure items to closure_items array.
             * kclosure() adds to that set all items in the kernel that have
             *            outgoing transitions (ie. whose dots aren't at the far
             *            right).
             * sort()     sorts the closure items by the symbol to the right
             *            of dot. Epsilon transitions will sort to the head of
             *            the list, followed by transitions on nonterminals,
             *            followed by transitions on terminals.
             * move_eps() moves the epsilon transitions into the closure kernel set.
             */

            nclose = closure (cur_state, closure_items, MAXCLOSE);
            nclose = kclosure(cur_state, closure_items, MAXCLOSE, nclose);
            Arrays.sort(closure_items, 0, nclose, item_cmp);
            nitems = move_eps(cur_state, closure_items, nclose);

            System.arraycopy(closure_items, nitems, closure_items, 0, nclose -= nitems);

            /* All of the remaining items have at least on symbol to the
             * right of the dot. */
            while (nclose > 0) {
                sym = closure_items[0].right_of_dot;
                val = sym.val;

                /* Collect all items with the same symbol to the right of the dot.
                 * On exiting the loop, nitems will hold the number of these items
                 * and will point at the first matching item. Finally nclose is
                 * decremented by nitems.
                 */
                nitems = 0;
                do {
                    movedot(closure_items[nitems++]);
                } while (--nclose > 0 && right_of_dot(closure_items[nitems]) == val);

                /* (1) newstate() gets the next state. It returns NEW if the state
                 *     didn't exist previously, CLOSED if LR(0) closure has been
                 *     performed on the state, UNCLOSED otherwise.
                 * (2) add a transition from the current state to the next state.
                 * (3) If it's a brand-new state, add it to the unfinished list.
                 * (4) otherwise merge the lookaheads created by the current closure
                 *     operation with the ones already in the state.
                 * (5) If the merge operation added lookaheads to the existing set.
                 *     add it to the unfinished list.
                 */

                next = newstate(closure_items, nitems);                     /* (1) */

                if (cur_state.closed != CLOSED) {
                    if (isterm(sym))                                        /* (2) */
                        add_action(cur_state.num, val, next.num);
                    else
                        add_goto  (cur_state.num, val, next.num);
                }

                if (next.closed == NEW) {
                    add_unfinished(next);                                   /* (3) */
                } else {                                                    /* (4) */
                    if (merge_lookaheads(next.kernel_items, closure_items, nitems)) {
                        add_unfinished(next);                               /* (5) */
                    }
                }
                System.arraycopy(closure_items, nitems, closure_items, 0, nclose);
            }
            cur_state.closed = CLOSED;
        }
    }

    /* This routine is called if newstate has determined that a state having the
     * specified items already exists. If this is the case, the item list in the
     * STATE and the current item list will be identical in all respects except
     * lookaheads. This routine merges the lookaheads of the input items
     * (src_items) to the items already in the state (dst_items). false is returned
     * if nothing was done (all lookaheads  in the new state are already in the
     * existing state), true otherwise. It's an internal error if the items don't
     * match.
     */
    private boolean merge_lookaheads(ITEM[] dst_items, ITEM[] src_items, int nitems) {
        boolean did_something = false;

        for (int i = 0; i < nitems; i++) {
            if (   dst_items[i].prod     != src_items[i].prod
                || dst_items[i].dot_posn != src_items[i].dot_posn)
            {
                throw S.parseError("INTERNAL [merge_lookaheads], item mismatch");
            }

            if (!subset(dst_items[i].lookaheads, src_items[i].lookaheads))
            {
                did_something = true;
                dst_items[i].lookaheads.or(src_items[i].lookaheads);
            }
        }

        return did_something;
    }

    /* Move the epsilon items from the closure_items set to the kernel of the
     * current state. If epsilon items already exist in the current state,
     * just merge the lookaheads. Note that, because the closure items were
     * sorted to partition them, the epsilon productions in the closure items
     * set will be in the same order as those already in the kernel. Return
     * the number of items that were moved.
     */
    private int move_eps(STATE cur_state, ITEM[] closure_items, int nclose) {
        ITEM[] eps_items;
        int nitems, moved;

        eps_items = cur_state.epsilon_items;
        nitems    = cur_state.neitems;
        moved     = 0;

        for (int i = 0; i < nclose && closure_items[i].prod.rhs_len == 0; i++) {
            if (++moved > MAXEPSILON)
                throw S.parseError("Too many epsilon productions in state " + cur_state.num);

            if (nitems != 0) {
                eps_items[i].lookaheads.or(closure_items[i].lookaheads);
            } else {
                eps_items[i] = closure_items[i];
            }
        }

        if (moved != 0)
            cur_state.neitems = moved;

        return moved;
    }

    /* Add to the closure set those items from the kernel that will shift to
     * new states (ie. the items with dots somewhere other than the far right).
     */
    private int kclosure(STATE kernel, ITEM[] closure_items, int maxitems, int nclose) {
        maxitems -= nclose;

        for (int i = 0; i < kernel.nkitems; i++) {
            ITEM item = kernel.kernel_items[i];

            if (item.right_of_dot != null) {
                ITEM citem = newitem(item.prod);
                citem.prod = item.prod;
                citem.dot_posn = item.dot_posn;
                citem.right_of_dot = item.right_of_dot;
                citem.lookaheads = (BitSet)item.lookaheads.clone();

                if (--maxitems < 0)
                    throw S.parseError("Too many closure items in state");

                closure_items[nclose++] = citem;
            }
        }

        return nclose;
    }

    /* Do LR(1) closure on the kernel items array in the input STATE. When
     * finished, closure_items[] will hold the new items. The logic is:
     *
     * (1) for (each kernel item)
     *         do LR(1) closure on that item
     * (2) while (items were added in the previous step or are added below)
     *         do LR(1) closure on the items that were added
     */
    private int closure(STATE kernel, ITEM[] closure_items, int maxitems) {
        int     nclose = 0;
        int     n, i;
        boolean did_something = false;

        for (i = 0; i < kernel.nkitems; i++) {
            if ((n = do_close(kernel.kernel_items[i], closure_items, nclose, maxitems)) != 0) {
                did_something = true;
                if (n > 0) {
                    nclose += n;
                    maxitems -= n;
                }
            }
        }

        while (did_something) {
            did_something = false;
            for (i = 0; i < nclose; i++) {
                if ((n = do_close(closure_items[i], closure_items, nclose, maxitems)) != 0) {
                    did_something = true;
                    if (n > 0) {
                        nclose += n;
                        maxitems -= n;
                    }
                }
            }
        }

        return nclose;
    }

    /** Workhorse function used by closure(). Performs LR(1) closure on the
     * input item ([A->b.Cd, e] add [C->x, FIRST(de)]). The new items are added
     * to the closure_items[] array and nitems and maxitems are modified to
     * reflect the number of items in the closure set. Return the number of items
     * in the closure set or -1 if do_close() did anything, 0 if no items were
     * added (as will be the case if the dot is at the far right of the production
     * or the symbol to the right of the dot is a terminal).
     *
     * @param closure_items Array of items added by closure process
     * @param nitems # of items currently in closure_items
     * @param maxitems max # of items that can be added
     * @return # of items in the closure set or -1 if did anything,
     *         0 if no items were added
     */
    private int do_close(ITEM item, ITEM[] closure_items, int nitems, int maxitems) {
        boolean     did_something = false;
        boolean     rhs_is_nullable;
        int         n = 0;
        PRODUCTION  prod;
        ITEM        close_item;
        BitSet      closure_set;

        if (item.right_of_dot == null)
            return 0;

        if (!isnonterm(item.right_of_dot))
            return 0;

        closure_set = new BitSet();

        /* The symbol to the right of the dot is a nonterminal. Do the following:
         *
         * (1)  for (every production attached to that nonterminal)
         * (2)      if (the current production is not already in the set of closure items)
         * (3)          add it;
         * (4)      if (the d in [A->b.Cd, e] doesn't exist)
         * (5)          add e to the lookaheads in the closure production
         *          else
         * (6)          The d in [A->b.Cd, e] does exist, compute FIRST(de) and add
         *              it to the lookaheads for the current item if necessary.
         */

                                                                                /* (1) */
        for (prod = item.right_of_dot.productions; prod != null; prod = prod.next)
        {                                                                       /* (2) */
            if ((close_item = in_closure_items(prod, closure_items, nitems)) == null)
            {
                if (--maxitems <= 0)
                    throw S.parseError("LR(1) closure set too large");

                closure_items[nitems++] = close_item = newitem(prod);           /* (3) */
                ++n;
            }

            if (item.dot_posn + 1 >= item.prod.rhs_len)                         /* (4) */
            {                                                                   /* (5) */
                did_something |= add_lookahead(close_item.lookaheads, item.lookaheads);
            }
            else
            {
                closure_set.clear();                                            /* (6) */
                rhs_is_nullable = first_rhs(closure_set, item.prod.rhs,
                                            item.dot_posn + 1,
                                            item.prod.rhs_len - item.dot_posn - 1);
                closure_set.clear(EPSILON());

                if (rhs_is_nullable)
                    closure_set.or(item.lookaheads);
                did_something |= add_lookahead(close_item.lookaheads, closure_set);
            }
        }

        return n != 0 ? n : did_something ? -1 : 0;
    }

    /* If the indicated production is in the closure_items already, return a
     * pointer to the existing item, otherwise return null.
     */
    private ITEM in_closure_items(PRODUCTION production, ITEM[] closure_items, int nitems) {
        for (int i = 0; i < nitems; i++)
            if (closure_items[i].prod == production)
                return closure_items[i];
        return null;
    }

    /* Merge the lookaheads in the src and dst sets. If the original src
     * set was empty, or if it was already a subset of the destination set,
     * return false, otherwise return true.
     */
    private boolean add_lookahead(BitSet dst, BitSet src) {
        if (!src.isEmpty() && !subset(dst, src)) {
            dst.or(src);
            return true;
        }
        return false;
    }

    /* Do the reductions. */
    private void reductions() {
        for (STATE state : _states.values()) {
            addreductions(state);
        }
    }

    /* This routing is called for each state. It adds the reductions using the
     * disambiguating rules described in the text.
     */
    private void addreductions(STATE state) {
        int i;

        for (i = 0; i < state.nkitems; i++)
            reduce_one_item(state, state.kernel_items[i]);

        for (i = 0; i < state.neitems; i++)
            reduce_one_item(state, state.epsilon_items[i]);
    }

    private void reduce_one_item(STATE state, ITEM item) {
        int     token;              // Current lookahead
        int     pprec;              // Precedence of production
        int     tprec;              // Precedence of token
        int     assoc;              // Associativity of token
        int     reduce_by;
        ACT     ap;

        if (item.right_of_dot != null)  // No reduction required
            return;

        pprec = item.prod.prec;         // precedence of entire production

        for (token = item.lookaheads.nextSetBit(0); token>=0;
             token = item.lookaheads.nextSetBit(token+1))
        {
            if ((ap = p_action(state.num, token)) == null)      // No conflicts
            {
                add_action(state.num, token, -item.prod_num);
            }
            else if (ap.do_this <= 0)
            {
                // Resovle a reduce/reduce conflict in favor of the production
                // with the smaller number. Print a warning.

                reduce_by = Math.min(-ap.do_this, item.prod_num);
                ap.do_this = -reduce_by;

                System.err.printf("warning: State %2d: reduce/reduce conflict " +
                                  "%d/%d on %s (choose %d).\n",
                                  state.num,
                                  -ap.do_this, item.prod_num,
                                  token != 0 ? _terms[token].name : "<EOI>",
                                  reduce_by);
            }
            else
            {
                tprec = _terms[token].level;
                assoc = _terms[token].assoc;

                if (pprec != 0 && tprec != 0) {
                    if (tprec < pprec || (pprec == tprec && assoc != 'r'))
                        ap.do_this = -item.prod_num;
                } else {
                    System.err.printf("warning: State %2d: shift/reduce conflict %s/%d" +
                                      " (choose %s)\n",
                                      state.num,
                                      _terms[token].name,
                                      item.prod_num,
                                      ap.do_this < 0 ? "reduce" : "shift");
                }
            }
        }
    }

    /* This subroutine generates the yy_stok[] array that's
     * indexed by token value and evaluates to a string
     * representing the token name. Token values are adjusted
     * so that the smallest token value is 1 (0 is reserved
     * for end of input).
     */
    private YYSTOK[][] make_yy_stok() {
        YYSTOK[][] yy_stok = new YYSTOK[_cur_term + 1][];
        for (int i = MINUSERTERM; i <= _cur_term; i++) {
            if (_terms[i].used != 0 && _terms[i].literals != null) {
                yy_stok[i] = new YYSTOK[_terms[i].literals.size()];
                _terms[i].literals.toArray(yy_stok[i]);
                for (YYSTOK tok : yy_stok[i])
                    tok.name = _terms[i].name;
            }
        }
        return yy_stok;
    }

    /* Create the action or goto tab. */
    private int[][] make_tab(ACT[] table) {
        int     i, j;
        ACT     ele;
        int[][] matrix = new int[_nstates][];

        for (i = 0; i < _nstates; ++i) {
            if (i >= table.length || table[i] == null)
                continue;

            int count = 0;
            for (ele = table[i]; ele != null; ele = ele.next)
                ++count;

            int[] column = new int[count*2];
            matrix[i] = column;
            for (j = 0, ele = table[i]; ele != null; j++, ele = ele.next) {
                column[j*2  ] = ele.sym;
                column[j*2+1] = ele.do_this;
            }
        }

        return matrix;
    }

    private PRODUCTION[] mkprodtab() {
        PRODUCTION[] prodtab = new PRODUCTION[_num_productions];
        for (SYMBOL sym : _nontermtab.values())
            for (PRODUCTION p = sym.productions; p != null; p = p.next)
                prodtab[p.num] = p;
        return prodtab;
    }

    private int[] make_yy_lhs(PRODUCTION[] prodtab) {
        int[] yy_lhs = new int[prodtab.length];
        for (int i = 0; i < prodtab.length; i++) {
            yy_lhs[i] = adj_val(prodtab[i].lhs.val);
        }
        return yy_lhs;
    }

    private int[] make_yy_reduce(PRODUCTION[] prodtab) {
        int[] yy_reduce = new int[prodtab.length];
        for (int i = 0; i < prodtab.length; i++)
            yy_reduce[i] = prodtab[i].rhs_len;
        return yy_reduce;
    }

    private static boolean subset(BitSet set, BitSet possible_subset) {
        for (int i=possible_subset.nextSetBit(0); i>=0; i=possible_subset.nextSetBit(i+1))
            if (!set.get(i))
                return false;
        return true;
    }

    @SuppressWarnings({"unchecked"})
    private static <T> T[] ensure_capacity(T[] data, int min_capacity) {
        int old_capacity = data.length;
        if (min_capacity > old_capacity) {
            T[] old_data = data;
            int new_capacity = (old_capacity * 3)/2 + 1;
            if (new_capacity < min_capacity)
                new_capacity = min_capacity;
            data = (T[])Array.newInstance(data.getClass().getComponentType(), new_capacity);
            System.arraycopy(old_data, 0, data, 0, old_capacity);
        }
        return data;
    }
}
