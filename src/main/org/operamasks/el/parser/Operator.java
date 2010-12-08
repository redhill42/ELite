/*
 * $Id: Operator.java,v 1.1 2009/06/14 05:06:32 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.parser;

public final class Operator
{
    public String name;     // The operator name
    public int token;       // The operator numeric token value
    public int token2;      // The alternate token value

    public Operator(String name, int token, int token2) {
        this.name = name;
        this.token = token;
        this.token2 = token2;
    }
}
