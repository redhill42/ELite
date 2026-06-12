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

package elite.lang;

import java.util.List;

/**
 * The sequence interface.
 */
public interface Seq extends List {
    /**
     * Get the data element.
     */
    public Object head();

    /**
     * Set the data element.
     */
    public Object set_head(Object x);

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
