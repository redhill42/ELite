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

import java.lang.reflect.Method;
import java.io.StringWriter;
import java.io.IOException;
import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.MethodNotFoundException;
import javax.el.PropertyNotFoundException;
import javax.el.PropertyNotWritableException;

import org.w3c.dom.Node;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Attr;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.util.DOMWriter;
import elite.lang.Closure;
import static org.operamasks.el.resources.Resources.*;

class RealNode extends XmlNode
{
    private final Node dom;

    RealNode(Node dom) {
        this.dom = dom;
    }

    public Node toDOM() {
        return dom;
    }

    protected XmlNode realize(boolean create) {
        return this;
    }

    public Object getValue(ELContext elctx, Object property) {
        if ((dom instanceof Element) && (property instanceof String)) {
            Element elem = (Element)dom;
            String name = (String)property;

            if (name.startsWith("@@")) {
                elctx.setPropertyResolved(true);
                name = name.intern();
                if (name == "@@name") {
                    return elem.getTagName();
                } else if (name == "@@value") {
                    return elem.getTextContent();
                } else if (name == "@@prefix") {
                    return elem.getPrefix();
                } else if (name == "@@uri") {
                    return elem.getNamespaceURI();
                } else if (name == "@@localName") {
                    return elem.getLocalName();
                } else if (name == "@@parent") {
                    return XmlNode.valueOf(elem.getParentNode());
                } else if (name == "@@first") {
                    return XmlNode.valueOf(elem.getFirstChild());
                } else if (name == "@@last") {
                    return XmlNode.valueOf(elem.getLastChild());
                } else if (name == "@@next") {
                    return XmlNode.valueOf(elem.getNextSibling());
                } else if (name == "@@previous") {
                    return XmlNode.valueOf(elem.getPreviousSibling());
                } else if (name == "@@child") {
                    return new XmlNodeList(elctx, elem, elem.getChildNodes());
                } else {
                    throw new PropertyNotFoundException(_T(EL_PROPERTY_NOT_FOUND, "XmlNode", name));
                }
            } else if (name.startsWith("@")) {
                Attr attr = elem.getAttributeNode(name.substring(1));
                if (attr != null) {
                    elctx.setPropertyResolved(true);
                    return attr.getValue();
                }
            } else if (name.equals("_")) {
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
        Object value = getValue(elctx, property);
        return (value == null) ? null : value.getClass();
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        elctx.setPropertyResolved(true);
        return false;
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        if ((dom instanceof Element) && (property instanceof String)) {
            Element elem = (Element)dom;
            String name = (String)property;
            elctx.setPropertyResolved(true);

            if (name.startsWith("@@")) {
                if ("@@value".equals(name)) {
                    String text = value == null ? null : TypeCoercion.coerceToString(value);
                    elem.setTextContent(text);
                } else if ("@@child".equals(name)) {
                    // replace child nodes with new content
                    Node newnode = coerceToNode(elctx, value);
                    elem.setTextContent(null); // remove all child nodes
                    if (newnode != null) {
                        elem.appendChild(newnode);
                    }
                } else {
                    throw new PropertyNotWritableException(
                        _T(EL_PROPERTY_NOT_WRITABLE, "XmlNode", name));
                }
            } else if (name.startsWith("@")) {
                // set or remove attribute
                name = name.substring(1);
                if (value == null) {
                    elem.removeAttribute(name);
                } else {
                    elem.setAttribute(name, TypeCoercion.coerceToString(value));
                }
            } else if (name.equals("_")) {
                throw new PropertyNotWritableException(
                    _T(EL_PROPERTY_NOT_WRITABLE, "XmlNode", name));
            } else {
                Node newnode;
                if (value == null) {
                    newnode = null;
                } else if (canCoerceToNode(elctx, value)) {
                    newnode = coerceToNode(elctx, value);
                } else {
                    newnode = elem.getOwnerDocument().createElement(name);
                    newnode.setTextContent(TypeCoercion.coerceToString(value));
                }

                // remove all child nodes except first one, the first node
                // is used as a reference node to insert new node
                Node oldnode = null;
                for (Node n = elem.getFirstChild(); n != null; ) {
                    if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                        Node k = n; n = n.getNextSibling();
                        if (oldnode == null) {
                            oldnode = k;
                        } else {
                            elem.removeChild(k);
                        }
                    } else {
                        n = n.getNextSibling();
                    }
                }

                // replace old node with new content
                if (newnode != null) {
                    if (oldnode != null) {
                        elem.replaceChild(newnode, oldnode);
                    } else {
                        elem.appendChild(newnode);
                    }
                } else {
                    if (oldnode != null) {
                        elem.removeChild(oldnode);
                    }
                }
            }
        }
    }

    public MethodInfo getMethodInfo(ELContext ctx, String name)
        throws MethodNotFoundException {
        return null; // TBD
    }

    public Object invoke(ELContext ctx, String name, Closure[] args)
        throws MethodNotFoundException
    {
        // DOM API doesn't have overloaded methods, so it's safe to
        // resolve method only with method name and argument length
        Method method = ELEngine.resolveMethod(ctx, dom.getClass(), name, args);
        if (method == null) {
            throw new MethodNotFoundException("method not found: " + name);
        }

        // coerce arguments to Node type if necessary
        Class[] types = method.getParameterTypes();
        Closure[] values = args.clone();
        assert types.length == args.length;
        for (int i = 0; i < types.length; i++) {
            if (Node.class.isAssignableFrom(types[i])) {
                Object value = args[i].getValue(ctx);
                values[i] = new LiteralClosure(coerceToNode(ctx, value));
            }
        }

        Object result = ELEngine.invokeMethod(ctx, dom, method, values);
        if (result instanceof Node) {
            result = valueOf((Node)result);
        } else if (result instanceof NodeList) {
            result = new XmlNodeList(ctx, dom, (NodeList)result);
        }
        return result;
    }

    public Object coerce(Class type) {
        if (textonly()) {
            return TypeCoercion.coerce(dom.getTextContent(), type);
        } else {
            return null;
        }
    }

    public String toString() {
        if (textonly()) {
            return dom.getTextContent();
        } else {
            return toXMLString();
        }
    }

    private boolean textonly() {
        for (Node kid = dom.getFirstChild(); kid != null; kid = kid.getNextSibling()) {
            switch (kid.getNodeType()) {
            case Node.TEXT_NODE:
            case Node.CDATA_SECTION_NODE:
            case Node.ENTITY_REFERENCE_NODE:
                break;
            default:
                return false;
            }
        }
        return true;
    }

    public String toXMLString() {
        try {
            StringWriter str = new StringWriter();
            DOMWriter writer = new DOMWriter(str);
            writer.writeNode(dom);
            writer.flush();
            return str.toString();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public int hashCode() {
        return dom.hashCode();
    }
}
