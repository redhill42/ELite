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

import java.lang.reflect.Modifier;

public final class Token
{
    /**
     * End of input
     */
    public static final int EOI             = -1;               // end of input
    public static final int UNKNOWN         = 0;                // unknown

    // Operators (in the precedence order)
    public static final int ASSIGN          = 1 + UNKNOWN;      // x=y
    public static final int ASSIGNOP        = 1 + ASSIGN;       // x+=y
    public static final int PREFIX          = 1 + ASSIGNOP;     // op x
    public static final int INFIX           = 1 + PREFIX;       // x op y
    public static final int KEYWORD         = 1 + INFIX;        // syntax rules keyword
    public static final int COND            = 1 + KEYWORD;      // x?y:z
    public static final int COALESCE        = 1 + COND;         // x??y
    public static final int SAFEREF         = 1 + COALESCE;     // x!?y
    public static final int OR              = 1 + SAFEREF;      // x||y
    public static final int AND             = 1 + OR;           // x&&y
    public static final int BITOR           = 1 + AND;          // x^|y
    public static final int XOR             = 1 + BITOR;        // x^^y
    public static final int BITAND          = 1 + XOR;          // x^&y
    public static final int EQ              = 1 + BITAND;       // x==y
    public static final int NE              = 1 + EQ;           // x!=y
    public static final int IDEQ            = 1 + NE;           // x===y
    public static final int IDNE            = 1 + IDEQ;         // x!==y
    public static final int LT              = 1 + IDNE;         // x<y
    public static final int GT              = 1 + LT;           // x>y
    public static final int LE              = 1 + GT;           // x<=y
    public static final int GE              = 1 + LE;           // x>=y
    public static final int SHL             = 1 + GE;           // x<<y
    public static final int SHR             = 1 + SHL;          // x>>y
    public static final int USHR            = 1 + SHR;          // x>>>y
    public static final int CAT             = 1 + USHR;         // x~y
    public static final int ADD             = 1 + CAT;          // x+y
    public static final int SUB             = 1 + ADD;          // x-y
    public static final int MUL             = 1 + SUB;          // x*y
    public static final int DIV             = 1 + MUL;          // x/y
    public static final int IDIV            = 1 + DIV;          // x div y
    public static final int REM             = 1 + IDIV;         // x%y
    public static final int POW             = 1 + REM;          // x^y
    public static final int INSTANCEOF      = 1 + POW;          // x instanceof y
    public static final int IN              = 1 + INSTANCEOF;   // x in y
    public static final int NOT             = 1 + IN;           // !x
    public static final int BITNOT          = 1 + NOT;          // ^!x
    public static final int POS             = 1 + BITNOT;       // +x
    public static final int NEG             = 1 + POS;          // -x
    public static final int INC             = 1 + NEG;          // ++x
    public static final int DEC             = 1 + INC;          // --x
    public static final int EMPTY           = 1 + DEC;          // empty x
    public static final int XFORM           = 1 + EMPTY;        // x->f
    public static final int EXPR            = 1 + XFORM;        // (x)
    public static final int FIELD           = 1 + EXPR;         // x.y
    public static final int ACCESS          = 1 + FIELD;        // x[y]
    public static final int APPLY           = 1 + ACCESS;       // x()
    public static final int NEW             = 1 + APPLY;        // new x()

    // Value tokens
    public static final int LITERAL         = 1 + NEW;
    public static final int IDENT           = 1 + LITERAL;
    public static final int CONST           = 1 + IDENT;
    public static final int BOOLEANVAL      = 1 + CONST;
    public static final int CHARVAL         = 1 + BOOLEANVAL;
    public static final int NUMBER          = 1 + CHARVAL;
    public static final int SYMBOL          = 1 + NUMBER;
    public static final int STRINGVAL       = 1 + SYMBOL;
    public static final int CLASS           = 1 + STRINGVAL;
    public static final int ARRAY           = 1 + CLASS;
    public static final int CONS            = 1 + ARRAY;
    public static final int NIL             = 1 + CONS;
    public static final int TUPLE           = 1 + NIL;
    public static final int MAP             = 1 + TUPLE;
    public static final int RANGE           = 1 + MAP;
    public static final int AST             = 1 + RANGE;
    public static final int XML             = 1 + AST;
    public static final int LAMBDA          = 1 + XML;
    public static final int METADATA        = 1 + LAMBDA;
    public static final int METASET         = 1 + METADATA;

    // Modifier keywords
    public static final int PUBLIC          = 1 + METASET;
    public static final int PROTECTED       = 1 + PUBLIC;
    public static final int PRIVATE         = 1 + PROTECTED;
    public static final int STATIC          = 1 + PRIVATE;
    public static final int FINAL           = 1 + STATIC;
    public static final int ABSTRACT        = 1 + FINAL;
    public static final int SYNCHRONIZED    = 1 + ABSTRACT;

