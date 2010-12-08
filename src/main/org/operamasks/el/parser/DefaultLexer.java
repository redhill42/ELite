/*
 * $Id: DefaultLexer.java,v 1.4 2010/03/21 10:37:25 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.parser;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import elite.lang.Rational;
import elite.lang.Decimal;

import static org.operamasks.el.parser.Token.*;
import static org.operamasks.el.resources.Resources.*;

public class DefaultLexer extends Lexer
{
    // The FSM state.
    private static class State
    {
        List<Transition> transitions;   // the transition table
        Operator accept;                // the accepted operator

        // optimization: transition table indexed by event
        private Transition[] table;
        private static final int MAX_EVENT = 256;
        private static final int SHRESHOLD = 10;

        State() {
            transitions = new ArrayList<Transition>();
        }

        Transition get(int event) {
            if (event < MAX_EVENT && table != null) {
                Transition t = table[event];
                if (t != null)
                    return t;
            }

            for (Transition t : transitions) {
                if (event == t.event) {
                    return t;
                }
            }

            return null;
        }

        Transition add(int event) {
            Transition t = new Transition();
            t.event = event;
            t.next = new State();
            transitions.add(t);

            if (event < MAX_EVENT) {
                if (table != null) {
                    table[event] = t;
                } else if (transitions.size() > SHRESHOLD) {
                    table = new Transition[MAX_EVENT];
                    for (Transition x : transitions) {
                        if (x.event < MAX_EVENT)
                            table[x.event] = x;
                    }
                }
            }

            return t;
        }

        void remove(Transition t) {
            transitions.remove(t);
            if (t.event < MAX_EVENT && table != null) {
                table[t.event] = null;
            }
        }

        boolean isEmpty() {
            return transitions.isEmpty() && accept == null;
        }

        State copy() {
            State copy = new State();
            for (Transition t : transitions)
                copy.transitions.add(t.copy());
            copy.accept = accept;

            if (table != null) {
                copy.table = new Transition[MAX_EVENT];
                for (Transition t : copy.transitions) {
                    if (t.event < MAX_EVENT) {
                        copy.table[t.event] = t;
                    }
                }
            }

            return copy;
        }
    }

    // The FSM transition.
    private static class Transition
    {
        int event;  // the input event
        State next; // the next state of the transition

        Transition copy() {
            Transition copy = new Transition();
            copy.event = this.event;
            copy.next = this.next.copy();
            return copy;
        }
    }

    // The start state of finite state machine
    private State start = new State();

    // The identifier operators
    private Map<String,Operator> operators;
    private boolean allow_keywords;

    public DefaultLexer() {
        this.start          = new State();
        this.operators      = new HashMap<String,Operator>();
        this.allow_keywords = false;
    }

    private DefaultLexer(State start, Map<String,Operator> operators) {
        this.start          = start;
        this.operators      = operators;
        this.allow_keywords = true;
    }

    // The shared lexer
    private static DefaultLexer shared =
        new DefaultLexer(new State(), new HashMap<String,Operator>());

    public static DefaultLexer newInstance() {
        return new DefaultLexer(shared.start, shared.operators);
    }

    /**
     * Make a deep clone to ensure not shared with other lexers.
     */
    public void dirtyCopy() {
        if (start == shared.start)
            start = start.copy();
        if (operators == shared.operators)
            operators = new HashMap<String,Operator>(operators);
    }

    /**
     * Import operators from another lexer.
     */
    public void importFrom(Lexer lexer) {
        if (lexer instanceof DefaultLexer) {
            DefaultLexer other = (DefaultLexer)lexer;
            if (other.operators != this.operators) {
                for (Operator op : other.operators.values()) {
                    Operator myop = this.operators.get(op.name);
                    if (myop != op) {
                        dirtyCopy();
                        addOperator(op.name, op.token, op.token2);
                    }
                }
            }
        }
    }

    public void addOperator(String tok, String name, int token, int token2) {
        Operator op = new Operator(name, token, token2);
        operators.put(tok, op);
        if (!isIdentifier(tok)) {
            addTransition(tok.toCharArray(), op);
        }
    }

    public void removeOperator(String tok) {
        if (operators.remove(tok) != null) {
            if (!isIdentifier(tok)) {
                removeTransition(tok.toCharArray());
            }
        }
    }

    public Operator getOperator(String tok) {
        return operators.get(tok);
    }

    private void addTransition(char[] cs, Operator accept) {
        State p = start;
        for (char c : cs) {
            Transition t = p.get(c);
            if (t == null)
                t = p.add(c);
            p = t.next;
        }
        p.accept = accept;
    }

    private void removeTransition(char[] cs) {
        doRemove(start, cs, 0, cs.length);
    }

    private void doRemove(State p, char[] cs, int off, int len) {
        if (off == len) {
            p.accept = null;
            return;
        }

        Transition t = p.get(cs[off]);
        if (t != null) {
            doRemove(t.next, cs, off+1, len);
            if (t.next.isEmpty()) {
                p.remove(t);
            }
        }
    }

    private State dispatch(State current, int event) {
        Transition t = current.get(event);
        return (t != null) ? t.next : null;
    }

    private static boolean isIdentifier(String id) {
        if (id.length() > 0) {
            if (Character.isJavaIdentifierStart(id.charAt(0))) {
                for (int i = 1; i < id.length(); i++)
                    if (!Character.isJavaIdentifierPart(id.charAt(i)))
                        return false;
                return true;
            }
        }
        return false;
    }

    // A character buffer for literals.
    protected char[] sbuf = new char[128];
    protected int sp;

    private void growBuffer() {
        char newsbuf[] = new char[sbuf.length * 2];
        System.arraycopy(sbuf, 0, newsbuf, 0, sbuf.length);
        sbuf = newsbuf;
    }

    protected void putc(int ch) {
        if (sp == sbuf.length) {
            growBuffer();
        }
        sbuf[sp++] = (char)ch;
    }

    protected String sbuf() {
        return new String(sbuf, 0, sp);
    }

    /**
     * Scan a number. The first digit of the number should be the current
     * character.
     */
    protected void scanNumber(Scanner s) {
        boolean overflow = false;
        long value = s.ch - '0';
        sp = 0;
        putc(s.ch);

    numberLoop:
        for (;;) {
            switch (s.nextchar()) {
            case '_': case '\'':
                // underscores are ignored but can be used as separator
                break;

            case 'e': case 'E': case 'm': case 'M':
                scanReal(s);
                return;

            case '.':
                int cc = s.lookahead(0);
                if (!Character.isJavaIdentifierStart(cc) && cc != '.' && cc != '@') {
                    // The 12.toString() is a legal syntax
                    scanReal(s);
                    return;
                } else {
                    break numberLoop;
                }

            case '0': case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                putc(s.ch);
                overflow = overflow || (value*10)/10 != value;
                value = (value * 10) + (s.ch - '0');
                overflow = overflow || (value - 1 < -1);
                break;

            default:
                break numberLoop;
            }
        }

        s.token = NUMBER;
        if (s.ch == 'b' || s.ch == 'B') {
            s.nextchar();
            s.numberValue = new BigInteger(sbuf());
        } else if (s.ch == 'r' || s.ch == 'R') {
            s.nextchar();
            s.numberValue = Rational.make(new BigInteger(sbuf()), BigInteger.ONE);
        } else if (overflow) {
            s.numberValue = new BigInteger(sbuf());
        } else if ((int)value == value) {
            s.numberValue = (int)value;
        } else {
            s.numberValue = value;
        }
    }

    /**
     * Scan a hexadecimal number.
     */
    protected void scanHexadecimal(Scanner s) {
        long value = 0;
        boolean overflow = false;
        sp = 0;

        for (;;) {
            switch (s.nextchar()) {
            case '0': case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                putc(s.ch);
                overflow = overflow || (value >>> 60) != 0;
                value = (value << 4) + (s.ch - '0');
                break;

            case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
                putc(s.ch);
                overflow = overflow || (value >>> 60) != 0;
                value = (value << 4) + (s.ch - 'a' + 10);
                break;

            case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
                putc(s.ch);
                overflow = overflow || (value >>> 60) != 0;
                value = (value << 4) + (s.ch - 'A' + 10);
                break;

            case '_': case '\'':
              // underscores are ignored but can be used as separator
              break;

            default:
                s.token = NUMBER;
                if (overflow) {
                    s.numberValue = new BigInteger(sbuf(), 16);
                } else if ((int)value == value) {
                    s.numberValue = (int)value;
                } else {
                    s.numberValue = value;
                }
                return;
            }
        }
    }

    /**
     * Scan a float. We are either looking at the decimal, or we have already
     * seen it and put it into the sbuf. We haven't seen an exponent.
     * Scan a float. Should be called with the current character is either
     * the 'e', 'E', or '.'
     */
    protected void scanReal(Scanner s) {
        boolean seenExponent = false;
        char lastChar;

        if (s.ch == '.') {
            putc(s.ch);
            s.nextchar();
        } else if (s.ch == 'e' || s.ch == 'E') {
            putc(s.ch);
            seenExponent = true;
            s.nextchar();
        }

    numberLoop:
        for (;; s.nextchar()) {
            switch (s.ch) {
              case '0': case '1': case '2': case '3': case '4':
              case '5': case '6': case '7': case '8': case '9':
                putc(s.ch);
                break;

              case '_': case '\'':
                // underscores are ignored but can be used as separator
                break;

              case 'e': case 'E':
                if (seenExponent)
                    break numberLoop; // we'll get a format error
                putc(s.ch);
                seenExponent = true;
                break;

              case '+': case '-':
                lastChar = sbuf[sp - 1];
                if (lastChar != 'e' && lastChar != 'E')
                    break numberLoop; // this isn't an error, though!
                putc(s.ch);
                break;

              default:
                break numberLoop;
            }
        }

        s.token = NUMBER;
        try {
            lastChar = sbuf[sp - 1];
            if (lastChar == 'e' || lastChar == 'E' || lastChar == '+' || lastChar == '-') {
                throw s.parseError(_T(EL_FLOATING_LITERAL_FORMAT_ERROR));
            } else if (s.ch == 'm' || s.ch == 'M') {
                s.nextchar();
                s.numberValue = Decimal.valueOf(sbuf());
            } else if (s.ch == 'b' || s.ch == 'B') {
                s.nextchar();
                s.numberValue = new BigDecimal(sbuf());
            } else if (s.ch == 'r' || s.ch == 'R') {
                s.nextchar();
                s.numberValue = Rational.valueOf(new BigDecimal(sbuf()));
            } else {
                String str = sbuf();
                double dbl = Double.parseDouble(str);
                if (Double.isInfinite(dbl)) {
                    throw s.parseError(_T(EL_FLOATING_LITERAL_OVERFLOW));
                } else if (dbl == 0 && !looksLikeZero(str)) {
                    throw s.parseError(_T(EL_FLOATING_LITERAL_UNDERFLOW));
                }
                s.numberValue = dbl;
            }
        } catch (ArithmeticException ex) {
            throw s.parseError(_T(EL_FLOATING_LITERAL_OVERFLOW));
        } catch (NumberFormatException ex) {
            throw s.parseError(_T(EL_FLOATING_LITERAL_FORMAT_ERROR));
        }
    }

    // We have a token that parses as a number. Is this token possibly zero?
    // i.e. does it have a non-zero value in the mantissa?
    private static boolean looksLikeZero(String token) {
        int length = token.length();
        for (int i = 0; i < length; i++) {
            switch (token.charAt(i)) {
              case 0: case '.':
                continue;
              case '1': case '2': case '3': case '4': case '5':
              case '6': case '7': case '8': case '9':
                return false;
              case 'e': case 'E':
                return true;
            }
        }
        return true;
    }

    /**
     * Scan an escape character.
     */
    protected int scanEscapeChar(Scanner s, int c) {
        switch (c) {
          case '0': case '1': case '2': case '3':
          case '4': case '5': case '6': case '7':
            int n = s.ch - '0';
            for (int i = 0; i < 2; i++) {
                switch (s.nextchar()) {
                  case '0': case '1': case '2': case '3':
                  case '4': case '5': case '6': case '7':
                    n = (n << 3) + s.ch - '0';
                    break;

                  default:
                    return n;
                }
            }
            if (n > 0xff)
                throw s.parseError(_T(EL_ILLEGAL_ESCAPE_CHAR));
            s.nextchar();
            return n;

          case 'u': case 'U':
            int u = 0;
            for (int i = 0; i < 4; i++) {
                switch (s.nextchar()) {
                  case '0': case '1': case '2': case '3': case '4':
                  case '5': case '6': case '7': case '8': case '9':
                    u = (u << 4) + s.ch - '0';
                    break;

                  case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
                    u = (u << 4) + s.ch - 'a' + 10;
                    break;

                  case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
                    u = (u << 4) + s.ch - 'A' + 10;
                    break;

                  default:
                    throw s.parseError(_T(EL_ILLEGAL_ESCAPE_CHAR));
                }
            }
            s.nextchar();
            return u;

          case 'r':  s.nextchar(); return '\r';
          case 'n':  s.nextchar(); return '\n';
          case 'f':  s.nextchar(); return '\f';
          case 'b':  s.nextchar(); return '\b';
          case 't':  s.nextchar(); return '\t';
          case 's':  s.nextchar(); return ' ';
          case '\\': s.nextchar(); return '\\';
          case '\"': s.nextchar(); return '\"';
          case '\'': s.nextchar(); return '\'';
        }

        throw s.parseError(_T(EL_ILLEGAL_ESCAPE_CHAR));
    }

    /**
     * Scan a string. The current character should be the opening
     * ' or " of the string.
     */
    protected void scanString(Scanner s) {
        int spos = s.pos;
        int delim = s.ch;
        boolean multiline = false;
        s.token = STRINGVAL;
        s.charValue = (char)delim;
        sp = 0;

        if (s.nextchar() == delim) {
            if (s.nextchar() == delim) {
                s.nextchar();
                multiline = true;
            } else {
                s.stringValue = "";
                return;
            }
        }

        while (true) {
            switch (s.ch) {
              case EOI:
                if (multiline) {
                    throw s.incomplete(spos, _T(EL_UNTERMINATED_STRING));
                } else {
                    throw s.parseError(spos, _T(EL_UNTERMINATED_STRING));
                }

              case '\'': case '"':
                if (s.ch == delim) {
                    if (!multiline) {
                        s.nextchar();
                        s.stringValue = sbuf();
                        return;
                    }
                    if (s.nextchar() != delim) {
                        putc(delim);
                        break;
                    }
                    if (s.nextchar() != delim) {
                        putc(delim);
                        putc(delim);
                        break;
                    }
                    s.nextchar();
                    s.stringValue = sbuf();
                    return;
                } else {
                    putc(s.ch);
                    s.nextchar();
                }
                break;

              case '\\': {
                int c = s.nextchar();
                if (c == '\r' || c == '\n') {
                    if (s.nextchar() == '\n' && c == '\r') {
                        s.nextchar();
                    }
                    s.pos = Position.nextline(s.pos);
                } else {
                    if (delim == '\'') {
                        if (c == '\'') { // \\'
                            putc('\'');
                            s.nextchar();
                        } else {
                            putc('\\');
                        }
                    } else if (c == '$' || c == '#' || c == '\\') {
                        putc('\\'); // backslash will be removed by parser
                        putc(c);
                        s.nextchar();
                    } else {
                        putc(scanEscapeChar(s, c));
                    }
                }
                break;
              }

              case '\r':
                if (multiline) {
                    putc('\r');
                    if (s.nextchar() == '\n') {
                        putc('\n');
                        s.nextchar();
                    }
                    s.pos = Position.nextline(s.pos);
                } else {
                    throw s.parseError(spos, _T(EL_UNTERMINATED_STRING));
                }
                break;

              case '\n':
                if (multiline) {
                    putc('\n');
                    s.nextchar();
                    s.pos = Position.nextline(s.pos);
                } else {
                    throw s.parseError(spos, _T(EL_UNTERMINATED_STRING));
                }
                break;

              default:
                putc(s.ch);
                s.nextchar();
                break;
            }
        }
    }

    /**
     * Scan a character.
     */
    protected void scanCharacter(Scanner s) {
        s.token = CHARVAL;

        switch (s.nextchar()) {
        case '\\':
            s.charValue = (char)scanEscapeChar(s, s.nextchar());
            break;

        case '\r':
        case '\n':
            throw s.parseError("Invalid character literal.");

        default:
            s.charValue = (char)s.ch;
            s.nextchar();
            break;
        }

        if (s.ch != '\'')
            throw s.parseError("Invalid character literal.");
        s.nextchar();
    }

    /**
     * Scan an Identifier. The current character should be the first character
     * of the identifier.
     */
    protected void scanIdentifier(Scanner s) {
        sp = 0;

        while (true) {
            putc(s.ch);
            switch (s.nextchar()) {
              case 'a': case 'b': case 'c': case 'd': case 'e':
              case 'f': case 'g': case 'h': case 'i': case 'j':
              case 'k': case 'l': case 'm': case 'n': case 'o':
              case 'p': case 'q': case 'r': case 's': case 't':
              case 'u': case 'v': case 'w': case 'x': case 'y':
              case 'z':
              case 'A': case 'B': case 'C': case 'D': case 'E':
              case 'F': case 'G': case 'H': case 'I': case 'J':
              case 'K': case 'L': case 'M': case 'N': case 'O':
              case 'P': case 'Q': case 'R': case 'S': case 'T':
              case 'U': case 'V': case 'W': case 'X': case 'Y':
              case 'Z':
              case '0': case '1': case '2': case '3': case '4':
              case '5': case '6': case '7': case '8': case '9':
              case '_': case '$': case '\'':
                break;

              default:
                if (!Character.isJavaIdentifierPart((char)s.ch)) {
                    String str = sbuf().intern();
                    s.idValue = str;
                    if (allow_keywords) {
                        Integer key = s.keywords.get(str);
                        s.token = (key != null) ? key : IDENT;
                    } else {
                        s.token = IDENT;
                    }
                    return;
                }
            }
        }
    }

    /**
     * Scan the next token.
     */
    public void scan(Scanner s) {
        int c;

        switch (s.ch) {
        case EOI:
            s.token = EOI;
            return;

        case '0':
            if (s.lookahead(0) == 'x' || s.lookahead(0) == 'X') {
                s.nextchar();
                scanHexadecimal(s);
            } else {
                scanNumber(s);
            }
            return;

        case '1': case '2': case '3': case '4':
        case '5': case '6': case '7': case '8': case '9':
            scanNumber(s);
            return;

        case '.':
            c = s.lookahead(0);
            if (c >= '0' && c <= '9') {
                sp = 0;
                putc('.');
                s.nextchar();
                scanReal(s);
                return;
            }
            break;

        case '#':
            if (s.lookahead(0) == '\'') {
                s.nextchar();
                scanCharacter(s);
                return;
            }
            break;

        default:
            if (allow_keywords && Character.isJavaIdentifierStart(s.ch)) {
                scanIdentifier(s);
                return;
            }
            break;
        }

        Operator accept = null;
        int last = s.next;
        int lastch = s.ch;
        int lastpos = s.pos;

        State t = start;
        while (s.ch != EOI && (t = dispatch(t, s.ch)) != null) {
            s.nextchar();
            if (t.accept != null) {
                // save state for this accepted operator
                accept  = t.accept;
                last    = s.next;
                lastch  = s.ch;
                lastpos = s.pos;
            }
        }

        // reset position
        s.next = last;
        s.ch   = lastch;
        s.pos  = lastpos;

        if (accept != null) {
            s.operator = accept;
            s.token    = accept.token;
        } else {
            if (s.ch == '\'' || s.ch == '"') {
                scanString(s);
            } else if (Character.isJavaIdentifierStart(s.ch)) {
                scanIdentifier(s);
            } else {
                s.token = UNKNOWN;
            }
        }
    }

    static {
        shared.addOperator("{", LBRACE, -1);
        shared.addOperator("}", RBRACE, -1);
        shared.addOperator("(", LPAREN, -1);
        shared.addOperator(")", RPAREN, -1);
        shared.addOperator("[", LBRACKET, -1);
        shared.addOperator("]", RBRACKET, -1);
        shared.addOperator("?", QUESTIONMARK, -1);
        shared.addOperator("??", COALESCE, -1);
        shared.addOperator("??=", ASSIGNOP, COALESCE);
        shared.addOperator(":", COLON, -1);
        shared.addOperator("::", COLONCOLON, -1);
        shared.addOperator("@", ATSIGN, -1);
        shared.addOperator("#", HASH, -1);
        shared.addOperator(".", FIELD, -1);
        shared.addOperator("..", RANGE, -1);
        shared.addOperator("...", ELLIPSIS, -1);
        shared.addOperator(",", COMMA, -1);
        shared.addOperator(";", SEMI, -1);
        shared.addOperator("-", SUB, -1);
        shared.addOperator("-=", ASSIGNOP, SUB);
        shared.addOperator("--", DEC, -1);
        shared.addOperator("->", XFORM, -1);
        shared.addOperator("+", ADD, -1);
        shared.addOperator("+=", ASSIGNOP, ADD);
        shared.addOperator("++", INC, -1);
        shared.addOperator("<", LT, -1);
        shared.addOperator("<=", LE, -1);
        shared.addOperator("<-", IN, -1);
        shared.addOperator("<<", SHL, -1);
        shared.addOperator("<<=", ASSIGNOP, SHL);
        shared.addOperator(">", GT, -1);
        shared.addOperator(">=", GE, -1);
        shared.addOperator(">>", SHR, -1);
        shared.addOperator(">>>", USHR, -1);
        shared.addOperator(">>=", ASSIGNOP, SHR);
        shared.addOperator(">>>=", ASSIGNOP, USHR);
        shared.addOperator("=", ASSIGN, -1);
        shared.addOperator("==", EQ, -1);
        shared.addOperator("===", IDEQ, -1);
        shared.addOperator("=>", ARROW, -1);
        shared.addOperator("\\", LAMBDA, -1);
        shared.addOperator("!", NOT, -1);
        shared.addOperator("!=", NE, -1);
        shared.addOperator("!==", IDNE, -1);
        shared.addOperator("!?", SAFEREF, -1);
        shared.addOperator("|", BAR, -1);
        shared.addOperator("||", OR, -1);
        shared.addOperator("&", LAZY, -1);
        shared.addOperator("&&", AND, -1);
        shared.addOperator("^", POW, -1);
        shared.addOperator("^=", ASSIGNOP, POW);
        shared.addOperator("^!", BITNOT, -1);
        shared.addOperator("^|", BITOR, -1);
        shared.addOperator("^|=", ASSIGNOP, BITOR);
        shared.addOperator("^&", BITAND, -1);
        shared.addOperator("^&=", ASSIGNOP, BITAND);
        shared.addOperator("^^", XOR, -1);
        shared.addOperator("^^=", ASSIGNOP, XOR);
        shared.addOperator("~", CAT, -1);
        shared.addOperator("~=", ASSIGNOP, CAT);
        shared.addOperator("/", DIV, -1);
        shared.addOperator("/=", ASSIGNOP, DIV);
        shared.addOperator("%", REM, -1);
        shared.addOperator("%=", ASSIGNOP, REM);
        shared.addOperator("*", MUL, -1);
        shared.addOperator("*=", ASSIGNOP, MUL);

        shared.addOperator("module", MODULE, -1); // FIXME
        shared.addOperator("and", "&&", AND, -1);
        shared.addOperator("or", "||", OR, -1);
        shared.addOperator("eq", "==", EQ, -1);
        shared.addOperator("ne", "!=", NE, -1);
        shared.addOperator("lt", "<", LT, -1);
        shared.addOperator("gt", ">", GT, -1);
        shared.addOperator("le", "<=", LE, -1);
        shared.addOperator("ge", ">=", GE, -1);
        shared.addOperator("div", "div", IDIV, -1);
        shared.addOperator("mod", "%", REM, -1);
        shared.addOperator("xor", "^^", XOR, -1);
        shared.addOperator("shl", "<<", SHL, -1);
        shared.addOperator("shr", ">>", SHR, -1);
        shared.addOperator("ushr", ">>>", USHR, -1);
        shared.addOperator("is", "is", INSTANCEOF, -1);
        shared.addOperator("in", "<-", IN, -1);

        shared.addOperator("×", "*", MUL, -1);
        shared.addOperator("÷", "/", DIV, -1);
        shared.addOperator("≠", "!=", NE, -1);
        shared.addOperator("≤", "<=", LE, -1);
        shared.addOperator("≥", ">=", GE, -1);
        shared.addOperator("∈", "<-", IN, -1);
        shared.addOperator("λ", "\\", LAMBDA, -1);
        shared.addOperator("→", "->", XFORM, -1);
        shared.addOperator("⇒", "=>", ARROW, -1);
        shared.addOperator("¬", "!", NOT, -1);
        shared.addOperator("∧", "&&", AND, -1);
        shared.addOperator("∨", "||", OR, -1);
        shared.addOperator("⊕", "^^", XOR, -1);
        shared.addOperator("⊻", "^^", XOR, -1);
    }
}
