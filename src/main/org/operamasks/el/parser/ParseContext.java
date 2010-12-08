/*
 * $Id: ParseContext.java,v 1.1 2009/05/20 05:50:49 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.parser;

import java.util.Map;
import java.util.LinkedHashMap;

public class ParseContext
{
    /**
     * A variable environment.
     */
    private static class Context extends LinkedHashMap<String, ELNode.DEFINE> {
        Context next;
        Context(Context next) {
            this.next = next;
        }
    }

    /**
     * The context stack top.
     */
    private Context top;

    /**
     * Push a new context at the top of stack.
     */
    public void push() {
        top = new Context(top);
    }

    /**
     * Pop context from the top of stack.
     */
    public Map<String, ELNode.DEFINE> pop() {
        Context ret = top;
        top = top.next;
        return ret;
    }

    /**
     * Put a new variable to the environment.
     */
    public ELNode.DEFINE put(String name, ELNode.DEFINE var) {
        return top.put(name, var);
    }

    /**
     * Put a variable to the environment if the it doesn't exist.
     */
    public ELNode.DEFINE putIfAbsent(String name, ELNode.DEFINE var) {
        ELNode.DEFINE prev = top.get(name);
        if (prev == null)
            top.put(name, var);
        return prev;
    }
    
    /**
     * Remove a variable from the environemnt.
     */
    public ELNode.DEFINE remove(String name) {
        return top.remove(name);
    }

    /**
     * Find the variable in the environment.
     */
    public ELNode.DEFINE get(String name) {
        for (Context env = top; env != null; env = env.next) {
            ELNode.DEFINE var = env.get(name);
            if (var != null) return var;
        }
        return null;
    }
}
