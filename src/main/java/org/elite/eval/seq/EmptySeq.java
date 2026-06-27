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

package org.elite.eval.seq;

import java.util.RandomAccess;
import java.io.Serializable;
import elite.lang.Seq;

public class EmptySeq extends AbstractSeq
    implements RandomAccess, Serializable
{
    private static final Seq EMPTY_SEQ = new EmptySeq();

    public static Seq make() {
        return EMPTY_SEQ;
    }

    private EmptySeq() {}

    public boolean isEmpty() {
        return true;
    }
    
    public int size() {
        return 0;
    }

    public Object head() {
        return null;
    }

    public Seq tail() {
        return this;
    }

    public Seq last() {
        return this;
    }

    public Object get(int index) {
        throw new IndexOutOfBoundsException("Index:"+index);
    }

    public boolean contains(Object obj) {
        return false;
    }

    private Object readResolve() {
        return EMPTY_SEQ;
    }
}
