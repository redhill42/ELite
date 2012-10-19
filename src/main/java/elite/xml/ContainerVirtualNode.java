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

import java.util.Iterator;
import java.util.Collections;
import java.util.NoSuchElementException;
import javax.el.ELContext;

import org.w3c.dom.Node;
import org.operamasks.el.eval.TypeCoercion;
import elite.lang.annotation.Expando;
import elite.lang.Closure;

class ContainerVirtualNode extends VirtualNode
    implements Iterable<XmlNode>
{
    protected XmlNode real;

    ContainerVirtualNode(XmlNode parent, String name) {
        super(parent, name);
    }
    
    protected XmlNode realize(boolean create) {
        if (real == null) {
            // first realize parent node
            XmlNode parent = this.parent.realize(create);
            if (parent == null) {
                return null;
            }

            // does node already exist?
            Node parentNode = parent.toDOM();
            Node childNode = firstChild(parentNode);

            // create node if it doesn't exist!
            if (create && childNode == null) {
                childNode = parentNode.getOwnerDocument().createElement(name);
                parentNode.appendChild(childNode);
            }

            real = XmlNode.valueOf(childNode);
        }

        return real;
    }

    public Object getValue(ELContext elctx, Object property) {
        if (property instanceof String) {
            return super.getValue(elctx, property);
        } else if (property instanceof Number) {
            int index = ((Number)property).intValue();
            if (index >= 0) {
                elctx.setPropertyResolved(true);
                return new IndexedVirtualNode(parent, name, index);
            }
        }
        return null;
    }

    public Class<?> getType(ELContext elctx, Object property) {
        if (property instanceof String) {
            return super.getType(elctx, property);
        } else if (property instanceof Number) {
            elctx.setPropertyResolved(true);
            return XmlNode.class;
        } else {
            return null;
        }
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        if (property instanceof String) {
            return super.isReadOnly(elctx, property);
        } else if (property instanceof Number) {
            elctx.setPropertyResolved(true);
            return false;
        } else {
            return false;
        }
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        if (property instanceof String) {
            super.setValue(elctx, property, value);
            return;
        }

        if (property instanceof Number) {
            XmlNode parent = this.parent.realize(value != null);
            if (parent == null) {
                return;
            }

            Node elem = parent.toDOM();
            int index = ((Number)property).intValue();
            Node oldnode = child(elem, index);

            if (value == null) {
                // remove old child
                if (oldnode != null) {
                    elem.removeChild(oldnode);
                }
            } else if (canCoerceToNode(elctx, value)) {
                // replace old child with new child, if old child
                // does not exist then the new child is appended
                Node newnode = coerceToNode(elctx, value);
                if (newnode != null) { // may be a virtual node so check for null
                    if (oldnode != null) {
                        elem.replaceChild(newnode, oldnode);
                    } else {
                        elem.appendChild(newnode);
                    }
                }
            } else {
                // set old child text content, if old child does not
                // exist then a new child is created
                String text = TypeCoercion.coerceToString(value);
                if (oldnode != null) {
                    oldnode.setTextContent(text);
                } else {
                    Node newnode = elem.getOwnerDocument().createElement(name);
                    newnode.setTextContent(text);
                    elem.appendChild(newnode);
                }
            }

            elctx.setPropertyResolved(true);
            return;
        }
    }

    @Expando(name="+=")
    public XmlNode appendChild(ELContext elctx, Object value) {
        Node newnode = coerceToNode(elctx, value);
        if (newnode != null) {
            Node parent = this.parent.realize(true).toDOM();
            Node refnode = lastChild(parent);
            if (refnode != null)
                refnode = refnode.getNextSibling();
            parent.insertBefore(newnode, refnode);
        }
        return this;
    }

    public XmlNode filter(Closure pred) {
        return new FilterVirtualNode(parent, this, pred);
    }

    public Iterator<XmlNode> iterator() {
        Node elem = parent.toDOM();
        if (elem != null) {
            return new NodeItr(elem, name);
        } else {
            return Collections.<XmlNode>emptyList().iterator();
        }
    }

    private static class NodeItr implements Iterator<XmlNode> {
        private final String name;
        private Node parent, next, lastRet;

        NodeItr(Node parent, String name) {
            this.name = name;
            this.parent = parent;
            this.next = nextMatch(parent.getFirstChild());
        }

        private Node nextMatch(Node node) {
            while (node != null &&
                   !(node.getNodeType() == Node.ELEMENT_NODE &&
                     name.equals(node.getNodeName())))
                node = node.getNextSibling();
            return node;
        }

        public boolean hasNext() {
            return next != null;
        }

        public XmlNode next() {
            if (next == null)
                throw new NoSuchElementException();
            lastRet = next;
            next = nextMatch(lastRet.getNextSibling());
            return XmlNode.valueOf(lastRet);
        }

        public void remove() {
            if (lastRet == null)
                throw new IllegalStateException();
            parent.removeChild(lastRet);
            lastRet = null;
        }
    }
}
