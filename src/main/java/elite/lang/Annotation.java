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

import java.io.Serializable;
import java.util.Map;
import java.util.LinkedHashMap;

import org.elite.eval.TypeCoercion;

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
