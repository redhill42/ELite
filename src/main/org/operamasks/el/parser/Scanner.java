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

import java.util.Map;
import java.util.HashMap;
import javax.el.ELException;
import org.operamasks.el.eval.TypeCoercion;
import static org.operamasks.el.parser.Token.*;
import static org.operamasks.el.resources.Resources.*;

/**
 * A Scanner for EL tokens. The scanner keeps track of the current token,
 * the value of the current token (if any), and the start position of
 * the current token.
 *
 * The scan() method advances the scanner to the next token in the input.
 */
public class Scanner implements Cloneable
{
    /**
     * Current token.
     */
    public int token;

    /**
     * The position of the current token.
     */
    protected int next;

    /**
     * The position of the previous token.
     */
    protected int prev;

    /**
     * The current character.
     */
    protected int ch;

    /**
     * The previous character.
     */
    protected int prevch;

    /**
     * The file name of input.
     */
    public String filename;

    /**
     * The line and column number of the current token.
     */
    public int pos;

    /**
     * The line and column number of the previous token.
     */
    public int prevPos;

    /**
     * Token values.
     */
    public String   idValue;
    public Operator operator;
    public char     charValue;
    public Number   numberValue;
    public String   stringValue;

    protected String token_value() {
        String tok;
        if (token == EOI) {
            tok = "<EOF>";
        } else if (idValue != null) {
            tok = idValue;
        } else if (operator != null) {
            tok = operator.name;
        } else if (token == NUMBER) {
            tok = numberValue.toString();
        } else if (token == STRINGVAL) {
            tok = stringValue;
            if (tok.length() > 20)
                tok = tok.substring(0, 20) + "...";
            tok = TypeCoercion.escape(tok);
        } else if (token == CHARVAL) {
            tok = TypeCoercion.escape(charValue);
            if (tok == null) {
                tok = "#'" + charValue + "'";
            } else {
                tok = "#'" + tok + "'";
            }
        } else {
            tok = opNames[token];
        }
        return tok;
    }

    private int layout;
    private static final int SPACE_LAYOUT = 0x01;
    private static final int NEWLINE_LAYOUT = 0x02;

    // The shared mark state, for simple mark/reset
    private Scanner mark;

    // The input buffer
    private char[] buf;
    private int buflen;

    // flag to enable comments
    protected boolean allowComment;

    public void allowComment(boolean allowance) {
        allowComment = allowance;
    }

    // The currently used keyword table
    protected Map<String,Integer> keywords = xelKeywords;

    // Enable/disable ELite keywords. When ELite keywords
    // disabled then only EL keywords are enabled.
    public void allowKeywords(boolean allowance) {
        keywords = allowance ? xelKeywords : elKeywords;
    }

    // The ELite keywords
    private static Map<String,Integer> xelKeywords = new HashMap<String, Integer>();
    static {
        xelKeywords.put("not", NOT);
        xelKeywords.put("true", TRUE);
        xelKeywords.put("false", FALSE);
        xelKeywords.put("null", NULL);
        xelKeywords.put("void", VOID);
        xelKeywords.put("empty", EMPTY);
        xelKeywords.put("instanceof", INSTANCEOF);
        xelKeywords.put("new", NEW);
        xelKeywords.put("require", REQUIRE);
        xelKeywords.put("import", IMPORT);
        xelKeywords.put("grammar", GRAMMAR);
        xelKeywords.put("define", DEFINE);
        xelKeywords.put("undef", UNDEF);
        xelKeywords.put("public", PUBLIC);
        xelKeywords.put("protected", PROTECTED);
        xelKeywords.put("private", PRIVATE);
        xelKeywords.put("static", STATIC);
        xelKeywords.put("final", FINAL);
        xelKeywords.put("abstract", ABSTRACT);
        xelKeywords.put("class", CLASSDEF);
        xelKeywords.put("extends", EXTENDS);
        xelKeywords.put("implements", IMPLEMENTS);
        xelKeywords.put("let", LET);
        xelKeywords.put("if", IF);
        xelKeywords.put("else", ELSE);
        xelKeywords.put("for", FOR);
        xelKeywords.put("while", WHILE);
        xelKeywords.put("switch", SWITCH);
        xelKeywords.put("case", CASE);
        xelKeywords.put("default", DEFAULT);
        xelKeywords.put("break", BREAK);
        xelKeywords.put("continue", CONTINUE);
        xelKeywords.put("return", RETURN);
        xelKeywords.put("throw", THROW);
        xelKeywords.put("try", TRY);
        xelKeywords.put("catch", CATCH);
        xelKeywords.put("finally", FINALLY);
        xelKeywords.put("synchronized", SYNCHRONIZED);
        xelKeywords.put("assert", ASSERT);
    }

