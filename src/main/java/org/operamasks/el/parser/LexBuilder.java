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

import java.util.BitSet;
import java.util.Stack;
import java.util.Map;
import java.util.HashMap;

public class LexBuilder
{
    static class NFA
    {
        int         val;        /* The state number of this node.        */
        int         edge;       /* Label for edge: character, CCL, EMPTY */
                                /* or EPSILON. */
        BitSet      bitset;     /* Set to store character classes.       */
        boolean     compl;      /* is a negative character class set.    */
        NFA         next;       /* Next state (or NULL if none)          */
        NFA         next2;      /* Another next state if edge==EPSILON   */
        int         accept;     /* -1 if not an accepting state, else    */
                                /* the accepting code of the rule        */

        NFA         end;        /* temporarily used for machine building   */
    }

    /* Non-character values of NFA.edge */
    static final int EPSILON = -1;
    static final int CCL     = -2;
    static final int EMPTY   = -3;

    /* Tokens */
    static final int EOS         = 1,       /* end of string     */
                     ANY         = 2,       /* .                 */
                     CCL_END     = 3,       /* ]                 */
                     CCL_START   = 4,       /* [                 */
                     CLOSE_CURLY = 5,       /* }                 */
                     CLOSE_PAREN = 6,       /* )                 */
                     CLOSURE     = 7,       /* *                 */
                     COMPLEMENT  = 8,       /* ^                 */
                     DASH        = 9,       /* -                 */
                     L           = 10,      /* literal character */
                     OPEN_CURLY  = 11,      /* {                 */
                     OPEN_PAREN  = 12,      /* (                 */
                     OPTIONAL    = 13,      /* ?                 */
                     OR          = 14,      /* |                 */
                     PCCL        = 15,      /* predefined CCL    */
                     PLUS_CLOSE  = 16;      /* +                 */

    static final int _tokmap[] =
    {
    /*  ^@  ^A  ^B  ^C  ^D  ^E  ^F  ^G  ^H  ^I  ^J  ^K  ^L  ^M  ^N	*/
         L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,

    /*  ^O  ^P  ^Q  ^R  ^S  ^T  ^U  ^V  ^W  ^X  ^Y  ^Z  ^[  ^\  ^]	*/
         L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,

    /*  ^^  ^_  SPACE  !   "   #    $   %   &    '                      */
        L,  L,  L,     L,  L,  L,   L,  L,  L,   L,

    /*  (		 )            *	       +           ,  -     .   */
        OPEN_PAREN,  CLOSE_PAREN, CLOSURE, PLUS_CLOSE, L, DASH, ANY,

    /*  /   0   1   2   3   4   5   6   7   8   9   :   ;   <   =	*/
        L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,

    /*  >         ?							*/
        L,        OPTIONAL,

    /*  @   A   B   C   D   E   F   G   H   I   J   K   L   M   N	*/
        L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,

    /*  O   P   Q   R   S   T   U   V   W   X   Y   Z   		*/
        L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,

    /*  [		\	]		^			*/
        CCL_START,	L,	CCL_END, 	COMPLEMENT,

    /*  _   `   a   b   c   d   e   f   g   h   i   j   k   l   m	*/
        L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,

    /*  n   o   p   q   r   s   t   u   v   w   x   y   z   	*/
        L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,  L,

    /*  {            |    }            DEL 				*/
        OPEN_CURLY,  OR,  CLOSE_CURLY, L
    };

    private NFA[]   _nfa_states = new NFA[16];      /* State-machine array */
    private int     _nstates;                       /* Index of next element of the array */
    private NFA     _start;                         /* The start state of machine */

    private static final int SSIZE = 32;

    private NFA[]   _sstack = new NFA[SSIZE];       /* Stack used by new_nfa() */
    private int     _sp = -1;                       /* Stack pointer */

    private String  _input;                         /* The input string */
    private boolean _inquote;                       /* Processing quoted string */
    private int     _next;                          /* The position of current token */
    private int     _pos;                           /* The source position of input string */
    private int     _current_tok;                   /* Current token */
    private int     _lexeme;                        /* Value associated with LITERAL */

    private Map<String,String> _macros = new HashMap<String, String>();
    private String  _mac_stack[] = new String[SSIZE]; /* Input-source stack */
    private int     _mac_sp = -1;                     /* and stack pointer */

