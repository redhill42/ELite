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
