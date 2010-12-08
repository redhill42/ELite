/*
 * $Id: ParserCombinator.java,v 1.1 2009/05/24 10:46:45 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.parser;

import java.io.File;
import java.io.Reader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import elite.ast.Expression;

public final class ParserCombinator implements Serializable
{
    private Grammar grammar;

    private static final long serialVersionUID = 6681093034146902331L;

    ParserCombinator(Grammar grammar) {
        this.grammar = grammar;
    }

    public Object parse(String text) {
        Parser parser;
        Object result;

        parser = new Parser(text);
        parser.nextchar();
        parser.scan();
        result = grammar.parse(parser);
        parser.expect(Token.EOI);

        if (result instanceof ELNode)
            result = Expression.valueOf((ELNode)result);
        return result;
    }

    public Object parse(File file) throws IOException {
        return parse(readText(file, null));
    }

    public Object parse(File file, String charset) throws IOException {
        return parse(readText(file, charset));
    }

    private String readText(File file, String charset)
        throws IOException
    {
        Reader          reader;
        StringBuilder   buf;
        char[]          cbuf;
        int             n;

        if (charset == null) {
            reader = new InputStreamReader(new FileInputStream(file));
        } else {
            reader = new InputStreamReader(new FileInputStream(file), charset);
        }

        buf = new StringBuilder();
        cbuf = new char[8192];
        while ((n = reader.read(cbuf)) != -1)
            buf.append(cbuf, 0, n);
        reader.close();
        return buf.toString();
    }
}
