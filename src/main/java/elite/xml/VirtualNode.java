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

package elite.xml;

import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.MethodNotFoundException;

import org.w3c.dom.Node;
import elite.lang.Closure;

abstract class VirtualNode extends XmlNode
{
    protected final XmlNode parent;
    protected final String name;

    VirtualNode(XmlNode parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    public Object getValue(ELContext elctx, Object property) {
        if (property instanceof String) {
            XmlNode real = realize(false);
            if (real != null) {
                return real.getValue(elctx, property);
            }

            String name = (String)property;
            if (name.startsWith("@")) {
                return null;
            }  else if (name.equals("_")) {
                elctx.setPropertyResolved(true);
                return new DescendantVirtualNode(this, DescendantVirtualNode.WILDCARD);
            } else {
                elctx.setPropertyResolved(true);
                return new ContainerVirtualNode(this, name);
            }
        }
        return null;
    }

    public Class<?> getType(ELContext elctx, Object property) {
        if (property instanceof String) {
            XmlNode real = realize(false);
            if (real != null) {
                return real.getType(elctx, property);
            }

            String name = (String)property;
            if (name.startsWith("@")) {
                return null;
            } else {
                elctx.setPropertyResolved(true);
                return XmlNode.class;
            }
        }
        return null;
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        if (property instanceof String)
            elctx.setPropertyResolved(true);
        return false;
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        if (property instanceof String) {
            XmlNode real = realize(value != null);
            if (real != null) {
                real.setValue(elctx, property, value);
            }
        }
    }

    public MethodInfo getMethodInfo(ELContext ctx, String name)
        throws MethodNotFoundException
    {
        XmlNode real = realize(false);
        if (real == null)
            throw new MethodNotFoundException("method not found: " + name);
        return real.getMethodInfo(ctx, name);
    }

    public Object invoke(ELContext ctx, String name, Closure[] args)
        throws MethodNotFoundException
    {
        XmlNode real = realize(false);
        if (real == null)
            throw new MethodNotFoundException("method not found: " + name);
        return real.invoke(ctx, name, args);
    }

    protected boolean ischild(Node n) {
        return n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName());
    }

    protected Node firstChild(Node parent) {
        Node n = parent.getFirstChild();
        while (n != null) {
            if (ischild(n))
                return n;
            n = n.getNextSibling();
        }
        return null;
    }

    protected Node lastChild(Node parent) {
        Node n = parent.getLastChild();
        while (n != null) {
            if (ischild(n))
                return n;
            n = n.getPreviousSibling();
        }
        return null;
    }

    protected Node child(Node parent, int index) {
        Node n = parent.getFirstChild();
        int i = index;
        while (i >= 0 && n != null) {
            if (ischild(n)) {
                if (i == 0)
                    return n;
                i--;
            }
            n = n.getNextSibling();
        }
        if (i == 0) {
            return null;
        } else {
            throw new IndexOutOfBoundsException(""+index);
        }
    }

    public Object coerce(Class type) {
        XmlNode real = realize(false);
        return (real == null) ? null : real.coerce(type);
    }

    public Node toDOM() {
        XmlNode real = realize(false);
        return (real == null) ? null : real.toDOM();
    }

    public String toString() {
        XmlNode real = realize(false);
        return (real == null) ? null : real.toString();
    }

    public String toXMLString() {
        XmlNode real = realize(false);
        return (real == null) ? null : real.toXMLString();
    }
}
