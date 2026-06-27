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

import java.util.RandomAccess;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.io.Serializable;

import elite.lang.Range;
import elite.lang.Seq;
import org.elite.eval.seq.AbstractSeq;
import org.elite.eval.seq.EmptySeq;

public class Ranges
{
    public static Seq createRange(long begin, long end, long step) {
        if (step == 1) {
            if (begin > end) {
                return EmptySeq.make();
            } else if (begin == end) {
                return new Singleton(begin);
            } else {
                return new Ascending(begin, end);
            }
        }

        if (step == -1) {
            if (begin < end) {
                return EmptySeq.make();
            } else if (begin == end) {
                return new Singleton(begin);
            } else {
                return new Descending(begin, end);
            }
        }

        if (step > 0) {
            if (begin > end) {
                return EmptySeq.make();
            } else if (begin + step > end) {
                return new Singleton(begin);
            } else {
                return new StepUp(begin, end, step);
            }
        }

        if (step < 0) {
            if (begin < end) {
                return EmptySeq.make();
            } else if (end + step > begin) {
                return new Singleton(begin);
            } else {
                return new StepDown(begin, end, -step);
            }
        }

        throw new IllegalArgumentException("illegal range step: " + step);
    }

    public static Seq createUnboundedRange(long begin, long step) {
        if (step > 0) {
            return new UnboundedStepUp(begin, step);
        } else if (step < 0) {
            return new UnboundedStepDown(begin, -step);
        } else {
            throw new IllegalArgumentException("illegal range step: " + step);
        }
    }

    private static abstract class AbstractRange extends AbstractSeq
        implements Range, RandomAccess, Serializable
    {
        public Object head() {
            return getBegin();
        }

        public Seq tail() {
            if (isUnbound()) {
                return createUnboundedRange(getBegin() + getStep(), getStep());
            } else {
                return createRange(getBegin() + getStep(), getEnd(), getStep());
            }
        }

        public Seq last() {
            if (isUnbound()) {
                throw new UnsupportedOperationException();
            }

            long begin = getBegin(), end = getEnd(), step = getStep();
            end = begin + (end-begin)/step*step;
            return new Singleton(end);
        }

        public Seq reverse() {
            if (isUnbound())
                throw new UnsupportedOperationException();
            return createRange(getEnd(), getBegin(), -getStep());
        }

        public int indexOf(Object o) {
            long num;
            if (o instanceof Number) {
                num = ((Number)o).longValue();
            } else if (o instanceof Character) {
                // noinspection UnnecessaryUnboxing
                num = ((Character)o).charValue();
            } else {
                return -1;
            }
            return indexOf(num);
        }

        public boolean contains(Object o) {
            return indexOf(o) != -1;
        }

        protected abstract int indexOf(long num);
    }

    public static class Singleton extends AbstractRange {
        private final long n;
        private static final long serialVersionUID = 2865107437677054850L;

        public Singleton(long n) {
            this.n = n;
        }

        public long getBegin()        { return n;     }
        public long getEnd()          { return n;     }
        public long getStep()         { return 1;     }
        public boolean isExcludeEnd() { return false; }
        public boolean isUnbound()    { return false; }

        public int size() {
            return 1;
        }

        public Long get(int index) {
            if (index != 0)
                throw new IndexOutOfBoundsException("Index: "+index+", size: 1");
            return n;
        }

        protected int indexOf(long n) {
            return n == this.n ? 0 : -1;
        }

        public String toString() {
            return "[" + n + ".." + n + "]";
        }
    }

    public static class Ascending extends AbstractRange {
        private static final long serialVersionUID = -6020526239222455654L;
        private final long begin, end;

        public Ascending(long begin, long end) {
            this.begin = begin;
            this.end = end;
        }

        public long    getBegin()     { return begin; }
        public long    getEnd()       { return end;   }
        public long    getStep()      { return 1;     }
        public boolean isExcludeEnd() { return false; }
        public boolean isUnbound()    { return false; }

        public int size() {
            return (int)(end - begin + 1);
        }

        public Long get(int index) {
            long num = begin + index;
            if (num < begin || num > end) {
                throw new IndexOutOfBoundsException(num + " not in range " + this);
            }
            return num;
        }

        protected int indexOf(long num) {
            if (num < begin || num > end) {
                return -1;
            } else {
                return (int)(num - begin);
            }
        }

        public String toString() {
            return "[" + begin + ".." + end + "]";
        }
    }

    public static class Descending extends AbstractRange {
        private static final long serialVersionUID = -5305644628530230136L;
        private final long begin, end;

        public Descending(long begin, long end) {
            this.begin = begin;
            this.end = end;
        }

        public long    getBegin()     { return begin; }
        public long    getEnd()       { return end;   }
        public long    getStep()      { return -1;    }
        public boolean isExcludeEnd() { return false; }
        public boolean isUnbound()    { return false; }

        public int size() {
            return (int)(begin - end + 1);
        }

        public Long get(int index) {
            long num = begin - index;
            if (num < end || num > begin) {
                throw new IndexOutOfBoundsException(num + " not in range " + this);
            }
            return num;
        }

        protected int indexOf(long num) {
            if (num < end || num > begin) {
                return -1;
            } else {
                return (int)(begin - num);
            }
        }

        public String toString() {
            return "[" + begin + "," + (begin-1) + ".." + end + "]";
        }
    }

    public static class StepUp extends AbstractRange {
        private static final long serialVersionUID = -575647382944213405L;
        private final long begin, end, step;

        public StepUp(long begin, long end, long step) {
            this.begin = begin;
            this.end = end;
            this.step = step;
        }

