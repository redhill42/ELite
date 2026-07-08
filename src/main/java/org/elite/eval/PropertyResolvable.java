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
package org.elite.eval;

import javax.el.ELContext;

/**
 * Implementations of this interface perform self property resolution
 * for EL expression evaluation.
 */
public interface PropertyResolvable
{
    /**
     * Attemps to resolve the given <code>property</code> object.
     *
     * <p>If this resolvable object handles the given property, the
     * <code>propertyResolved</code> property of the <code>ELContext</code>
     * object must be set to <code>true</code>, before returning. If this
     * property is not <code>true</code> after this method is called, the
     * caller should ignore the return value.
     *
     * @param context The context of this evaluation.
     * @param property The property or variable to be resolved.
     * @return If the <code>propertyResolved</code> property of
     *      <code>ELContext</code> was set to <code>true</code>, then
     *      the result of the variable or property resolution; otherwise
     *      undefined.
     * @throws javax.el.PropertyNotFoundException if the given property is handled
     *      by this <code>PropertyResolver</code> but the specified
     *      variable or property does not exist or is not readable.
     * @throws javax.el.ELException if an exception was thrown while performing
     *      the property or variable resolution. The thrown exception
     *      must be included as the cause property of this exception, if
     *      available.
     */
    public Object getValue(ELContext context, Object property);

    /**
     * For a given <code>property</code>, attempts to identify the most general
     * type that is acceptable for an object to be passed as the <code>value</code>
     * parameter in a future call to the @{@link #setValue} method.
     *
     * <p>If this resolvable object handles the given property, the
     * <code>propertyResolved</code> property of the <code>ELContext</code>
     * object must be set to <code>true</code>, before returning. If this
     * property is not <code>true</code> after this method is called, the
     * caller should ignore the return value.
     *
     * @param context The context of this evaluation.
     * @param property The property or variable to be resolved.
     * @return If the <code>propertyResolved</code> property of
     *      <code>ELContext</code> was set to <code>true</code>, then
     *      the result of the variable or property resolution; otherwise
     *      undefined.
     * @throws javax.el.PropertyNotFoundException if the given property is handled
     *      by this <code>PropertyResolver</code> but the specified
     *      variable or property does not exist or is not readable.
     * @throws javax.el.ELException if an exception was thrown while performing
     *      the property or variable resolution. The thrown exception
     *      must be included as the cause property of this exception, if
     *      available.
     */
    public Class<?> getType(ELContext context, Object property);

    /**
     * Attemps to set the value of the given <code>property</code> object.
     *
     * <p>If this resolvable object handles the given property, the
     * <code>propertyResolved</code> property of the <code>ELContext</code>
     * object must be set to <code>true</code>, before returning. If this
     * property is not <code>true</code> after this method is called, the
     * caller should ignore the return value.
     *
     * @param context The context of this evaluation.
     * @param property The property or variable to be resolved.
     * @throws javax.el.PropertyNotFoundException if the given property is handled
     *      by this <code>PropertyResolver</code> but the specified
     *      variable or property does not exist or is not readable.
     * @throws javax.el.ELException if an exception was thrown while performing
     *      the property or variable resolution. The thrown exception
     *      must be included as the cause property of this exception, if
     *      available.
     */
    public void setValue(ELContext context, Object property, Object value);

    /**
     * For a given <code>property</code>, attemps to determine whether a call
     * to {@link #setValue} will always fail.
     *
     * <p>If this resolvable object handles the given property, the
     * <code>propertyResolved</code> property of the <code>ELContext</code>
     * object must be set to <code>true</code>, before returning. If this
     * property is not <code>true</code> after this method is called, the
     * caller should ignore the return value.
     *
     * @param context The context of this evaluation.
     * @param property The property or variable to be resolved.
     * @return If the <code>propertyResolved</code> property of
     *      <code>ELContext</code> was set to <code>true</code>, then
     *      the result of the variable or property resolution; otherwise
     *      undefined.
     * @throws javax.el.PropertyNotFoundException if the given property is handled
     *      by this <code>PropertyResolver</code> but the specified
     *      variable or property does not exist or is not readable.
     * @throws javax.el.ELException if an exception was thrown while performing
     *      the property or variable resolution. The thrown exception
     *      must be included as the cause property of this exception, if
     *      available.
     */
    public boolean isReadOnly(ELContext context, Object property);
}