    private Lexer   _lexer = new DefaultLexer();    /* The default lexer */
    private boolean _has_rules;                     /* true if generating state-driven lexer. */

    /*----------------------------------------------------------------
     * Predefined character classes
     */

    private static final BitSet LETTERS = new BitSet();
    private static final BitSet DIGITS  = new BitSet();
    private static final BitSet SPACES  = new BitSet();

    static {
        LETTERS.set('a', 'z'+1);
        LETTERS.set('A', 'Z'+1);
        LETTERS.set('_');

        DIGITS.set('0', '9'+1);

        SPACES.set(' ');
        SPACES.set('\t');
        SPACES.set('\n');
        SPACES.set('\r');
        SPACES.set('\f');
    }

    /*----------------------------------------------------------------
     * Error processing stuff. Note that all errors are fatal.
     *----------------------------------------------------------------
     */

    private static enum ERR_NUM {
        E_BADEXPR("Malformed regular expression"),
        E_PAREN("Missing close parenthesis"),
        E_BRACKET("Missing [ in character class"),
        E_CLOSE("+ ? or * must follow expression"),
        E_BADMAC("Missing } in macro expansion"),
        E_NOMAC("Macro doesn't exist"),
        E_EMPTYMAC("Macro body is empty"),
        E_MACDEPTH("Macro expansions nested too deeply.");

        String errmsg;
        ERR_NUM(String msg) { this.errmsg = msg; }
    }

    private static enum WARN_NUM {
        W_STARTDASH("Treating dash in [-...] as a literal dash"),
        W_ENDDASH("Treating dash in [...-] as a literal dash");

        String warnmsg;
        WARN_NUM(String msg) { this.warnmsg = msg; }
    }

    private void warning(WARN_NUM type) {
        System.err.println(type.warnmsg);
    }

    private void parse_error(ERR_NUM type) {
        throw new ParseException(null, Position.line(_pos), Position.column(_pos), type.errmsg);
    }

    /*--------------------------------------------------------------*/
    /* NFA management methods */

    private NFA new_nfa() {
        NFA p;

        if (_sp == -1) {
            if (_nstates >= _nfa_states.length) {
                NFA[] t = new NFA[_nfa_states.length * 2];
                System.arraycopy(_nfa_states, 0, t, 0, _nstates);
                _nfa_states = t;
            }

            p = new NFA();
            p.val = _nstates;
            _nfa_states[_nstates++] = p;
        } else {
            p = _sstack[_sp--];
        }

        p.edge   = EPSILON;
        p.accept = -1;

        return p;
    }

    private void discard(NFA nfa_to_discard) {
        nfa_to_discard.edge   = EMPTY;
        nfa_to_discard.bitset = null;
        nfa_to_discard.compl  = false;
        nfa_to_discard.next   = null;
        nfa_to_discard.next2  = null;
        _sstack[++_sp] = nfa_to_discard;
    }

