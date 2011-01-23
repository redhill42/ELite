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

package elite.lang;

import java.util.List;

/**
 * The sequence interface.
 */
public interface Seq extends List
{
    /**
     * Get the data element.
     */
    public Object get();

    /**
     * Set the data element.
     */
    public Object set(Object x);

    /**
     * Remove the data element.
     */
    public Object remove();

    /**
     * Returns the tail of the sequence.
     */
    public Seq tail();

    /**
     * Mutate the tail of the sequence, for advanced usage.
     */
    public void set_tail(Seq t);

    /**
     * Returns the last element in the sequence.
     */
    public Seq last();

    /**
     * Append a sequence at the end of this sequence.
     */
    public Seq append(Seq xs);

    /**
     * Make a reversed sequence.
     */
    public Seq reverse();

    /**
     * Apply the procedure and create a mapped sequence.
     */
    public Seq map(Closure proc);

    /**
     * Apply the predicate and create a filtered sequence.
     */
    public Seq filter(Closure pred);

    /**
     * Support method for list comprehension.
     */
    public Seq mappend(Closure proc);
}