        public long    getBegin()     { return begin; }
        public long    getEnd()       { return end;   }
        public long    getStep()      { return step;  }
        public boolean isExcludeEnd() { return false; }
        public boolean isUnbound()    { return false; }

        public int size() {
            return (int)((end - begin + step) / step);
        }

        public Long get(int index) {
            long num = begin + index * step;
            if (num < begin || num > end) {
                throw new IndexOutOfBoundsException(num + " not in range " + this);
            }
            return num;
        }

        protected int indexOf(long num) {
            if (num < begin || num > end) {
                return -1;
            } else if ((num - begin) % step != 0) {
                return -1;
            } else {
                return (int)((num - begin) / step);
            }
        }
        public String toString() {
            return "[" + begin + "," + (begin+step) + ".." + end + "]";
        }
    }

    public static class StepDown extends AbstractRange {
        private static final long serialVersionUID = -6482548072796361895L;
        private final long begin, end, step;

        public StepDown(long begin, long end, long step) {
            this.begin = begin;
            this.end = end;
            this.step = step;
        }

        public long    getBegin()     { return begin; }
        public long    getEnd()       { return end;   }
        public long    getStep()      { return -step; }
        public boolean isExcludeEnd() { return false; }
        public boolean isUnbound()    { return false; }

        public int size() {
            return (int)((begin - end + step) / step);
        }

        public Long get(int index) {
            long num = begin - index * step;
            if (num < end || num > begin) {
                throw new IndexOutOfBoundsException(num + " not in range " + this);
            }
            return num;
        }

        protected int indexOf(long num) {
            if (num < end || num > begin) {
                return -1;
            } else if ((begin - num) % step != 0) {
                return -1;
            } else {
                return (int)((begin - num) / step);
            }
        }

        public String toString() {
            return "[" + begin + "," + (begin-step) + ".." + end + "]";
        }
    }

    public static class UnboundedStepUp extends AbstractRange {
        private final long begin, step;
        private static final long serialVersionUID = -3185000032254546767L;

        public UnboundedStepUp(long begin, long step) {
            this.begin = begin;
            this.step = step;
        }

        public long getBegin()        { return begin;          }
        public long getEnd()          { return Long.MAX_VALUE; }
        public long getStep()         { return step;           }
        public boolean isExcludeEnd() { return false;          }
        public boolean isUnbound()    { return true;           }

        public int size() {
            return Integer.MAX_VALUE;
        }

        public Long get(int index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return begin + index * step;
        }

        protected int indexOf(long num) {
            if (num < begin) {
                return -1;
            } else if ((num - begin) % step != 0) {
                return -1;
            } else {
                return (int)((num - begin) / step);
            }
        }

        public String toString() {
            if (step == 1) {
                return "[" + begin + "..*]";
            } else {
                return "[" + begin + "," + (begin+step) + "..*]";
            }
        }

        public Iterator<Long> iterator() {
            return new Iterator<Long>() {
                private long next = begin;
                public boolean hasNext() {
                    return true;
                }
                public Long next() {
                    long i = next;
                    next += step;
                    return i;
                }
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        public ListIterator<Long> listIterator(final int index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return new ListIterator<Long>() {
                private int cursor = index;
                public boolean hasNext() {
                    return true;
                }
                public Long next() {
                    return begin + cursor++ * step;
                }
                public boolean hasPrevious() {
                    return cursor != 0;
                }
                public Long previous() {
                    if (cursor == 0) {
                        throw new NoSuchElementException();
                    }
                    return begin + --cursor * step;
                }
                public int nextIndex() {
                    return cursor;
                }
                public int previousIndex() {
                    return cursor-1;
                }
                public void remove() {
                    throw new UnsupportedOperationException();
                }
                public void set(Long o) {
                    throw new UnsupportedOperationException();
                }
                public void add(Long o) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    public static class UnboundedStepDown extends AbstractRange {
        private final long begin, step;
        private static final long serialVersionUID = -5903447967490555464L;

        public UnboundedStepDown(long begin, long step) {
            this.begin = begin;
            this.step = step;
        }

        public long getBegin()        { return begin;          }
        public long getEnd()          { return Long.MIN_VALUE; }
        public long getStep()         { return -step;          }
        public boolean isExcludeEnd() { return false;          }
        public boolean isUnbound()    { return true;           }

        public int size() {
            return Integer.MAX_VALUE;
        }

        public Long get(int index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return begin - index * step;
        }

        protected int indexOf(long num) {
            if (num > begin) {
                return -1;
            } else if ((begin - num) % step != 0) {
                return -1;
            } else {
                return (int)((begin - num) / step);
            }
        }

        public String toString() {
            return "[" + begin + "," + (begin-step) + "..*]";
        }

        public Iterator<Long> iterator() {
            return new Iterator<Long>() {
                private long next = begin;
                public boolean hasNext() {
                    return true;
                }
                public Long next() {
                    long i = next;
                    next -= step;
                    return i;
                }
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        public ListIterator<Long> listIterator(final int index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return new ListIterator<Long>() {
                private int cursor = index;
                public boolean hasNext() {
                    return true;
                }
                public Long next() {
                    return begin - cursor++ * step;
                }
                public boolean hasPrevious() {
                    return cursor != 0;
                }
                public Long previous() {
                    if (cursor == 0) {
                        throw new NoSuchElementException();
                    }
                    return begin - --cursor * step;
                }
                public int nextIndex() {
                    return cursor;
                }
                public int previousIndex() {
                    return cursor-1;
                }
                public void remove() {
                    throw new UnsupportedOperationException();
                }
                public void set(Long o) {
                    throw new UnsupportedOperationException();
                }
                public void add(Long o) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
