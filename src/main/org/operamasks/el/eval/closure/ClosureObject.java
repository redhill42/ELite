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

package org.operamasks.el.eval.closure;

import java.util.Map;
import javax.el.ELContext;
import elite.lang.Closure;
import org.operamasks.el.eval.PropertyDelegate;

/**
 * A marker interface to indicate a closure object.
 */
public interface ClosureObject extends PropertyDelegate
{
    /**
     * Returns ClassDefinition of the closure object.
     */
    public ClassDefinition get_class();

    /**
     * Get the internal this object.
     *
     * @return the internal this object.
     */
    public ClosureObject get_this();

    /**
     * Get the owner of this object.
     *
     * @return the owner of internal this object.
     */
    public ClosureObject get_owner();

    /**
     * Get the proxy of this object.
     *
     * @return the proxy object.
     */
    public Object get_proxy();

    /**
     * Get the property closure.
     *
     * @param elctx the evaluation context
     * @param name the property name @return the closure associated with the key
     */
    public Closure get_closure(ELContext elctx, String name);

    /**
     * Get a map that contains all closures defined in this ClosureObject.
     *
     * @return the closure map
     * @param elctx
     */
    public Map<String,Closure> get_closures(ELContext elctx);

    /**
     * Invoke the closure procedure.
     *
     * @param elctx the evaluation context
     * @param name the procedure name
     * @param args the invocation arguments
     */
    public Object invoke(ELContext elctx, String name, Closure[] args);

    /**
     * Invoke a special procedure, such as operator overrider.
     *
     * @param elctx the evaluation context
     * @param name the procedure name
     * @param args the invocation arguments
     */
    public Object invokeSpecial(ELContext elctx, String name, Closure[] args);
}
