/*
 * $Id: EmptySeq.java,v 1.3 2009/03/22 08:38:15 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
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

package org.operamasks.el.eval.seq;

import java.util.RandomAccess;
import java.io.Serializable;
import elite.lang.Seq;

public class EmptySeq extends AbstractSeq
    implements RandomAccess, Serializable
{
    private static final Seq EMPTY_SEQ = new EmptySeq();

    public static Seq make() {
        return EMPTY_SEQ;
    }

    private EmptySeq() {}

    public boolean isEmpty() {
        return true;
    }
    
    public int size() {
        return 0;
    }

    public Object get() {
        return null;
    }

    public Seq tail() {
        return this;
    }

    public Seq last() {
        return this;
    }

    public Object get(int index) {
        throw new IndexOutOfBoundsException("Index:"+index);
    }

    public boolean contains(Object obj) {
        return false;
    }

    private Object readResolve() {
        return EMPTY_SEQ;
    }
}
