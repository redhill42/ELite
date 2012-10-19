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

package org.operamasks.el.resolver;

import java.beans.FeatureDescriptor;
import java.util.Iterator;
import java.util.Collection;
import java.util.List;
import java.lang.reflect.Array;
import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

import elite.lang.Range;
import elite.lang.Seq;
import org.operamasks.el.eval.seq.Cons;
import org.operamasks.el.eval.seq.DelaySeq;
import org.operamasks.el.eval.seq.EmptySeq;
import org.operamasks.el.eval.TypeCoercion;

@SuppressWarnings("unchecked")
public class SeqELResolver extends ELResolver
{
    private boolean isReadOnly;

    public SeqELResolver() {
        this.isReadOnly = false;
    }

    public SeqELResolver(boolean isReadOnly) {
        this.isReadOnly = isReadOnly;
    }

    public Class<?> getType(ELContext context, Object base, Object property) {
        if (base instanceof Seq) {
            context.setPropertyResolved(true);
            if (property instanceof Range) {
                return Seq.class;
            } else if ("length".equals(property) || "size".equals(property)) {
                return Integer.class;
            } else {
                return Object.class;
            }
        }
        return null;
    }

    public Object getValue(ELContext context, Object base, Object property) {
        if (!(base instanceof Seq)) {
            return null;
        }

        Seq seq = (Seq)base;
        Object result = null;

        if (property instanceof String) {
            if ("length".equals(property) || "size".equals(property)) {
                result = seq.size();
                context.setPropertyResolved(true);
            } else if ("first".equals(property) || "head".equals(property)) {
                result = seq.head();
                context.setPropertyResolved(true);
            } else if ("last".equals(property)) {
                result = seq.last().head();
                context.setPropertyResolved(true);
            } else if ("rest".equals(property) || "tail".equals(property)) {
                result = seq.isEmpty() ? seq : seq.tail();
                context.setPropertyResolved(true);
            }
        } else if (property instanceof Range) {
            result = extractRange(seq, (Range)property);
            context.setPropertyResolved(true);
        } else if (property instanceof Number) {
            try {
                result = seq.get(((Number)property).intValue());
            } catch (IndexOutOfBoundsException ex) {
                result = null;
            }
            context.setPropertyResolved(true);
        } else if ((property instanceof List) && ((List)property).isEmpty()) {
            // handle empty range
            result = EmptySeq.make();
            context.setPropertyResolved(true);
        }

        return result;
    }

    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (!(base instanceof Seq)) {
            return;
        }

        if (isReadOnly) {
            throw new PropertyNotWritableException();
        }

        Seq seq = (Seq)base;

