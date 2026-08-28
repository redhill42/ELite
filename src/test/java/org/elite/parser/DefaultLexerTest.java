package org.elite.parser;

import static org.elite.parser.Token.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import elite.lang.Decimal;
import elite.lang.Rational;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link DefaultLexer}. The scanner must be
 * primed with {@code nextchar()} before the first {@code scan()}.
 */
class DefaultLexerTest {

    // ---- helpers ---------------------------------------------------------

    private static Scanner scanner(String source) {
        Scanner s = new Scanner(source);
        s.nextchar();          // prime, like Parser.parseTopLevelExpression
        return s;
    }

    /** Scan the first token and return the scanner. */
    private static Scanner first(String source) {
        Scanner s = scanner(source);
        s.scan();
        s.failIfErrors();
        return s;
    }

    /** Scan the whole input and return the token type sequence. */
    private static List<Integer> tokens(String source) {
        Scanner s = scanner(source);
        List<Integer> list = new ArrayList<>();
        for (;;) {
            s.scan();
            list.add(s.token);
            if (s.token == EOI)
                return list;
        }
    }

    // ---- numbers ---------------------------------------------------------

    @Test
    void integerFitsInt() {
        Scanner s = first("42");
        assertEquals(NUMBER, s.token);
        assertEquals(Integer.valueOf(42), s.numberValue);
    }

    @Test
    void integerFitsLong() {
        Scanner s = first("9223372036854775807");   // Long.MAX_VALUE
        assertEquals(Long.valueOf(9223372036854775807L), s.numberValue);
    }

    @Test
    void integerOverflowsToBigInteger() {
        Scanner s = first("9223372036854775808");   // Long.MAX_VALUE + 1
        assertEquals(new BigInteger("9223372036854775808"), s.numberValue);
    }

    @Test
    void integerWithSeparators() {
        assertEquals(Integer.valueOf(1000000), first("1_000_000").numberValue);
        assertEquals(Integer.valueOf(1000000), first("1'000'000").numberValue);
        // Separators never enter the buffer, so the BigInteger fallback works.
        assertEquals(new BigInteger("1000000000000000000000"),
                     first("1_000_000_000_000_000_000_000").numberValue);
    }

    @Test
    void hexadecimal() {
        assertEquals(Integer.valueOf(16), first("0x10").numberValue);
        assertEquals(new BigInteger("2361183241434822606847"),
                     first("0x7f_ff_ff_ff_ff_ff_ff_ff_ff").numberValue);
    }

    @Test
    void hexadecimalEdgeCases() {
        // Current lenient behavior: 0x with no digits lexes as 0.
        assertEquals(Integer.valueOf(0), first("0x").numberValue);
        // 0xg splits into NUMBER(0) + IDENT(g) — locked, not an error today.
        assertEquals(List.of(NUMBER, IDENT, EOI), tokens("0xg"));
    }

    @Test
    void numberSuffixes() {
        assertEquals(new BigInteger("42"), first("42b").numberValue);
        assertEquals(Rational.valueOf(42), first("42r").numberValue);
        assertEquals(Decimal.valueOf("42"), first("42m").numberValue);
        assertEquals(new BigDecimal("1.5"), first("1.5b").numberValue);
        assertEquals(Rational.valueOf(1.5), first("1.5r").numberValue);
        assertEquals(Decimal.valueOf("1.5"), first("1.5m").numberValue);
    }

    @Test
    void floatingPointForms() {
        assertEquals(Double.valueOf(1.5), first("1.5").numberValue);
        assertEquals(Double.valueOf(0.5), first(".5").numberValue);
        assertEquals(Double.valueOf(1.0), first("1.").numberValue);
        assertEquals(Double.valueOf(1000.0), first("1e3").numberValue);
        assertEquals(Double.valueOf(0.0015), first("1.5e-3").numberValue);
        assertEquals(Double.valueOf(1000.0), first("1e+3").numberValue);
    }

