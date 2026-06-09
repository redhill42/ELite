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
        Object result = grammar.parse(text);
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
