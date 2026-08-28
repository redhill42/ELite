/*
 * Copyright 2006-2026 Daniel Yuan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elite.parser;

import org.elite.eval.ELUtils;
import javax.el.ELException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elite.parser.Token.*;
import static org.elite.resources.Resources.*;

/**
 * A Scanner for EL tokens. The scanner keeps track of the current token,
 * the value of the current token (if any), and the start position of
 * the current token.
 * <p>
 * The scan() method advances the scanner to the next token in the input.
 */
public class Scanner implements Cloneable {
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
  public String idValue;
  public Operator operator;
  public char charValue;
  public Number numberValue;
  public String stringValue;

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
      tok = ELUtils.escape(tok);
    } else if (token == CHARVAL) {
      tok = ELUtils.escape(charValue);
      if (tok == null) {
        tok = "#'" + charValue + "'";
      } else {
        tok = "#'" + tok + "'";
      }
    } else if (token == UNKNOWN) {
      return String.valueOf((char)ch);
    } else {
      tok = opNames[token];
    }
    return tok;
  }

  private int layout;
  private static final int SPACE_LAYOUT = 0x01;
  private static final int NEWLINE_LAYOUT = 0x02;

  // The shared mark state, for simple mark/reset
  private final Scanner mark;

  // The input buffer
  private final char[] buf;
  private final int buflen;

  // flag to enable comments
  protected boolean allowComment;

  public void allowComment(boolean allowance) {
    allowComment = allowance;
  }

  // The currently used keyword table
  protected Map<String, Integer> keywords = xelKeywords;

  // Enable/disable ELite keywords. When ELite keywords
  // disabled then only EL keywords are enabled.
  public void allowKeywords(boolean allowance) {
    keywords = allowance ? xelKeywords : elKeywords;
  }

  // The ELite keywords
  private static final Map<String, Integer> xelKeywords = new HashMap<>();

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
  private static final Map<String, Integer> elKeywords = new HashMap<>();

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

  protected void restoreOperator(String id, Operator op) {
    if (op != null) {
      if (op == NULL_OPERATOR) {
        removeOperator(id);
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
    lineStarts.add(0);
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

  //=------------------------------------------------------------------------=//
  // Error report and recovery

  /**
   * The default maximum number of errors to record before aborting.
   */
  public static final int DEFAULT_ERROR_LIMIT = 20;

  /**
   * The maximum number of errors to record before aborting.
   */
  private int errorLimit = DEFAULT_ERROR_LIMIT;

  /**
   * Throw IncompleteException for interactive REPL.
   */
  private boolean interactive = false;

  /**
   * Errors recorded during parsing. When the error limit is reached, an
   * unrecoverable error occurs, or parsing completes with errors recorded,
   * a {@link ParseException} exception is thrown.
   */
  private final List<ParseError> errors = new ArrayList<>();

  /**
   * The start offset of each line in the input buffer, used to display
   * the source line in error messages. Built lazily and deterministically
   * from the buffer, since scanning may be rewound by mark/reset.
   */
  private final List<Integer> lineStarts = new ArrayList<>();

  /**
   * Set the maximum number of errors to record before aborting.
   */
  public void setErrorLimit(int limit) {
    this.errorLimit = limit;
  }

  public void setInteractive(boolean interactive) {
    this.interactive = interactive;
  }

  /**
   * Record a semantic error. This kind of error doesn't affect syntax
   * analysis, so parsing continues without recovery.
   */
  protected void error(int pos, String message) {
    recordError(pos, message);
  }

  protected void error(String message) {
    error(prevPos, message);
  }

  /**
   * Record a syntax error and recover by skipping tokens to a statement
   * boundary: a ';', a significant newline, or the closing bracket of the
   * enclosing bracket-delimited construct.
   */
  protected void errorRecover(int pos, String message) {
    recordError(pos, message);
    while (!scanLayout()) {
      if (token == UNKNOWN)
        nextchar();
      scan();
    }
  }

  protected void errorRecover(String message) {
    errorRecover(prevPos, message);
  }

  /**
   * Record an unrecoverable error and throw immediately. If other errors
   * were recorded, a {@link ParseException} exception aggregating them all
   * is thrown.
   */
  protected void fail(int pos, String message) {
    recordError(pos, message);
    throwErrorList();
  }

  protected void fail(String message) {
    fail(prevPos, message);
  }

  /**
   * Record an incomplete-input error and throw immediately.
   */
  protected void incomplete(int pos, String message) {
    if (interactive)
      throw new IncompleteException(filename, Position.line(pos),
                                    Position.column(pos), message);
    else
      error(pos, message);
  }

  protected void incomplete(String message) {
    incomplete(prevPos, message);
  }

  /**
   * Mark the number of recorded errors, for speculative parsing.
   */
  protected int errorMark() {
    return errors.size();
  }

  /**
   * Discard the errors recorded since the given mark. Used when a
   * speculative parse attempt failed and must leave no trace.
   */
  protected void errorReset(int mark) {
    errors.subList(mark, errors.size()).clear();
  }

  /**
   * Throw the recorded errors as a compound exception, if any.
   */
  protected void failIfErrors() {
    if (!errors.isEmpty())
      throwErrorList();
  }

  /**
   * Whether any errors have been recorded so far.
   */
  protected boolean hasErrors() {
    return !errors.isEmpty();
  }

  /**
   * The text of the source line at the given position.
   */
  protected String sourceLine(int pos) {
    int start = lineStart(Position.line(pos));
    int end = start;
    while (end < buflen && buf[end] != '\n' && buf[end] != '\r')
      end++;
    return new String(buf, start, end - start);
  }

  /**
   * Record the start offset of each line up to the given 1-based line
   * number. The line starts are derived deterministically from the input
   * buffer, because scanning may be rewound by mark/reset and the same
   * newline can be visited more than once.
   */
  private int lineStart(int line) {
    int from = lineStarts.get(lineStarts.size() - 1);
    while (lineStarts.size() < line && from < buflen) {
      int i = from;
      while (i < buflen && buf[i] != '\n' && buf[i] != '\r')
        i++;
      if (i + 1 < buflen && buf[i] == '\r' && buf[i + 1] == '\n')
        i++;
      from = i + 1;
      lineStarts.add(from);
    }
    return line <= lineStarts.size() ? lineStarts.get(line - 1) : buflen;
  }

  private void recordError(int pos, String message) {
    errors.add(new ParseError(pos, message, sourceLine(pos)));
    if (errors.size() >= errorLimit)
      throwErrorList();
  }

  private void throwErrorList() {
    errors.sort(Comparator.comparingInt(ParseError::pos));
    throw new ParseException(filename, errors);
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
    return (next + n < buflen) ? buf[next + n] : EOI;
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
    this.next          = state.next;
    this.prev          = state.prev;
    this.ch            = state.ch;
    this.prevch        = state.prevch;
    this.pos           = state.pos;
    this.prevPos       = state.prevPos;
    this.token         = state.token;
    this.idValue       = state.idValue;
    this.operator      = state.operator;
    this.charValue     = state.charValue;
    this.numberValue   = state.numberValue;
    this.stringValue   = state.stringValue;
    this.layout        = state.layout;
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
        error(_T(EL_UNTERMINATED_STRING));
        return buf.toString();

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
              incomplete(p, "End of file in comment");
              return;
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
    prev = next;
    prevch = ch;
    idValue = null;
    operator = null;

    skipWhitespaces();
    prevPos = pos;

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
   * record an error.
   * <p>
   * On error, the token is assumed to have been scanned, so that
   * parsing can continue. At end of input an incomplete exception
   * is thrown, since there is no way to recover.
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
      if (!scanLayout())
        errorRecover(_T(EL_TOKEN_EXPECTED, ";"));
      return;

    case IDENT:
      if (token == EOI)
        incomplete(_T(EL_IDENTIFIER_EXPECTED));
      else
        error(_T(EL_IDENTIFIER_EXPECTED));
      return;

    case EOI:
      error(_T(EL_EXTRA_CHAR_IN_INPUT));
      return;

    default:
      if (token == EOI)
        incomplete(_T(EL_TOKEN_EXPECTED, opNames[t]));
      else
        error(_T(EL_TOKEN_EXPECTED, opNames[t]));
      return;
    }
  }

  public boolean scanLayout() {
    return (layout & NEWLINE_LAYOUT) != 0 ||
           token == SEMI || token == EOI || token == RBRACE;
  }

  public boolean sawSpace() {
    return layout != 0;
  }

  public boolean sawNewLine() {
    return (layout & NEWLINE_LAYOUT) != 0;
  }
}
