/*
 * Copyright 2006-2026 Daniel Yuan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.operamasks.el.parser;

import java.util.Map;
import java.util.LinkedHashMap;

public class ParseContext
{
    public enum ScopeState {
        ENTER_NESTED,
        ENTER_LOOP,
        ENTER_CLOSURE,
    };

    /**
     * A variable environment.
     */
    private static class Context extends LinkedHashMap<String, ELNode.DEFINE> {
        Context next;
        boolean loop;

        Context(Context next, ScopeState s) {
            this.next = next;
            if (s == ScopeState.ENTER_LOOP)
                this.loop = true;
            else if (s == ScopeState.ENTER_NESTED && next != null)
                this.loop = next.loop;
            else
                this.loop = false;
        }
    }

    /**
     * The context stack top.
     */
    private Context top;

    /**
     * Push a new context at the top of stack.
     */
    public void push(ScopeState state) {
        top = new Context(top, state);
    }

    /**
     * Pop context from the top of stack.
     */
    public Map<String, ELNode.DEFINE> pop() {
        Context ret = top;
        top = top.next;
        return ret;
    }

    public boolean insideLoops() {
        return top.loop;
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