    /*----------------------------------------------------------------
     * Lexical analyzer:
     *
     * Lexical analysis is trivial because all lexemes are single-character values.
     * The only complications are escape sequences and quoted strings, both
     * of which are handled by advance(), below. This routine advances past the
     * current token, putting the new token into Current_tok and the equivalent
     * lexeme into Lexeme. If the character was escaped, Lexeme holds the actual
     * value. For example, if a "\s" is encountered, Lexeme will hold a space
     * character.  Advance both modifies Current_tok to the current token and
     * returns it.
     *
     * Macro expansion is handled by means of a stack (declared at the top
     * of the subroutine). When an expansion request is encountered, the
     * current input buffer is stacked, and input is read from the macro
     * text. This process repeats with nested macros, so SSIZE controls
     * the maximum macro-nesting depth.
     */
    private int advance() {
        int t, c;

        while (_next >= _input.length()) {
            if (_mac_sp >= 0) {            // Restore previous input source
                _input = _mac_stack[_mac_sp--];
                _next = 0;
                continue;
            }

            _current_tok = EOS;           // No more input sources to restore
            _lexeme = '\0';               // ie. you're at the real end of string.
            return EOS;
        }

        if (!_inquote) {
            String name, text;
            int i;

            while (_input.charAt(_next) == '{') {   // Macro expansion required
                if ((i = _input.indexOf('}', _next+1)) == -1)   // skip { and find }
                    parse_error(ERR_NUM.E_BADMAC);
                name = _input.substring(_next+1, i);
                if ((text = _macros.get(name)) == null)
                    parse_error(ERR_NUM.E_NOMAC);
                else if (text.length() == 0)
                    parse_error(ERR_NUM.E_EMPTYMAC);
                if (_mac_sp+1 >= _mac_stack.length)
                    parse_error(ERR_NUM.E_MACDEPTH);

                // Stack current input string. Use macro body as input string.
                assert text != null;
                _mac_stack[++_mac_sp] = _input.substring(i+1);
                _input = text;
                _next  = 0;
            }
        }

        c = _input.charAt(_next++);

        if (_inquote) {
            t = L;
        } else if (c == '\\' && _next < _input.length()) {
            c = _input.charAt(_next++);
            t = L;
            switch (c) {
            case 't': c = '\t'; break;
            case 'n': c = '\n'; break;
            case 'r': c = '\r'; break;
            case 'f': c = '\f'; break;
            // predefined character classes
            case 'd': case 'D':
            case 's': case 'S':
            case 'w': case 'W': t = PCCL; break;
            }
        } else {
            t = c >= _tokmap.length ? L : _tokmap[c];
        }

        _current_tok = t;
        _lexeme = c;
        return t;
    }

    /*--------------------------------------------------------------
     * The Parser:
     *	A simple recursive descent parser that creates a Thompson NFA for
     * 	a regular expression. The access routine [thompson()] is at the
     *	bottom. The NFA is created as a directed graph, with each node
     *	containing pointer's to the next node. Since the structures are
     *	allocated from an array, the machine can also be considered
     *	as an array where the state number is the array index.
     */

    public void add_rule(String input, int pos, int accept) {
        _has_rules = true;
        add(input, pos, accept, false);
    }

    public void add_str(String input, int pos, int accept) {
        _lexer.addOperator(input, Token.LALR, accept);
        add(input, pos, accept, true);
    }

    public void add_macro(String name, String text) {
        _macros.put(name, "(" + text + ")");
    }

    private void add(String input, int pos, int accept, boolean inquote) {
        NFA p;

        if (_start == null) {
            p = _start = new_nfa();
        } else {
            p = _start.end;
            p.next2 = new_nfa();
            p = p.next2;
        }
        _start.end = p;

        this._input   = input;
        this._inquote = inquote;
        this._pos     = pos;
        this._next    = 0;

        advance();
        p.next = rule(accept);
    }

    /* - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -*/

    private NFA rule(int accept) {
        /*	rule	--> expr  EOS
         */

        NFA start;

        start = expr();
        start.end.accept = accept;
        advance();
        return start;
    }

    private NFA expr() {
        /* Because a recursive descent compiler can't handle left recursion,
         * the productions:
         *
         *	expr	-> expr OR cat_expr
         *		|  cat_expr
         *
         * must be translated into:
         *
         *	expr	-> cat_expr expr'
         *	expr'	-> OR cat_expr expr'
         *		   epsilon
         *
         * which can be implemented with this loop:
         *
         *	cat_expr
         *	while( match(OR) )
         *		cat_expr
         *		do the OR
         */

        NFA start, end;
        NFA e2_start, e2_end;
        NFA compose = null;
        NFA p;

        start = cat_expr();
        end   = start.end;

        if (start.next == end)
            compose = start;

        while (_current_tok == OR) {
            advance();
            e2_start = cat_expr();
            e2_end   = e2_start.end;

            if (compose != null && e2_start.next == e2_end) {
                // Compose multiple terms into one character class.
                // For example, given an expression "a|b", convert it to
                // an equivalent expression "[ab]".

                if (compose.edge != CCL) {
                    compose.bitset = new BitSet();
                    if (compose.edge != EPSILON)
                        compose.bitset.set(compose.edge);
                    compose.edge = CCL;
                }

                if (e2_start.edge == CCL) {                         // handle complement sets
                    if (!compose.compl && !e2_start.compl) {        // A | B
                        compose.bitset.or(e2_start.bitset);
                    } else if (compose.compl && e2_start.compl) {   // ~A | ~B = ~(A & B)
                        compose.bitset.and(e2_start.bitset);
                    } else if (compose.compl) {                     // ~A | B = ~(A & ~B)
                        compose.bitset.andNot(e2_start.bitset);
                    } else {                                        // A | ~B = ~(B & ~A)
                        e2_start.bitset.andNot(compose.bitset);
                        compose.bitset = e2_start.bitset;
                        compose.compl = true;
                    }
                } else if (e2_start.edge != EPSILON) {
                    compose.bitset.set(e2_start.edge, !compose.compl);
                }

                discard(e2_start);
                discard(e2_end);
            } else {
                p = new_nfa();
                p.next2 = e2_start;
                p.next  = start;
                start   = p;

                p = new_nfa();
                end.next = p;
                e2_end.next = p;
                end = p;

                if (e2_start.next == e2_end)
                    compose = e2_start;
            }
        }

        start.end = end;
        return start;
    }