    // The EL keywords that always enabled
    private static Map<String,Integer> elKeywords = new HashMap<String,Integer>();
    static {
        elKeywords.put("not", NOT);
        elKeywords.put("true", TRUE);
        elKeywords.put("false", FALSE);
        elKeywords.put("null", NULL);
        elKeywords.put("empty", EMPTY);
        elKeywords.put("instanceof", INSTANCEOF);
        elKeywords.put("new", NEW);
        elKeywords.put("let", LET);
    }

    protected Lexer lexer = DefaultLexer.newInstance();
    protected static final Operator NULL_OPERATOR = new Operator(null, -1, -1);

    protected void addOperator(String id, int token, int token2) {
        lexer.dirtyCopy();
        lexer.addOperator(id, token, token2);
    }

    protected void removeOperator(String id) {
        lexer.dirtyCopy();
        lexer.removeOperator(id);
    }
    
    protected void restoreOperator(Operator op) {
        if (op != null) {
            if (op == NULL_OPERATOR) {
                removeOperator(op.name);
            } else {
                addOperator(op.name, op.token, op.token2);
            }
        }
    }

    protected Operator getOperator(String id) {
        return lexer.getOperator(id);
    }

    /**
     * Create a scanner to scan an input string.
     */
    public Scanner(String expression) {
        buf = expression.toCharArray();
        buflen = buf.length;
        next = 0;
        pos = Position.FIRSTPOS;
        mark = save();
    }

    /**
     * Set the file name of input.
     */
    public void setFileName(String filename) {
        this.filename = filename;
    }

    /**
     * Set the current line number.
     */
    public void setLineNumber(int line) {
        this.pos = Position.make(line, 1);
    }

    /**
     * Raise a parse exception.
     */
    protected ELException parseError(String message) {
        return parseError(prevPos, message);
    }

    /**
     * Raise a parse exception.
     */
    protected ELException parseError(int pos, String message) {
        return new ParseException(filename, Position.line(pos), Position.column(pos), message);
    }

    /**
     * Raise an incomplete parse exception.
     */
    protected ELException incomplete(String message) {
        return incomplete(prevPos, message);
    }

    /**
     * Raise an incomplete parse exception.
     */
    protected ELException incomplete(int pos, String message) {
        return new IncompleteException(filename, Position.line(pos), Position.column(pos), message);
    }

    /**
     * Read next character.
     */
    protected int nextchar() {
        if (next < buflen) {
            pos++;
            return ch = buf[next++];
        } else {
            return ch = EOI;
        }
    }

    /**
     * Lookahead next character.
     */
    protected int lookahead(int n) {
        return (next+n < buflen) ? buf[next+n] : EOI;
    }

    /**
     * Save current state for restore.
     */
    protected Scanner save() {
        try {
            return (Scanner)super.clone();
        } catch (CloneNotSupportedException ex) {
            throw new InternalError();
        }
    }

    /**
     * Save state to given place.
     */
    protected void save(Scanner state) {
        state.restore(this);
    }
    
    /**
     * Restore saved state.
     */
    protected void restore(Scanner state) {
        this.next        = state.next;
        this.prev        = state.prev;
        this.ch          = state.ch;
        this.prevch      = state.prevch;
        this.pos         = state.pos;
        this.prevPos     = state.prevPos;
        this.token       = state.token;
        this.idValue     = state.idValue;
        this.operator    = state.operator;
        this.charValue   = state.charValue;
        this.numberValue = state.numberValue;
        this.stringValue = state.stringValue;
        this.layout      = state.layout;
    }

    /**
     * Mark the current state for reset.
     */
    protected void mark() {
        mark.restore(this);
    }

    /**
     * Reset the marked state.
     */
    protected void reset() {
        this.restore(mark);
    }

    /**
     * Scan a regular expression.
     */
    protected String scanRegexp() {
        StringBuilder buf = new StringBuilder();

        while (true) {
            switch (ch) {
            case EOI: case '\n': case '\r':
                throw parseError(_T(EL_UNTERMINATED_STRING));

            case '/':
                nextchar();
                return buf.toString();

            case '\\':
                if (nextchar() == '/') {
                    buf.append('/');
                } else {
                    buf.append('\\');
                    buf.append((char)ch);
                }
                nextchar();
                break;

            default:
                buf.append((char)ch);
                nextchar();
                break;
            }
        }
    }

