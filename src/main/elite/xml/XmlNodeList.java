/*
 * $Id: XmlNodeList.java,v 1.2 2009/04/24 15:12:53 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.xml;

import java.util.AbstractList;
import java.util.Collection;

import javax.el.ELContext;

import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

public class XmlNodeList extends AbstractList
{
    protected ELContext elctx;
    protected Node parent;
    protected NodeList list;

    protected XmlNodeList(ELContext elctx, Node parent, NodeList list) {
        this.elctx = elctx;
        this.parent = parent;
        this.list = list;
    }

    public int size() {
        return list.getLength();
    }

    public Object get(int index) {
        if (index < 0 || index >= list.getLength()) {
            throw new IndexOutOfBoundsException(""+index);
        }
        return XmlNode.valueOf(list.item(index));
    }

    public Object set(int index, Object value) {
        if (index < 0 || index > list.getLength()) {
            throw new IndexOutOfBoundsException(""+index);
        }

        Node oldnode = list.item(index);
        Node newnode = XmlNode.coerceToNode(elctx, value);

        if (newnode != null) {
            if (oldnode != null) {
                parent.replaceChild(newnode, oldnode);
            } else {
                parent.appendChild(newnode);
            }
        } else {
            if (oldnode != null) {
                parent.removeChild(oldnode);
            }
        }

        return XmlNode.valueOf(oldnode);
    }

    public boolean add(Object value) {
        Node newnode = XmlNode.coerceToNode(elctx, value);
        if (newnode != null)
            parent.appendChild(newnode);
        return true;
    }

    public void add(int index, Object value) {
        if (index < 0 || index > list.getLength()) {
            throw new IndexOutOfBoundsException(""+index);
        }

        Node newnode = XmlNode.coerceToNode(elctx, value);
        if (newnode != null) {
            parent.insertBefore(newnode, list.item(index));
        }
    }

    public boolean addAll(Collection c) {
        return add(c);
    }

    public Object remove(int index) {
        return set(index, null);
    }

    public boolean remove(Object value) {
        Node node = XmlNode.coerceToNode(elctx, value);
        if (node == null) {
            return false;
        } else if (node.getParentNode() == parent) {
            parent.removeChild(node);
            return true;
        } else {
            return false;
        }
    }

    public boolean removeAll(Collection c) {
        return super.removeAll(c);
    }
}