    private NFA cat_expr() {
        /* The same translations that were needed in the expr rules are needed again
         * here:
         *
         *	cat_expr  -> cat_expr | factor
         *		     factor
         *
         * is translated to:
         *
         *	cat_expr  -> factor cat_expr'
         *	cat_expr' -> | factor cat_expr'
         *		     epsilon
         */

        NFA start = null;
        NFA e2_start;

        if (first_in_cat(_current_tok)) {
            start = factor();

            while (first_in_cat(_current_tok)) {
                e2_start = factor();

                // discard e2_start
                start.end.edge   = e2_start.edge;
                start.end.bitset = e2_start.bitset;
                start.end.compl  = e2_start.compl;
                start.end.next   = e2_start.next;
                start.end.next2  = e2_start.next2;
                start.end        = e2_start.end;
                discard(e2_start);
            }
        }

        return start;
    }

    private boolean first_in_cat(int tok) {
        switch (tok) {
        case CLOSE_PAREN:
        case OR:
        case EOS:           return false;

        case CLOSURE:
        case PLUS_CLOSE:
        case OPTIONAL:      parse_error(ERR_NUM.E_CLOSE);   return false;

        case CCL_END:       parse_error(ERR_NUM.E_BRACKET); return false;
        }

        return true;
    }

    private NFA factor() {
        /*		factor	--> term*  | term+  | term?
        */

        NFA startp, endp;
        NFA start, end;

        startp = term();
        endp   = startp.end;

        if (_current_tok==CLOSURE || _current_tok==PLUS_CLOSE || _current_tok==OPTIONAL) {
            start = new_nfa();
            end   = new_nfa();
            start.next = startp;
            endp.next  = end;

            if (_current_tok==CLOSURE || _current_tok==OPTIONAL)
                start.next2 = end;

            if (_current_tok==CLOSURE || _current_tok==PLUS_CLOSE)
                endp.next2 = startp;

            startp = start;
            startp.end = end;
            advance();
        }

        return startp;
    }

    private NFA term() {
        /* Process the term productions:
         *
         * term  --> [...]  |  [^...]  |  []  |  [^] |  .  | (expr) | <character>
         *
         * The [] is nonstandard. It matches a space, tab, formfeed, or newline,
         * but not a carriage return (\r). All of these are single nodes in the
         * NFA.
         */

        NFA start;

        if (_current_tok == OPEN_PAREN) {
            advance();
            start = expr();
            if (_current_tok == CLOSE_PAREN)
                advance();
            else
                parse_error(ERR_NUM.E_PAREN);
        } else {
            start = new_nfa();
            start.end = start.next = new_nfa();

            if (!(_current_tok==ANY || _current_tok==CCL_START || _current_tok==PCCL)) {
                start.edge = _lexeme;
                advance();
            } else if (_current_tok == PCCL) {
                start.edge = CCL;
                predefined(start);
                advance();
            } else {
                start.edge = CCL;
                start.bitset = new BitSet();

                if (_current_tok == ANY) {
                    start.bitset.set('\n');
                    start.compl = true;
                } else {
                    advance();
                    if (_current_tok == COMPLEMENT) {   // Negative character class
                        advance();
                        start.bitset.set('\n');
                        start.compl = true;
                    }
                    if (_current_tok != CCL_END)
                        dodash(start.bitset);
                    else                                // [] or [^]
                        start.bitset.set(0, ' '+1);
                }
                advance();
            }
        }

        return start;
    }

