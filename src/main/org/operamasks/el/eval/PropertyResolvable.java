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
package org.operamasks.el.eval;

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
     * @throws PropertyNotFoundException if the given property is handled
     *      by this <code>PropertyResolver</code> but the specified
     *      variable or property does not exist or is not readable.
     * @throws ELException if an exception was thrown while performing
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
     * @throws PropertyNotFoundException if the given property is handled
     *      by this <code>PropertyResolver</code> but the specified
     *      variable or property does not exist or is not readable.
     * @throws ELException if an exception was thrown while performing
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
     * @throws PropertyNotFoundException if the given property is handled
     *      by this <code>PropertyResolver</code> but the specified
     *      variable or property does not exist or is not readable.
     * @throws ELException if an exception was thrown while performing
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
     * @throws PropertyNotFoundException if the given property is handled
     *      by this <code>PropertyResolver</code> but the specified
     *      variable or property does not exist or is not readable.
     * @throws ELException if an exception was thrown while performing
     *      the property or variable resolution. The thrown exception
     *      must be included as the cause property of this exception, if
     *      available.
     */
    public boolean isReadOnly(ELContext context, Object property);
}
