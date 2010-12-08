/*
 * $Id: Lexer.java,v 1.1 2009/06/14 05:06:32 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
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