    private void predefined(NFA p) {
        switch (_lexeme) {
        case 'D': p.compl = true;
        case 'd': p.bitset = DIGITS;
                  break;

        case 'S': p.compl = true;
        case 's': p.bitset = SPACES;
                  break;

        case 'W': p.compl = true;
        case 'w': p.bitset = LETTERS;
                  break;

        default:  assert false;
        }
    }

    private void dodash(BitSet set) {
        int first = 0;

        if (_current_tok == DASH) {         // Treat [-...] as a literal dash
            warning(WARN_NUM.W_STARTDASH);  // But print warning.
            set.set('-');
            advance();
        }

        for (; _current_tok != EOS && _current_tok != CCL_END; advance()) {
            if (_current_tok != DASH) {
                first = _lexeme;
                set.set(_lexeme);
            } else { // looking at a dash
                advance();
                if (_current_tok == CCL_END) { // Treat [...-] as literal
                    warning(WARN_NUM.W_ENDDASH);
                    set.set('-');
                } else if (first <= _lexeme) {
                    set.set(first, _lexeme+1);
                }
            }
        }
    }

    /*--------------------------------------------------------------
     * Routine to print out a NFA structure in human-readable form.
     */

    private void printccl(BitSet set) {
        System.out.print('[');
        for (int i = 0; i <= 0x7f; i++) {
            if (set.get(i)) {
                if (i < ' ')
                    System.out.printf("^%c", (char)(i+'@'));
                else
                    System.out.printf("%c", (char)i);
            }
        }
        System.out.print(']');
    }

    private String plab(NFA nfa) {
        return nfa == null ? "--" : String.format("%2d", nfa.val);
    }

    public void print_nfa() {
        System.out.println("----------------- NFA ----------------");

        for (int i = 0; i < _nstates; i++) {
            NFA nfa = _nfa_states[i];

            if (nfa.edge == EMPTY)
                continue;
            
            System.out.printf("NFA state %s: ", plab(nfa));

            if (nfa.next == null) {
                System.out.print("(TERMINAL)");
            } else {
                System.out.printf("--> %s ", plab(nfa.next));
                System.out.printf("(%s) on ", plab(nfa.next2));

                switch (nfa.edge) {
                case CCL:       printccl(nfa.bitset);             break;
                case EPSILON:   System.out.print("EPSILON ");     break;
                default:        System.out.print((char)nfa.edge); break;
                }
            }

            if (nfa == _start)
                System.out.print(" (START STATE)");

            if (nfa.accept != -1)
                System.out.printf(" accepting <%d>", nfa.accept);

            System.out.println();
        }

        System.out.println("--------------------------------");
    }

    /*--------------------------------------------------------------*/

    private static final int MAX_CHARS = 128;

    private static class DFA {
        BitSet          nfa_set;
        int             accept;
        DFA[]           fan_out;

        DFA() {
            this.nfa_set = null;
            this.accept  = -1;
        }

        DFA(BitSet set, int accept) {
            this.nfa_set = set;
            this.accept  = accept;
        }
    }

    private static final DFA DFA_FAIL = new DFA();

    private static class MachineLexer extends DefaultLexer {
        private NFA[]           states;
        private DFA             start_state;
        private Map<BitSet,DFA> dstates = new HashMap<BitSet,DFA>();
        private Stack<NFA>      stack   = new Stack<NFA>();

        void init(NFA[] states, int nstates, NFA start) {
            if (states.length != nstates) {
                NFA[] tmp = new NFA[nstates];
                System.arraycopy(states, 0, tmp, 0, nstates);
                states = tmp;
            }

            this.states = states;

            // Initialize start state
            BitSet init_set;
            int accept;

            init_set = new BitSet();
            init_set.set(start.val);
            accept = e_closure(init_set);
            start_state = new DFA(init_set, accept);
        }

