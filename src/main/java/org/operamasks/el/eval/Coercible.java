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