        if (property instanceof String) {
            if ("length".equals(property) || "size".equals(property)) {
                throw new PropertyNotWritableException(property.toString());
            } else if ("first".equals(property) || "head".equals(property)) {
                if (seq.isEmpty()) {
                    seq.add(value);
                } else {
                    seq.set_head(value);
                }
                context.setPropertyResolved(true);
            } else if ("last".equals(property)) {
                Seq l = seq.last();
                if (l.isEmpty()) {
                    l.add(value);
                } else {
                    l.set_head(value);
                }
                context.setPropertyResolved(true);
            } else if ("rest".equals(property) || "tail".equals(property)) {
                seq.set_tail(TypeCoercion.coerceToSeq(value));
                context.setPropertyResolved(true);
            }
        } else if (property instanceof Range) {
            if (value instanceof Collection) {
                copyRangeWithCollection(seq, (Range)property, (Collection)value);
            } else if (value instanceof Object[]) {
                copyRangeWithArray(seq, (Range)property, (Object[])value);
            } else if (value.getClass().isArray()) {
                copyRangeWithPArray(seq, (Range)property, value);
            } else {
                copyRangeWithSingle(seq, (Range)property, value);
            }
            context.setPropertyResolved(true);
        } else if (property instanceof Number) {
            int index = ((Number)property).intValue();
            set(seq, index, value);
            context.setPropertyResolved(true);
        }
    }

    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if (base instanceof Seq) {
            context.setPropertyResolved(true);
            return isReadOnly;
        }
        return false;
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        return null;
    }

    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        if (base instanceof Seq) {
            return Integer.class;
        }
        return null;
    }

    // Implementation -----------------

    private void set(Seq seq, int index, Object value) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index:"+index);
        }

        while (index > 0 && !seq.isEmpty()) {
            seq = seq.tail();
            --index;
        }

        if (!seq.isEmpty()) {
            seq.set_head(value);
        } else {
            while (index > 0) {
                seq.add(null);
                seq = seq.tail();
                --index;
            }
            seq.add(value);
        }
    }

    private Seq extractRange(Seq base, Range range) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();

        if (step == 1 && range.isUnbound()) {
            // simple case
            while (begin > 0 && !base.isEmpty()) {
                base = base.tail();
                --begin;
            }
            return base;
        }

        if (step < 0) {
            if (begin <= 0) {
                return Cons.nil(); // empty list
            }
            if (range.isUnbound() || end < 0) {
                end = 0;
            }

            // normalize end position
            step = -step;
            end = begin - ((begin-end)/step)*step;

            // move to the end position
            while (end > 0 && !base.isEmpty()) {
                base = base.tail();
                --end; --begin;
            }

            // make reverse list
            Seq rev = Cons.nil();
            while (begin >= 0 && !base.isEmpty()) {
                rev = new Cons(base.head(), rev);
                for (long i = step; !base.isEmpty() && --i >= 0; ) {
                    base = base.tail();
                    --begin;
                }
            }
            return rev;
        }

        if (begin < 0) {
            if (!range.isUnbound() && end < 0)
                return Cons.nil();
            begin = 0;
        }

        // move to the begin position
        while (begin > 0 && !base.isEmpty()) {
            base = base.tail();
            --begin; --end;
        }
        if (base.isEmpty()) {
            return base;
        }

        // create a lazy evaluated list
        return new RangeSeq(base, (int)step, (int)end);
    }

    private static class RangeSeq extends DelaySeq {
        private Seq seq;
        private int step, end;

        public RangeSeq(Seq seq, int step, int end) {
            this.seq  = seq;
            this.step = step;
            this.end  = end;
        }

        protected void force(ELContext elctx) {
            if (seq != null) {
                head = seq.head();

                Seq t = seq;
                for (int i = step; !t.isEmpty() && --i >= 0; ) {
                    t = t.tail();
                }

                if (t.isEmpty() || (end - step) < 0) {
                    tail = Cons.nil();
                } else {
                    tail = new RangeSeq(t, step, end-step);
                }

                seq = null;
            }
        }
    }

    private void copyRangeWithSingle(Seq base, Range range, Object value) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        boolean inf = range.isUnbound();

        if (step < 0) {
            if (begin <= 0)
                return;
            if (inf || end < 0)
                end = 0;

            // normalize end position
            step = -step;
            end = begin - ((begin-end)/step)*step;
            inf = false;

            // swap begin and end position
            long t = begin; begin = end; end = t;
        } else {
            if (begin < 0) {
                if (!inf && end < 0)
                    return;
                begin = 0;
            }
        }

        // move to the begin position
        while (begin > 0) {
            if (base.isEmpty())
                base.add(null);
            base = base.tail();
            --begin; --end;
        }

        // step to end
        while (inf || end >= 0) {
            if (base.isEmpty()) {
                base.add(value);
            } else {
                base.set_head(value);
            }

            if (step == 1) {
                base = base.tail();
                --end;
            } else {
                for (long i = step; (inf || end >= 0) && --i >= 0; ) {
                    if (base.isEmpty())
                        base.add(null);
                    base = base.tail();
                    --end;
                }
            }
        }
    }

    private void copyRangeWithArray(Seq base, Range range, Object[] value) {
        if (value.length == 0) {
            return;
        }

        if (range.getStep() < 0) {
            reverseCopyRangeWithArray(base, range, value);
            return;
        }
        
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        boolean inf = range.isUnbound();

        if (begin < 0) {
            if (!inf && end < 0)
                return;
            begin = 0;
        }

        // move to the begin position
        while (begin > 0) {
            if (base.isEmpty())
                base.add(null);
            base = base.tail();
            --begin; --end;
        }

        // step to end
        int xl = value.length, xi = 0;
        while (inf || end >= 0) {
            if (base.isEmpty()) {
                base.add(value[xi]);
            } else {
                base.set_head(value[xi]);
            }

            if (++xi >= xl) {
                break;
            }

            if (step == 1) {
                base = base.tail();
                --end;
            } else {
                for (long i = step; (inf || end >= 0) && --i >= 0; ) {
                    if (base.isEmpty())
                        base.add(null);
                    base = base.tail();
                    --end;
                }
            }
        }
    }

    private void reverseCopyRangeWithArray(Seq base, Range range, Object[] value) {
        long begin = range.getEnd();
        long end   = range.getBegin();
        long step  = -range.getStep();

        if (end <= 0)
            return;
        if (range.isUnbound() || begin < 0)
            begin = 0;

        // normalize begin position
        long count = (end - begin + step) / step;
        begin = end - (count-1) * step;

        // normalize the source position
        int xi = count >= value.length ? value.length-1 : (int)count-1;

        // move to the begin position
        while (begin > 0) {
            if (base.isEmpty())
                base.add(null);
            base = base.tail();
            --begin; --end;
        }

        // step to end
        while (end >= 0) {
            if (base.isEmpty()) {
                base.add(value[xi]);
            } else {
                base.set_head(value[xi]);
            }

            if (--xi < 0) {
                break;
            }

            if (step == 1) {
                base = base.tail();
                --end;
            } else {
                for (long i = step; end >= 0 && --i >= 0; ) {
                    if (base.isEmpty())
                        base.add(null);
                    base = base.tail();
                    --end;
                }
            }
        }
    }

    private void copyRangeWithPArray(Seq base, Range range, Object value) {
        if (Array.getLength(value) == 0) {
            return;
        }

        if (range.getStep() < 0) {
            reverseCopyRangeWithPArray(base, range, value);
            return;
        }

        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        boolean inf = range.isUnbound();

        if (begin < 0) {
            if (!inf && end < 0)
                return;
            begin = 0;
        }

        // move to the begin position
        while (begin > 0) {
            if (base.isEmpty())
                base.add(null);
            base = base.tail();
            --begin; --end;
        }

        // step to end
        int xl = Array.getLength(value), xi = 0;
        while (inf || end >= 0) {
            Object x = Array.get(value, xi);
            if (base.isEmpty()) {
                base.add(x);
            } else {
                base.set_head(x);
            }

            if (++xi >= xl) {
                break;
            }

            if (step == 1) {
                base = base.tail();
                --end;
            } else {
                for (long i = step; (inf || end >= 0) && --i >= 0; ) {
                    if (base.isEmpty())
                        base.add(null);
                    base = base.tail();
                    --end;
                }
            }
        }
    }

    private void reverseCopyRangeWithPArray(Seq base, Range range, Object value) {
        long begin = range.getEnd();
        long end   = range.getBegin();
        long step  = -range.getStep();

        if (end <= 0)
            return;
        if (range.isUnbound() || begin < 0)
            begin = 0;

        // normalize begin position
        long count = (end - begin + step) / step;
        begin = end - (count-1) * step;

        // normalize the source position
        int xl = Array.getLength(value);
        int xi = count >= xl ? xl-1 : (int)count-1;

        // move to the begin position
        while (begin > 0) {
            if (base.isEmpty())
                base.add(null);
            base = base.tail();
            --begin; --end;
        }

        // step to end
        while (end >= 0) {
            Object x = Array.get(value, xi);
            if (base.isEmpty()) {
                base.add(x);
            } else {
                base.set_head(x);
            }

            if (--xi < 0) {
                break;
            }

            if (step == 1) {
                base = base.tail();
                --end;
            } else {
                for (long i = step; end >= 0 && --i >= 0; ) {
                    if (base.isEmpty())
                        base.add(null);
                    base = base.tail();
                    --end;
                }
            }
        }
    }

    private void copyRangeWithCollection(Seq base, Range range, Collection c) {
        if (c.isEmpty()) {
            return;
        }

        if (range.getStep() < 0) {
            copyRangeWithArray(base, range, c.toArray());
            return;
        }

        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        boolean inf = range.isUnbound();

        if (begin < 0) {
            if (!inf && end < 0)
                return;
            begin = 0;
        }

        // move to the begin position
        while (begin > 0) {
            if (base.isEmpty())
                base.add(null);
            base = base.tail();
            --begin; --end;
        }

        Iterator it = c.iterator();
        if (!it.hasNext()) {
            return;
        }

        // step to end
        while (inf || end >= 0) {
            if (base.isEmpty()) {
                base.add(it.next());
            } else {
                base.set_head(it.next());
            }

            if (!it.hasNext()) {
                break;
            }

            if (step == 1) {
                base = base.tail();
                --end;
            } else {
                for (long i = step; (inf || end >= 0) && --i >= 0; ) {
                    if (base.isEmpty())
                        base.add(null);
                    base = base.tail();
                    --end;
                }
            }
        }
    }
}
