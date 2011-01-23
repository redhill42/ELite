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

public abstract class Lexer
{
    /**
     * Make a deep clone to ensure not shared with other lexers.
     */
    public abstract void dirtyCopy();

    /**
     * Import operators from another lexer.
     */
    public abstract void importFrom(Lexer other);

    /**
     * Add an operator.
     */
    public void addOperator(String tok, int token, int token2) {
        addOperator(tok, tok, token, token2);
    }

    /**
     * Add an operator.
     */
    public abstract void addOperator(String tok, String name, int token, int token2);

    /**
     * Remove an operator.
     */
    public abstract void removeOperator(String tok);

    /**
     * Get the operator.
     */
    public abstract Operator getOperator(String tok);

    /**
     * Scan the next token.
     */
    public abstract void scan(Scanner s);
}
