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