    private void skipWhitespaces() {
        layout = 0;
        while (true) {
            switch (ch) {
            case ' ': case '\t': case '\f':
                nextchar();
                layout |= SPACE_LAYOUT;
                break;

            case '\r':
                if (nextchar() == '\n')
                    nextchar();
                pos = Position.nextline(pos);
                layout |= NEWLINE_LAYOUT;
                break;

            case '\n':
                nextchar();
                pos = Position.nextline(pos);
                layout |= NEWLINE_LAYOUT;
                break;

            case '/':
                if (!allowComment) {
                    return;
                } else {
                    int c = lookahead(0);
                    if (c == '/') {
                        do {
                            c = nextchar();
                        } while (c != '\n' && c != '\r' && c != EOI);
                    } else if (c == '*') {
                        int p = pos;
                        nextchar();
                        while ((c = nextchar()) != EOI) {
                            if (c == '*' && lookahead(0) == '/') {
                                nextchar(); // the '/' char
                                nextchar(); // the real next char
                                break; // recognized comment
                            } else if (c == '\r') {
                                if (lookahead(0) == '\n')
                                    nextchar();
                                pos = Position.nextline(pos);
                            } else if (c == '\n') {
                                pos = Position.nextline(pos);
                            }
                        }
                        if (c == EOI) {
                            throw incomplete(p, "End of file in comment");
                        }
                    } else {
                        return;
                    }
                }
                break;

            default:
                if (Character.isWhitespace((char)ch)) {
                    nextchar();
                    layout |= SPACE_LAYOUT;
                    break;
                } else {
                    return;
                }
            }
        }
    }

    /**
     * Scan the next token.
     */
    public int scan() {
        prevPos  = pos;
        prev     = next;
        prevch   = ch;
        idValue  = null;
        operator = null;

        skipWhitespaces();
        if ((layout & NEWLINE_LAYOUT) != 0) {
            Operator op = lexer.getOperator("\n");
            if (op != null) {
                layout &= ~NEWLINE_LAYOUT;
                operator = op;
                token = op.token;
                return prevPos;
            }
        }

        lexer.scan(this);
        return prevPos;
    }

    /**
     * Rescan the previous token.
     */
    public int rescan() {
        if (token == EOI) {
            return EOI;
        } else {
            pos = prevPos;
            next = prev;
            ch = prevch;
            return scan();
        }
    }

    /**
     * Scan the next token if the given token is expected.
     */
    public boolean scan(int t) {
        if (token == t) {
            scan();
            return true;
        }

        if (token == IDENT) {
            Operator op = lexer.getOperator(idValue);
            if (op != null && t == op.token) {
                scan();
                return true;
            }
        }

        return false;
    }

    /**
     * Expect a token, return its value, scan the next token or
     * throw an exception.
     */
    protected void expect(int t) {
        if (token == t) {
            scan();
            return;
        }

        if (t != IDENT && token == IDENT) {
            Operator op = getOperator(idValue);
            if (op != null && t == op.token) {
                scan();
                return;
            }
        }

        switch (t) {
          case SEMI:
            if (!scanLayout()) {
                throw parseError(_T(EL_TOKEN_EXPECTED, ";", token_value()));
            }
            return;

          case IDENT:
            if (token == EOI) {
                throw incomplete(_T(EL_IDENTIFIER_EXPECTED));
            } else {
                throw parseError(_T(EL_IDENTIFIER_EXPECTED));
            }

          case EOI:
            throw parseError(_T(EL_EXTRA_CHAR_IN_INPUT));

          default:
            if (token == EOI) {
                throw incomplete(_T(EL_TOKEN_EXPECTED, opNames[t], "<EOF>"));
            } else {
                throw parseError(_T(EL_TOKEN_EXPECTED, opNames[t], token_value()));
            }
        }
    }

    public boolean scanLayout() {
        return (layout & NEWLINE_LAYOUT) != 0
            || token == SEMI || token == EOI || token == RBRACE;
    }

    public boolean sawSpace() {
        return layout != 0;
    }

    public boolean sawNewLine() {
        return (layout & NEWLINE_LAYOUT) != 0;
    }
}
