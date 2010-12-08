/*
 * $Id: ValueChangeListener.java,v 1.1 2009/03/22 08:37:27 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.eval.closure;

/**
 * A listener registered on LiteralClosure to receive value change notification.
 */
public interface ValueChangeListener
{
    /**
     * Called when literal value changed.
     */
    public void valueChanged(Object oldValue, Object newValue);
}
