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

import java.util.RandomAccess;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.io.Serializable;

import elite.lang.Seq;
import elite.lang.CharRange;
import org.operamasks.el.eval.seq.EmptySeq;
import org.operamasks.el.eval.seq.AbstractSeq;

public class CharRanges
{
    public static Seq createCharRange(char begin, char end, int step) {
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

    public static Seq createUnboundedRange(char begin, int step) {
        if (step > 0) {
            return new UnboundedStepUp(begin, step);
        } else if (step < 0) {
            return new UnboundedStepDown(begin, -step);
        } else {
            throw new IllegalArgumentException("Illegal range step: " + step);
        }
    }

    private static abstract class AbstractCharRange extends AbstractSeq
        implements CharRange, RandomAccess, Serializable
    {
        public Object head() {
            return getBegin();
        }

        public Seq tail() {
            if (isUnbound()) {
                return createUnboundedRange((char)(getBegin() + getStep()), getStep());
            } else {
                return createCharRange((char)(getBegin() + getStep()), getEnd(), getStep());
            }
        }

        public Seq last() {
            if (isUnbound()) {
                throw new UnsupportedOperationException();
            }

            char begin = getBegin(), end = getEnd();
            int step = getStep();
            end = (char)(begin + (end-begin)/step*step);
            return new Singleton(end);
        }

        public Seq reverse() {
            if (isUnbound())
                throw new UnsupportedOperationException();
            return createCharRange(getEnd(), getBegin(), -getStep());
        }

        public int indexOf(Object o) {
            if (o instanceof Character) {
                return indexOf(((Character)o).charValue());
            } else if (o instanceof CharSequence) {
                CharSequence s = (CharSequence)o;
                if (s.length() == 1) {
                    return indexOf(s.charAt(0));
                }
            }
            return -1;
        }

        public boolean contains(Object o) {
            return indexOf(o) != -1;
        }

        protected abstract int indexOf(char ch);
    }

    public static class Singleton extends AbstractCharRange {
        private final char c;

        public Singleton(char c) {
            this.c = c;
        }

        public char    getBegin()  { return c; }
        public char    getEnd()    { return c; }
        public int     getStep()   { return 1; }
        public boolean isUnbound() { return false; }

        public int size() {
            return 1;
        }

        public Character get(int index) {
            if (index != 0)
                throw new IndexOutOfBoundsException("Index:"+index+", size: 1");
            return c;
        }

        protected int indexOf(char c) {
            return c == this.c ? 0 : -1;
        }

        public String toString() {
            return "[" + c + ".." + c + "]";
        }
    }

    public static class Ascending extends AbstractCharRange {
        private final char begin, end;

        public Ascending(char begin, char end) {
            this.begin = begin;
            this.end = end;
        }

        public char    getBegin()  { return begin; }
        public char    getEnd()    { return end; }
        public int     getStep()   { return 1; }
        public boolean isUnbound() { return false; }

        public int size() {
            return (end - begin + 1);
        }

        public Character get(int index) {
            char c = (char)(begin + index);
            if (c < begin | c > end) {
                throw new IndexOutOfBoundsException(c + " not in range " + this);
            }
            return c;
        }

        protected int indexOf(char c) {
            if (c < begin || c > end) {
                return -1;
            } else {
                return c - begin;
            }
        }

        public String toString() {
            return "[" + begin + ".." + end + "]";
        }
    }

    public static class Descending extends AbstractCharRange {
        private final char begin, end;

        public Descending(char begin, char end) {
            this.begin = begin;
            this.end = end;
        }

        public char    getBegin()   { return begin; }
        public char    getEnd()     { return end; }
        public int     getStep()    { return -1; }
        public boolean isUnbound()  { return false; }

        public int size() {
            return begin - end + 1;
        }

        public Character get(int index) {
            char c = (char)(begin - index);
            if (c < end || c > begin) {
                throw new IndexOutOfBoundsException(c + " not in range "+ this);
            }
            return c;
        }

        protected int indexOf(char c) {
            if (c < end || c > begin) {
                return -1;
            } else {
                return begin - c;
            }
        }

        public String toString() {
            return "[" + begin + "," + (char)(begin-1) + ".." + end + "]";
        }
    }

    public static class StepUp extends AbstractCharRange {
        private final char begin, end;
        private final int step;

        public StepUp(char begin, char end, int step) {
            this.begin = begin;
            this.end = end;
            this.step = step;
        }

        public char    getBegin()    { return begin; }
        public char    getEnd()      { return end; }
        public int     getStep()     { return step; }
        public boolean isUnbound()   { return false; }

        public int size() {
            return (end - begin + step) / step;
        }

        public Character get(int index) {
            char c = (char)(begin + index * step);
            if (c < begin || c > end) {
                throw new IndexOutOfBoundsException(c + " not in range " + this);
            }
            return c;
        }

        protected int indexOf(char c) {
            if (c < begin || c > end) {
                return -1;
            } else if ((c - begin) % step != 0) {
                return -1;
            } else {
                return (c - begin) / step;
            }
        }

        public String toString() {
            return "[" + begin + "," + (char)(begin+step) + ".." + end + "]";
        }
    }

    public static class StepDown extends AbstractCharRange {
        private final char begin, end;
        private final int step;

        public StepDown(char begin, char end, int step) {
            this.begin = begin;
            this.end = end;
            this.step = step;
        }

        public char    getBegin()    { return begin; }
        public char    getEnd()      { return end; }
        public int     getStep()     { return -step; }
        public boolean isUnbound()   { return false; }

        public int size() {
            return (begin - end + step) / step;
        }

        public Character get(int index) {
            char c = (char)(begin - index * step);
            if (c < end || c > begin) {
                throw new IndexOutOfBoundsException(c + " not in range " + this);
            }
            return c;
        }

        protected int indexOf(char c) {
            if (c < end || c > begin) {
                return -1;
            } else if ((begin - c) % step != 0) {
                return -1;
            } else {
                return (begin - c) / step;
            }
        }

        public String toString() {
            return "[" + begin + "," + (char)(begin-step) + ".." + end + "]";
        }
    }

    public static class UnboundedStepUp extends AbstractCharRange {
        private final char begin;
        private final int step;

        public UnboundedStepUp(char begin, int step) {
            this.begin = begin;
            this.step = step;
        }

        public char getBegin()     { return begin; }
        public char getEnd()       { return Character.MAX_VALUE; }
        public int  getStep()      { return step; }
        public boolean isUnbound() { return true; }

        public int size() {
            return Character.MAX_VALUE;
        }

        public Character get(int index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return (char)(begin + index * step);
        }

        protected int indexOf(char c) {
            if (c < begin) {
                return -1;
            } else if ((c - begin) % step != 0) {
                return -1;
            } else {
                return (c - begin) / step;
            }
        }

        public String toString() {
            if (step == 1) {
                return "[" + begin + "..*]";
            } else {
                return "[" + begin + "," + (char)(begin+step) + "..*]";
            }
        }

        public Iterator<Character> iterator() {
            return new Iterator<Character>() {
                private char next = begin;
                public boolean hasNext() {
                    return true;
                }
                public Character next() {
                    char c = next;
                    next = (char)(next + step);
                    return c;
                }
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        public ListIterator<Character> listIterator(final int index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return new ListIterator<Character>() {
                private int cursor = index;
                public boolean hasNext() {
                    return true;
                }
                public Character next() {
                    return (char)(begin + cursor++ * step);
                }
                public boolean hasPrevious() {
                    return cursor != 0;
                }
                public Character previous() {
                    if (cursor == 0) {
                        throw new NoSuchElementException();
                    }
                    return (char)(begin + --cursor * step);
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
                public void set(Character o) {
                    throw new UnsupportedOperationException();
                }
                public void add(Character o) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    public static class UnboundedStepDown extends AbstractCharRange {
        private final char begin;
        private final int step;

        public UnboundedStepDown(char begin, int step) {
            this.begin = begin;
            this.step = step;
        }

        public char getBegin()     { return begin; }
        public char getEnd()       { return Character.MIN_VALUE; }
        public int  getStep()      { return -step; }
        public boolean isUnbound() { return true; }

        public int size() {
            return Integer.MAX_VALUE;
        }

        public Character get(int index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return (char)(begin - index * step);
        }

        protected int indexOf(char c) {
            if (c > begin) {
                return -1;
            } else if ((begin - c) % step != 0) {
                return -1;
            } else {
                return (begin - c) / step;
            }
        }

        public String toString() {
            return "[" + begin + "," + (char)(begin-step) + "..*]";
        }

        public Iterator<Character> iterator() {
            return new Iterator<Character>() {
                private char next = begin;
                public boolean hasNext() {
                    return true;
                }
                public Character next() {
                    char c = next;
                    next = (char)(next - step);
                    return c;
                }
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        public ListIterator<Character> listIterator(final int index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return new ListIterator<Character>() {
                private int cursor = index;
                public boolean hasNext() {
                    return true;
                }
                public Character next() {
                    return (char)(begin - cursor++ * step);
                }
                public boolean hasPrevious() {
                    return cursor != 0;
                }
                public Character previous() {
                    if (cursor == 0) {
                        throw new NoSuchElementException();
                    }
                    return (char)(begin - --cursor * step);
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
                public void set(Character o) {
                    throw new UnsupportedOperationException();
                }
                public void add(Character o) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