    @Test
    void numberDotFieldDisambiguation() {
        // 12.toString() must lex as NUMBER FIELD IDENT, not a float.
        assertEquals(List.of(NUMBER, FIELD, IDENT, LPAREN, RPAREN, EOI),
                     tokens("12.toString()"));
        // .. is the range operator.
        assertEquals(List.of(NUMBER, RANGE, NUMBER, EOI), tokens("1..10"));
    }

    @Test
    void floatingPointErrors() {
        assertThrows(ParseException.class, () -> first("1e"));
        assertThrows(ParseException.class, () -> first("1e9999"));   // overflow
        assertThrows(ParseException.class, () -> first("1e-400"));   // underflow
        // A genuine zero underflowing is not an error (looksLikeZero guard).
        assertEquals(Double.valueOf(0.0), first("0e-400").numberValue);
    }

    // ---- strings ---------------------------------------------------------

    @Test
    void plainStrings() {
        Scanner s = first("\"abc\"");
        assertEquals(STRINGVAL, s.token);
        assertEquals("abc", s.stringValue);
        assertEquals("", first("\"\"").stringValue);
        assertEquals("", first("''").stringValue);
        assertEquals("abc", first("'abc'").stringValue);
    }

    @Test
    void doubleQuotedEscapes() {
        assertEquals("\n", first("\"\\n\"").stringValue);
        assertEquals("\t", first("\"\\t\"").stringValue);
        assertEquals("\r", first("\"\\r\"").stringValue);
        assertEquals("\b", first("\"\\b\"").stringValue);
        assertEquals("\f", first("\"\\f\"").stringValue);
        assertEquals(" ", first("\"\\s\"").stringValue);
        assertEquals("A", first("\"\\101\"").stringValue);     // octal
        assertEquals("A", first("\"\\u0041\"").stringValue);   // unicode
        assertEquals("\"", first("\"\\\"\"").stringValue);
        assertEquals("'", first("\"\\'\"").stringValue);
    }

    @Test
    void doubleQuotedIllegalEscapes() {
        assertThrows(ParseException.class, () -> first("\"\\q\""));
        assertThrows(ParseException.class, () -> first("\"\\400\""));  // > 0xff
    }

    @Test
    void singleQuotedEscapes() {
        // In single-quoted strings only \' is an escape; other backslashes
        // stay literal (asymmetry with double quotes — locked behavior).
        assertEquals("a'b", first("'a\\'b'").stringValue);
        assertEquals("a\\nb", first("'a\\nb'").stringValue);
    }

    @Test
    void interpolationEscapesKeepBackslash() {
        // Lexer keeps the backslash for $ # and \\ — the parser unescapes.
        assertEquals("\\$", first("\"\\$\"").stringValue);
        assertEquals("\\#", first("\"\\#\"").stringValue);
        assertEquals("\\\\", first("\"\\\\\"").stringValue);
    }

    @Test
    void multilineStringsAreVerbatim() {
        // Maintainer decision (2026-08-25): no indentation stripping.
        assertEquals("\n  a\n", first("\"\"\"\n  a\n\"\"\"").stringValue);
        // CRLF is preserved.
        assertEquals("a\r\nb", first("\"\"\"a\r\nb\"\"\"").stringValue);
        // Backslash-newline is a line continuation.
        assertEquals("ab", first("\"\"\"a\\\nb\"\"\"").stringValue);
        // Single-quoted multiline: \n stays literal, same asymmetry.
        assertEquals("a\\nb", first("'''a\\nb'''").stringValue);
    }

    @Test
    void stringEdgeCases() {
        // Two-quote form is an empty string, not a multiline opener.
        assertEquals("", first("\"\"").stringValue);
        // A quote of the other kind does not close the string.
        assertEquals("a\"b", first("'a\"b'").stringValue);
    }

    @Test
    void unterminatedStrings() {
        assertThrows(ParseException.class, () -> first("\"abc"));
        assertThrows(ParseException.class, () -> first("'abc"));
        // Multiline unterminated is Incomplete (REPL continuation signal).
        assertThrows(ParseException.class, () -> first("\"\"\"abc"));
    }

    // ---- characters ------------------------------------------------------

