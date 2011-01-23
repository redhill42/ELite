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
