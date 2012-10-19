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

package org.operamasks.el.eval.closure;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

import elite.lang.Closure;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.EvaluationException;

public class FieldClosure extends AbstractClosure
{
    private final Field field;

    public FieldClosure(Field field) {
        this.field = field;
    }

    public Object getValue(ELContext elctx) {
        try {
            return field.get(null);
        } catch (Exception ex) {
            throw new EvaluationException(elctx, ex);
        }
    }

    public void setValue(ELContext elctx, Object value) {
        if (Modifier.isFinal(field.getModifiers())) {
            throw new PropertyNotWritableException(field.getName());
        }

        try {
            field.set(null, value);
        } catch (Exception ex) {
            throw new EvaluationException(elctx, ex);
        }
    }

    public Class<?> getType(ELContext elctx) {
        return field.getType();
    }

    public boolean isReadOnly(ELContext elctx) {
        return Modifier.isFinal(field.getModifiers());
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        try {
            return ELEngine.invokeTarget(elctx, field.get(null), args);
        } catch (IllegalAccessException ex) {
            throw new EvaluationException(elctx, ex);
        }
    }
}
