/*
 * $Id: IncompleteException.java,v 1.1 2009/03/27 07:28:04 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.parser;

public class IncompleteException extends ParseException
{
    public IncompleteException(String file, int line, int column, String message) {
        super(file, line, column, message);
    }
}
