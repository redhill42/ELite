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

package org.elite.resolver;

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
