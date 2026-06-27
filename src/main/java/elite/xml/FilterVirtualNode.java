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

package elite.xml;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

import elite.lang.Closure;
import org.elite.eval.ELEngine;
import static org.elite.resources.Resources.*;

class FilterVirtualNode extends VirtualNode
    implements Iterable<XmlNode>
{
    protected final Iterable<XmlNode> scope;
    protected final Closure filter;
    protected XmlNode real;

    FilterVirtualNode(XmlNode parent, Iterable<XmlNode> scope, Closure filter) {
        super(parent, null);
        this.scope = scope;
        this.filter = filter;
    }

    protected XmlNode realize(boolean create) {
        if (real == null) {
            ELContext elctx = ELEngine.getCurrentELContext();
            for (XmlNode node : scope) {
                if (filter.test(elctx, node)) {
                    return real = node;
                }
            }
        }
        return real;
    }

    public Object getValue(ELContext elctx, Object property) {
        if (property instanceof String) {
            return super.getValue(elctx, property);
        } else if (property instanceof Number) {
            int index = ((Number)property).intValue();
            Iterator<XmlNode> it = scope.iterator();
            while (index >= 0 && it.hasNext()) {
                XmlNode node = it.next();
                if (filter.test(elctx, node)) {
                    if (index-- == 0) {
                        elctx.setPropertyResolved(true);
                        return node;
                    }
                }
            }
            return null;
        }
        return null;
    }

    public Class<?> getType(ELContext elctx, Object property) {
        if (property instanceof String) {
            return super.getType(elctx, property);
        } else if (property instanceof Number) {
            elctx.setPropertyResolved(true);
            return XmlNode.class;
        }
        return null;
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        if (property instanceof String) {
            return super.isReadOnly(elctx, property);
        } else if (property instanceof Number) {
            elctx.setPropertyResolved(true);
            return true;
        }
        return false;
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        if (property instanceof String) {
            super.setValue(elctx, property, value);
        } else if (property instanceof Number) {
            throw new PropertyNotWritableException(
                _T(EL_PROPERTY_NOT_WRITABLE, "XmlNode", property));
        }
    }

    public XmlNode filter(Closure pred) {
        return new FilterVirtualNode(parent, this, pred);
    }

    public Iterator<XmlNode> iterator() {
        List<XmlNode> list = new ArrayList<XmlNode>();
        ELContext elctx = ELEngine.getCurrentELContext();
        for (XmlNode node : scope) {
            if (filter.test(elctx, node)) {
                list.add(node);
            }
        }
        return list.iterator();
    }
}
