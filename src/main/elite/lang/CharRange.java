/*
 * $Id: CharRange.java,v 1.1 2009/05/02 02:31:08 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.lang;

public interface CharRange extends Seq
{
    public char getBegin();
    public char getEnd();
    public int getStep();
    public boolean isUnbound();
}