    @Test
    void characterLiterals() {
        Scanner s = first("#'a'");
        assertEquals(CHARVAL, s.token);
        assertEquals('a', s.charValue);
        assertEquals('\n', first("#'\\n'").charValue);
        assertEquals('A', first("#'\\101'").charValue);
    }

    @Test
    void characterLiteralErrors() {
        assertThrows(ParseException.class, () -> first("#'ab'"));   // too long
        assertThrows(ParseException.class, () -> first("#'a"));     // unclosed
        assertThrows(ParseException.class, () -> first("#'\n'"));   // raw newline
    }

    // ---- operators -------------------------------------------------------

    @Test
    void maximalMunch() {
        assertEquals(IDEQ, first("===").token);
        assertEquals(EQ, first("==").token);
        assertEquals(CMP, first("<=>").token);
        assertEquals(ASSIGNOP, first(">>>=").token);
        assertEquals(USHR, first(">>>").token);
        assertEquals(SHR, first(">>").token);
        assertEquals(GE, first(">=").token);
        assertEquals(XFORM, first("->").token);
        assertEquals(ARROW, first("=>").token);
        assertEquals(IN, first("<-").token);
        assertEquals(ELLIPSIS, first("...").token);
        assertEquals(RANGE, first("..").token);
        assertEquals(AND, first("&&").token);
        assertEquals(LAZY, first("&").token);
        assertEquals(OR, first("||").token);
        assertEquals(BAR, first("|").token);
    }

    @Test
    void unicodeOperators() {
        assertEquals(MUL, first("×").token);
        assertEquals(DIV, first("÷").token);
        assertEquals(NE, first("≠").token);
        assertEquals(LE, first("≤").token);
        assertEquals(GE, first("≥").token);
        assertEquals(IN, first("∈").token);
        assertEquals(XFORM, first("→").token);
        assertEquals(ARROW, first("⇒").token);
        assertEquals(NOT, first("¬").token);
        assertEquals(AND, first("∧").token);
        assertEquals(OR, first("∨").token);
        assertEquals(XOR, first("⊕").token);
        assertEquals(XOR, first("⊻").token);
    }

    @Test
    void lambdaGlyphScansAsIdentifier() {
        // λ is a Java identifier character, so it lexes as IDENT; the
        // parser-side mapping to LAMBDA is missing (fix deferred by
        // maintainer). Lock the current behavior.
        assertEquals(List.of(IDENT, IDENT, EOI), tokens("λ x"));
    }

    @Test
    void identifierOperatorsRegistered() {
        // These lex as IDENT; the REGISTRATION (for the parser) lives in the
        // lexer's operators map — Scanner.operator is null for IDENT tokens.
        assertEquals(IDENT, first("div").token);
        assertEquals(IDIV, op("div").token);
        assertEquals(REM, op("mod").token);
        assertEquals(XOR, op("xor").token);
        assertEquals(INSTANCEOF, op("is").token);
        assertEquals(IN, op("in").token);
        assertEquals(SHL, op("shl").token);
        assertEquals(SHR, op("shr").token);
        assertEquals(USHR, op("ushr").token);
        assertEquals(AND, op("and").token);
        assertEquals(OR, op("or").token);
    }

    /** The registered Operator for an identifier-shaped operator. */
    private static Operator op(String src) {
        Operator o = first(src).lexer.getOperator(src);
        assertNotNull(o, "operator not registered: " + src);
        return o;
    }

    @Test
    void unknownToken() {
        // § is not an identifier start (unlike €, which JLS §3.8 accepts
        // as a currency identifier character) and not a registered operator.
        assertEquals(UNKNOWN, first("§").token);
    }

    // ---- identifiers and keywords ----------------------------------------

    @Test
    void identifierCharacters() {
        assertEquals("x", first("x").idValue);
        assertEquals("a1_b", first("a1_b").idValue);
        assertEquals("$foo", first("$foo").idValue);
        assertEquals("it's", first("it's").idValue);   // apostrophe in ids
        assertEquals("变量", first("变量").idValue);    // CJK identifiers
    }

