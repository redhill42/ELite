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

import java.io.Serializable;
import java.util.Map;
import java.util.LinkedHashMap;

import org.operamasks.el.eval.TypeCoercion;

public final class Annotation implements Serializable
{
    private String type;
    private Map<String,Object> atts;
    private static final long serialVersionUID = 7751627493018747021L;

    public Annotation(String type) {
        this.type = type;
        this.atts = new LinkedHashMap<String,Object>();
    }
    
    public String getAnnotationType() {
        return type;
    }

    public Map<String,Object> getAttributes() {
        return atts;
    }

    public Object getAttribute(String name) {
        return atts.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String name, Class<T> type) {
        Object obj = atts.get(name);
        if (obj == null && !type.isPrimitive()) {
            return null;
        } else {
            return (T) TypeCoercion.coerce(obj, type);
        }
    }

    public void setAttribute(String name, Object value) {
        atts.put(name, value);
    }

    public void removeAttribute(String name) {
        atts.remove(name);
    }

    public String toString() {
        return "@" + type + TypeCoercion.coerceToString(atts);
    }
}
