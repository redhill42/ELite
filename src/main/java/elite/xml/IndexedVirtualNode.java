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

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import javax.el.ELContext;
import elite.lang.annotation.Expando;

class IndexedVirtualNode extends VirtualNode
{
    protected final int index;
    protected XmlNode real;

    IndexedVirtualNode(XmlNode parent, String name, int index) {
        super(parent, name);
        this.index = index;
    }

    @Override
    protected XmlNode realize(boolean create) {
        if (real == null) {
            // first realize parent node
            XmlNode parent = this.parent.realize(create);
            if (parent == null) {
                return null;
            }

            // does node already exist?
            Element parentNode = (Element)parent.toDOM();
            Node childNode = child(parentNode, index);

            // create node if it doesn't exist!
            if (create && childNode == null) {
                childNode = parentNode.getOwnerDocument().createElement(name);
                parentNode.appendChild(childNode);
            }

            real = XmlNode.valueOf(childNode);
        }

        return real;
    }

    @Expando(name="+=")
    public XmlNode appendChild(ELContext elctx, Object value) {
        Node newnode = coerceToNode(elctx, value);
        if (newnode != null) {
            Node parent = this.parent.realize(true).toDOM();
            Node refnode = this.toDOM();
            if (refnode != null)
                refnode = refnode.getNextSibling();
            parent.insertBefore(newnode, refnode);
        }
        return this;
    }
}