    @Test
    void keywords() {
        assertEquals(IF, first("if").token);
        assertEquals(DEFINE, first("define").token);
        assertEquals(CLASSDEF, first("class").token);
        assertEquals(REQUIRE, first("require").token);
        assertEquals(TRUE, first("true").token);
        assertEquals(NULL, first("null").token);
    }

    @Test
    void keywordToggle() {
        // allowKeywords(false): only the EL subset stays reserved.
        Scanner s = scanner("if");
        s.allowKeywords(false);
        s.scan();
        assertEquals(IDENT, s.token);          // "if" is not an EL keyword
        Scanner t = scanner("true");
        t.allowKeywords(false);
        t.scan();
        assertEquals(TRUE, t.token);           // "true" is an EL keyword
    }

    // ---- comments (scanner level, not lexer) -----------------------------

    @Test
    void lineComments() {
        assertEquals(List.of(NUMBER, NUMBER, EOI),
                     tokensWithComments("42 // c\n43"));
    }

    @Test
    void blockComments() {
        assertEquals(List.of(NUMBER, NUMBER, EOI),
                     tokensWithComments("42 /* multi\nline */ 43"));
    }

    @Test
    void unterminatedBlockComment() {
        Scanner s = scanner("42 /* comment");
        s.allowComment(true);
        s.setInteractive(true);
        s.scan();       // 42
        assertThrows(IncompleteException.class, s::scan);   // EOF in comment
    }

    @Test
    void commentsDisabledByDefault() {
        // Without allowComment, // is the DIV operator twice.
        assertEquals(List.of(NUMBER, DIV, DIV, IDENT, EOI),
                     tokens("42 // c"));
    }

    /** tokens() with comments enabled. */
    private static List<Integer> tokensWithComments(String source) {
        Scanner s = scanner(source);
        s.allowComment(true);
        List<Integer> list = new ArrayList<>();
        for (;;) {
            s.scan();
            list.add(s.token);
            if (s.token == EOI)
                return list;
        }
    }

    // ---- shared lexer / copy-on-write ------------------------------------

    private static final int TEST_OP = 9000;   // arbitrary free token id

    @AfterEach
    void removeProbeOperator() {
        // Tests below mutate the SHARED lexer on purpose; always restore it
        // so other test classes in the same JVM never see "±".
        DefaultLexer.newInstance().removeOperator("±");
    }

    @Test
    void newInstancesShareOperators() {
        DefaultLexer a = DefaultLexer.newInstance();
        DefaultLexer b = DefaultLexer.newInstance();
        a.addOperator("±", "±", TEST_OP, -1);
        // Sharing: b sees the operator added through a.
        assertNotNull(b.getOperator("±"));
    }

    @Test
    void dirtyCopyIsolates() {
        DefaultLexer a = DefaultLexer.newInstance();
        DefaultLexer b = DefaultLexer.newInstance();
        a.dirtyCopy();
        a.addOperator("±", "±", TEST_OP, -1);
        // After a's dirtyCopy, b no longer sees a's additions.
        assertNull(b.getOperator("±"));
        // And a can still remove it without touching anyone else.
        a.removeOperator("±");
        assertNull(a.getOperator("±"));
        assertNull(b.getOperator("±"));
    }

    @Test
    void importFromCopiesForeignOperators() {
        DefaultLexer a = DefaultLexer.newInstance();
        DefaultLexer b = DefaultLexer.newInstance();
        a.addOperator("±", "±", TEST_OP, -1);
        b.importFrom(a);
        assertEquals(TEST_OP, b.getOperator("±").token);
    }

    @Test
    void sharedLexerScansAddedOperators() {
        DefaultLexer lexer = DefaultLexer.newInstance();
        lexer.dirtyCopy();
        lexer.addOperator("±", "±", TEST_OP, -1);
        Scanner s = new Scanner("±");
        s.lexer = lexer;          // package-visible field
        s.nextchar();
        s.scan();
        assertEquals(TEST_OP, s.token);
    }
}
