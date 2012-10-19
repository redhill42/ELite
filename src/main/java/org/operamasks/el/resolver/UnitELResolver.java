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
import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;
import javax.measure.unit.Unit;
import javax.measure.Measure;

import elite.lang.Measures;

public class UnitELResolver extends ELResolver
{
    private static boolean measuresEnabled(ELContext elctx) {
        return Boolean.TRUE.equals(elctx.getContext(Measures.class)); // see "measure.xel"
    }

    public Object getValue(ELContext context, Object base, Object property) {
        if (!measuresEnabled(context)) {
            return null;
        }
        
        if ((base != null) && (property instanceof Unit)) {
            context.setPropertyResolved(true);
            return Measures.getMeasure(base, (Unit)property);
        }

        if (property instanceof String) {
            Unit unit = Measures.getUnit(context, (String)property);
            if (unit != null) {
                context.setPropertyResolved(true);
                return base == null ? unit : Measures.getMeasure(base, unit);
            }
        }

        return null;
    }

    public Class<?> getType(ELContext context, Object base, Object property) {
        if (!measuresEnabled(context)) {
            return null;
        }

        if((base != null) && (property instanceof Unit)) {
            context.setPropertyResolved(true);
            return Measures.class;
        }

        if (property instanceof String) {
            Unit unit = Measures.getUnit(context, (String)property);
            if (unit != null) {
                context.setPropertyResolved(true);
                return base == null ? Unit.class : Measure.class;
            }
        }

        return null;
    }

    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (!measuresEnabled(context)) {
            return;
        }

        if ((base != null) && (property instanceof Unit)) {
            throw new PropertyNotWritableException();
        }

        if (property instanceof String) {
            Unit unit = Measures.getUnit(context, (String)property);
            if (unit != null) {
                throw new PropertyNotWritableException();
            }
        }
    }

    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if (!measuresEnabled(context)) {
            return false;
        }

        if ((base != null) && (property instanceof Unit)) {
            context.setPropertyResolved(true);
            return true;
        }

        if (property instanceof String) {
            Unit unit = Measures.getUnit(context, (String)property);
            if (unit != null) {
                context.setPropertyResolved(true);
                return true;
            }
        }

        return false;
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        return null;
    }

    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        return null;
    }
}
