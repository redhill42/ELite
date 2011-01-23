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
