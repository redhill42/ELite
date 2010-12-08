/*
 * $Id: DescendantVirtualNode.java,v 1.1 2009/04/24 15:12:53 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.xml;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Collections;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import elite.lang.Closure;
import static org.operamasks.el.resources.Resources.*;

class DescendantVirtualNode extends VirtualNode
    implements Iterable<XmlNode>
{
    static final String WILDCARD = "*";

    protected XmlNode real;

    DescendantVirtualNode(XmlNode parent, String name) {
        super(parent, name);
    }

    protected XmlNode realize(boolean create) {
        if (name.equals(WILDCARD)) {
            return null;
        } else {
            if (real == null)
                real = XmlNode.valueOf(descendant(0));
            return real;
        }
    }

    public Object getValue(ELContext elctx, Object property) {
        if (property instanceof String) {
            if (WILDCARD.equals(this.name)) {
                String name = (String)property;
                if (!name.startsWith("@")) {
                    elctx.setPropertyResolved(true);
                    return new DescendantVirtualNode(parent, name);
                }
            } else {
                return super.getValue(elctx, property);
            }
        } else if (property instanceof Number) {
            Node child = descendant(((Number)property).intValue());
            if (child != null) {
                elctx.setPropertyResolved(true);
                return XmlNode.valueOf(child);
            }
        }
        return null;
    }

    public Class<?> getType(ELContext elctx, Object property) {
        if (property instanceof String) {
            String name = (String)property;
            if (WILDCARD.equals(this.name)) {
                if (!name.startsWith("@")) {
                    elctx.setPropertyResolved(true);
                    return XmlNode.class;
                }
            } else {
                return super.getType(elctx, property);
            }
        } else if (property instanceof Number) {
            elctx.setPropertyResolved(true);
            return XmlNode.class;
        }
        return null;
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        if (property instanceof String) {
            if (WILDCARD.equals(this.name)) {
                elctx.setPropertyResolved(true);
                return true;
            } else {
                return super.isReadOnly(elctx, property);
            }
        } else if (property instanceof Number) {
            elctx.setPropertyResolved(true);
            return true;
        }
        return false;
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        if (property instanceof String) {
            if (WILDCARD.equals(this.name)) {
                throw new PropertyNotWritableException(
                    _T(EL_PROPERTY_NOT_WRITABLE, "XmlNode", property));
            } else {
                super.setValue(elctx, property, value);
            }
        } else if (property instanceof Number) {
            throw new PropertyNotWritableException(
                _T(EL_PROPERTY_NOT_WRITABLE, "XmlNode", property));
        }
    }

    public XmlNode filter(Closure pred) {
        return new FilterVirtualNode(parent, this, pred);
    }

    public Iterator<XmlNode> iterator() {
        Element elem = (Element)parent.toDOM();
        if (elem != null) {
            return new NodeListItr(elem.getElementsByTagName(name));
        } else {
            return Collections.<XmlNode>emptyList().iterator();
        }
    }
    
    private static class NodeListItr implements Iterator<XmlNode> {
        private NodeList nlist;
        private int length;
        private int index;

        NodeListItr(NodeList nlist) {
            this.nlist = nlist;
            this.length = nlist.getLength();
            this.index = 0;
        }

        public boolean hasNext() {
            return index < length;
        }

        public XmlNode next() {
            Node child = nlist.item(index);
            if (child == null)
                throw new NoSuchElementException();
            index++;
            return XmlNode.valueOf(child);
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private Node descendant(int index) {
        Element elem = (Element)parent.toDOM();
        if (elem != null) {
            NodeList nlist = elem.getElementsByTagName(name);
            return nlist.item(index);
        }
        return null;
    }
}
