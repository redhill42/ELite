/*
 * $Id: Coercible.java,v 1.1 2009/03/22 08:37:56 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.eval;

/**
 * An interface which an object implements to indicate that it will handle
 * coercion by itself.
 */
public interface Coercible
{
    /**
     * Coerce this object into a specified type.
     *
     * @param type type to coerce to
     * @return coerced object or null if cannot coerce
     * @throws ELException if failed to coerce
     */
    public Object coerce(Class type);
}