        private DFA feed(DFA current, int c) {
            DFA next = null;

            if (c < MAX_CHARS && current.fan_out != null) {
                next = current.fan_out[c];
            }

            if (next == null) {
                BitSet nfa_set = move(current.nfa_set, c);
                int    accept  = e_closure(nfa_set);

                if (nfa_set == null) {
                    next = DFA_FAIL;
                } else if ((next = dstates.get(nfa_set)) == null) {
                    next = new DFA(nfa_set, accept);
                    dstates.put(nfa_set, next);
                } else {
                    assert next.accept == accept;
                }

                if (c < MAX_CHARS) {
                    if (current.fan_out == null)
                        current.fan_out = new DFA[MAX_CHARS];
                    current.fan_out[c] = next;
                }
            }

            return next;
        }

        private int e_closure(BitSet set) {
            int i;
            int accept = -1;
            int accept_num = Integer.MAX_VALUE;

            if (set == null)
                return -1;

            stack.clear();
            for (i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i+1))
                stack.push(states[i]);

            while (!stack.isEmpty()) {
                NFA p = stack.pop();

                if (p.accept != -1 && p.val < accept_num) {
                    accept     = p.accept;
                    accept_num = p.val;
                }

                if (p.edge == EPSILON) {
                    if (p.next != null) {
                        if (!set.get(p.next.val)) {
                            set.set(p.next.val);
                            stack.push(p.next);
                        }
                    }
                    if (p.next2 != null) {
                        if (!set.get(p.next2.val)) {
                            set.set(p.next2.val);
                            stack.push(p.next2);
                        }
                    }
                }
            }

            return accept;
        }

        private BitSet move(BitSet inpset, int c) {
            BitSet outset = null;

            for (int i = inpset.nextSetBit(0); i >= 0; i = inpset.nextSetBit(i+1)) {
                NFA p = states[i];
                if (p.edge==c || (p.edge==CCL && (p.bitset.get(c) ^ p.compl))) {
                    if (outset == null)
                        outset = new BitSet();
                    outset.set(p.next.val);
                }
            }
            return outset;
        }

        public void scan(Scanner s) {
            int last       = s.next;
            int lastch     = s.ch;
            int lastpos    = s.pos;
            int lastsp     = 0;
            int lastaccept = -1;
            int c;

            if (s.ch == Token.EOI) {
                s.token = Token.EOI;
                return;
            }

            DFA p = start_state;
            sp = 0;

            while (s.ch != Token.EOI) {
                p = feed(p, s.ch);
                if (p == DFA_FAIL)
                    break; // rejected

                putc(s.ch);
                s.nextchar();

                if (p.accept != -1) {
                    // save state for this accepted token
                    lastaccept = p.accept;
                    last       = s.next;
                    lastch     = s.ch;
                    lastpos    = s.pos;
                    lastsp     = sp;
                }
            }

            // reset position
            s.next  = last;
            s.ch    = lastch;
            s.pos   = lastpos;

            if (lastaccept != -1) {
                String lexeme;
                Operator op;

                sp = lastsp;
                lexeme = sbuf();
                if ((op = getOperator(lexeme)) == null)
                    op = new Operator(lexeme, Token.LALR, lastaccept);
                s.operator = op;
                s.token = op.token;
            } else {
                // fallback to default lexer behavior
                s.token = Token.UNKNOWN;

                switch (s.ch) {
                case '0':
                    if (s.lookahead(0) == 'x' || s.lookahead(0) == 'X') {
                        s.nextchar();
                        scanHexadecimal(s);
                    } else {
                        scanNumber(s);
                    }
                    break;

                case '1': case '2': case '3': case '4':
                case '5': case '6': case '7': case '8': case '9':
                    scanNumber(s);
                    break;

                case '.':
                    c = s.lookahead(0);
                    if (c >= '0' && c <= '9') {
                        sp = 0;
                        putc('.');
                        s.nextchar();
                        scanReal(s);
                    }
                    break;

                case '#':
                    if (s.lookahead(0) == '\'') {
                        s.nextchar();
                        scanCharacter(s);
                    }
                    break;

                case '\'': case '"':
                    scanString(s);
                    break;

                default:
                    if (Character.isJavaIdentifierStart(s.ch)) {
                        scanIdentifier(s);
                    }
                    break;
                }
            }
        }
    }

    public Lexer getLexer() {
        if (_has_rules) {
            MachineLexer machine = new MachineLexer();
            machine.importFrom(_lexer);
            machine.init(_nfa_states, _nstates, _start);
            return machine;
        } else {
            return _lexer;
        }
    }
}