    // Expression keywords
    public static final int TRUE            = 1 + SYNCHRONIZED;
    public static final int FALSE           = 1 + TRUE;
    public static final int NULL            = 1 + FALSE;
    public static final int VOID            = 1 + NULL;

    // Punctuation
    public static final int COLON           = 1 + VOID;         // :
    public static final int COLONCOLON      = 1 + COLON;        // ::
    public static final int QUESTIONMARK    = 1 + COLONCOLON;   // ?
    public static final int LPAREN          = 1 + QUESTIONMARK; // (
    public static final int RPAREN          = 1 + LPAREN;       // )
    public static final int LBRACKET        = 1 + RPAREN;       // [
    public static final int RBRACKET        = 1 + LBRACKET;     // ]
    public static final int LBRACE          = 1 + RBRACKET;     // {
    public static final int RBRACE          = 1 + LBRACE;       // }
    public static final int BAR             = 1 + RBRACE;       // |
    public static final int LAZY            = 1 + BAR;          // &
    public static final int ATSIGN          = 1 + LAZY;         // @
    public static final int HASH            = 1 + ATSIGN;       // #
    public static final int ARROW           = 1 + HASH;         // =>
    public static final int ELLIPSIS        = 1 + ARROW;        // ...
    public static final int COMMA           = 1 + ELLIPSIS;     // ,
    public static final int SEMI            = 1 + COMMA;        // ;

    // keywords
    public static final int REQUIRE         = 1 + SEMI;
    public static final int IMPORT          = 1 + REQUIRE;
    public static final int MODULE          = 1 + IMPORT;
    public static final int GRAMMAR         = 1 + MODULE;
    public static final int DEFINE          = 1 + GRAMMAR;
    public static final int CLASSDEF        = 1 + DEFINE;
    public static final int EXTENDS         = 1 + CLASSDEF;
    public static final int IMPLEMENTS      = 1 + EXTENDS;
    public static final int UNDEF           = 1 + IMPLEMENTS;
    public static final int LET             = 1 + UNDEF;
    public static final int IF              = 1 + LET;
    public static final int ELSE            = 1 + IF;
    public static final int FOR             = 1 + ELSE;
    public static final int WHILE           = 1 + FOR;
    public static final int SWITCH          = 1 + WHILE;
    public static final int MATCH           = 1 + SWITCH;
    public static final int CASE            = 1 + MATCH;
    public static final int DEFAULT         = 1 + CASE;
    public static final int BREAK           = 1 + DEFAULT;
    public static final int CONTINUE        = 1 + BREAK;
    public static final int RETURN          = 1 + CONTINUE;
    public static final int THROW           = 1 + RETURN;
    public static final int TRY             = 1 + THROW;
    public static final int CATCH           = 1 + TRY;
    public static final int FINALLY         = 1 + CATCH;
    public static final int ASSERT          = 1 + FINALLY;

    public static final int LALR            = 1 + ASSERT;
    public static final int MAX_TOKEN       = 1 + LALR;

    // Operator names
    public static final String opNames[] = {
        "unknown", "=", "?=", "prefix", "infix", "keyword", "?:", "??", "!?", "||", "&&",
        "^|", "^^", "^&", "==", "!=", "===", "!==", "<", ">", "<=", ">=", "<<", ">>", ">>>",
        "~", "+", "-", "*", "/", "div", "%", "^", "instanceof", "in", "not",
        "^!", "+", "-", "++", "--", "empty", "->", "()", ".", "[]", "apply", "new",
        "literal", "ident", "const", "boolean", "char", "number", "symbol", "string",
        "class", "array", "cons", "nil", "tuple", "map", "..", "ast", "xml fragment", "lambda",
        "metadata", "metaset", "public", "protected", "private", "static", "final",
        "abstract", "synchronized", "true", "false", "null", "void",
        ":", "::", "?", "(", ")", "[", "]", "{", "}", "|", "&", "@", "#", "=>", "...", ",", ";",
        "require", "import", "module", "grammar", "define", "class", "extends", "implements",
        "undef", "let", "if", "else", "for", "while", "switch", "case", "case", "default",
        "break", "continue", "return", "throw", "try", "catch", "finally", "assert", "LALR"
    };

    // Masks for modifiers
    public static final int MM_CLASS
        = Modifier.PUBLIC
        | Modifier.FINAL
        | Modifier.ABSTRACT;
    public static final int MM_MEMBER
        = Modifier.PUBLIC
        | Modifier.PRIVATE
        | Modifier.PROTECTED
        | Modifier.FINAL
        | Modifier.STATIC;
    public static final int MM_METHOD
        = Modifier.ABSTRACT
        | Modifier.SYNCHRONIZED;
    public static final int MM_VAR
        = Modifier.FINAL;
    public static final int MM_TOPLEVEL
        = Modifier.PUBLIC
        | Modifier.PRIVATE
        | Modifier.PROTECTED;
}
