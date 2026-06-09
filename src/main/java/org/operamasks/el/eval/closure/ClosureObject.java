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
