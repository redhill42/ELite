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

package org.operamasks.el.parser;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import static org.operamasks.el.resources.Resources.*;

class XMLParser
{
    // node types
    private static final int ROOT = 0;
    private static final int TAG  = 1;
    private static final int TEXT = 2;

    // A temporary node used during parsing
    static abstract class Node {
        int type, pos;
        ArrayList<Node> children = new ArrayList<Node>();

        Node(int type, int pos) {
            this.type = type;
            this.pos = pos;
        }

        void append(Node node) {
            children.add(node);
        }

        abstract ELNode transform();
    }

    static class RootNode extends Node {
        RootNode(int pos) {
            super(ROOT, pos);
        }

        ELNode transform() {
            assert children.size() == 1;
            return children.get(0).transform();
        }
    }

    static class TagNode extends Node {
        String name;
        Map<String,ELNode> attributes;

        TagNode(int pos) {
            super(TAG, pos);
        }

        ELNode transform() {
            ELNode tag;
            ELNode[] keys = null;
            ELNode[] values = null;
            ELNode[] kids = null;

            if (name.startsWith("$")) {
                tag = new ELNode.IDENT(pos, name.substring(1));
            } else {
                tag = new ELNode.STRINGVAL(pos, name);
            }

            if (attributes != null) {
                int size = attributes.size();
                keys = new ELNode[size];
                values = new ELNode[size];
                int i = 0;
                for (Map.Entry<String,ELNode> e : attributes.entrySet()) {
                    String key = e.getKey();
                    ELNode value = e.getValue();
                    if (key.startsWith("$")) {
                        keys[i] = new ELNode.IDENT(value.pos, key.substring(1));
                    } else {
                        keys[i] = new ELNode.STRINGVAL(value.pos, key);
                    }
                    values[i] = value;
                    i++;
                }
            }

            if (!children.isEmpty()) {
                int size = children.size();
                kids = new ELNode[size];
                for (int i = 0; i < size; i++) {
                    kids[i] = children.get(i).transform();
                }
            }

            return new ELNode.XML(pos, tag, keys, values, kids);
        }
    }

    static class TextNode extends Node {
        ELNode content;

        TextNode(int pos, ELNode content) {
            super(TEXT, pos);
            this.content = content;
        }

        ELNode transform() {
            return content;
        }
    }

    private Parser s;
    private char next;

    private XMLParser(Parser s) {
        this.s = s;
    }

    /**
     * Parse the XML fragment and encapsulate it into an ELNode.XML node.
     */
    public static ELNode parse(Parser s) {
        XMLParser p = new XMLParser(s);
        Node root = new RootNode(s.pos);
        p.next = '<';  // already seen '<' when parser gives us control
        p.parse(root); // parse the XML fragment
        s.scan();      // advance to next token to continue parse EL program
        return root.transform(); // transform to ELNode
    }

    private void parse(Node body) {
        int state = '>';
        int pos = s.pos;
        StringBuilder textBuf = new StringBuilder();

        for (;;) {
            switch (state) {
            case '>': /* first character after a '>' */
                textBuf.setLength(0);
                state = 'x';
                pos = s.pos;
                // fall through

            case 'x': /* character between '>' and '<' */
                if (next == '<') {
                    state = '<';
                    pos = s.pos;
                } else {
                    textBuf.append(next);
                }
                break;

            case '<': /* seen '<' */
                if (next == '/') {
                    state = '/';
                } else if (next == '!') {
                    state = '!';
                } else {
                    processTemplateText(pos, textBuf.toString(), body);
                    if (parseTag(pos, scanQName(pos), body)) {
                        return;
                    } else {
                        state = '>';
                    }
                }
                break;

            case '/': /* seen </ */
                processTemplateText(pos, textBuf.toString(), body);
                parseCloseTag(pos, scanQName(pos), body);
                return;

            case '!': /* seen <! */
                if (next == '-') {
                    state = '-';
                } else {
                    textBuf.append("<!");
                    state = 'x';
                }
                break;

            case '-': /* seen <!- */
                if (next == '-') {
                    next = read();
                    readToMatch("-->");
                    state = 'x';
                } else {
                    textBuf.append("<!-");
                    state = 'x';
                }
                break;
            }

            next = read();
        }
    }

    private void processTemplateText(int pos, String text, Node body) {
        if ((text = text.trim()).length() != 0) {
            ELNode node = s.parseEmbedExpression(pos, text);
            if (node instanceof ELNode.Composite) {
                for (ELNode e : ((ELNode.Composite)node).elems) {
                    body.append(new TextNode(e.pos, e));
                }
            } else {
                body.append(new TextNode(node.pos, node));
            }
        }
    }

    private boolean parseTag(int pos, String tag, Node body) {
        Map<String,ELNode> atts = null;
        boolean endSlash = false;

        // Check if we're already at the end
        if (next == '/') {
            endSlash = true;
            if ((next = read()) != '>') {
                throw s.parseError(pos, _T(XML_NO_GT_AFTER_SLASH));
            }
        } else if (next != '>') {
            atts = new LinkedHashMap<String, ELNode>();
            endSlash = scanNameValuePairs(atts);
            if (atts.isEmpty()) atts = null;
        }

        TagNode node = new TagNode(pos);
        node.name = tag;
        node.attributes = atts;
        body.append(node);

        if (!endSlash) {
            // Recursively parse the tag body
            next = read();
            parse(node);
        }

        return body.type == ROOT; // finish XML parsing if root node
    }

    private void parseCloseTag(int pos, String tag, Node body) {
        if (next != '>') {
            throw s.parseError(pos, _T(XML_NO_GT_IN_CLOSE_TAG, tag));
        }

        if (body == null || body.type == ROOT) {
            throw s.parseError(pos, _T(XML_NO_START_TAG, tag));
        } else if (!tag.equals(((TagNode)body).name)) {
            throw s.parseError(pos, _T(XML_CLOSE_TAG_NOT_MATCH, tag, ((TagNode)body).name));
        }
    }

    private String scanQName(int pos) {
        StringBuilder buf = new StringBuilder();

        if (next == '$') {
            next = read();
            if (Character.isJavaIdentifierStart(next)) {
                buf.append("$");
                while (Character.isJavaIdentifierPart(next)) {
                    buf.append(next);
                    next = read();
                }
            } else {
                throw s.parseError(pos, _T(XML_ILLEGAL_NAME_CHAR, next));
            }
        } else if (Character.isLetter(next) || next == ':' || next == '_') {
            while (Character.isLetterOrDigit(next) ||
                    next == '.' || next == '-' ||
                    next == '_' || next == ':') {
                buf.append(next);
                next = read();
            }
        } else {
            throw s.parseError(pos, _T(XML_ILLEGAL_NAME_CHAR, next));
        }

        return buf.toString();
    }

    private boolean scanNameValuePairs(Map<String,ELNode> table) {
        String name;
        ELNode value;

        while (true) {
            // skip whitespaces
            int pos = s.pos;
            while (Character.isWhitespace(next)) {
                pos = s.pos;
                next = read();
            }

            if (next == '>') {
                return false;
            } else if (next == '/') {
                if ((next = read()) == '>') {
                    return true;
                } else {
                    throw s.parseError(pos, _T(XML_NO_GT_AFTER_SLASH));
                }
            }

            name = scanQName(pos);
            scanEqualSign(pos);
            value = scanValue();

            if (table.containsKey(name)) {
                throw s.parseError(pos, _T(XML_DUPLICATE_ATTRIBUTE, name));
            } else {
                table.put(name, value);
            }
        }
    }

    private void scanEqualSign(int pos) {
        // skip to first non-whitespace after =
        boolean seenEqualSign = false;
        while (Character.isWhitespace(next) || next == '=') {
            if (next == '=') {
                if (seenEqualSign)
                    break;
                seenEqualSign = true;
            }
            next = read();
        }
        if (!seenEqualSign) {
            throw s.parseError(pos, _T(XML_NO_EQ_IN_NAME_VALUE_PAIR));
        }
    }

    private ELNode scanValue() {
        int p = s.pos;
        char q = next;
        StringBuilder buf = new StringBuilder();

        if (q != '"' && q != '\'') {
            throw s.parseError(p, _T(XML_UNQUOTED_VALUE));
        }

        for (;;) {
            next = read();
            if (next == q) {
                next = read();
                break;
            } else {
                buf.append(next);
            }
        }

        if (q == '"') {
            return s.parseEmbedExpression(p, buf.toString());
        } else {
            return new ELNode.STRINGVAL(p, buf.toString());
        }
    }

    private char read() {
        int c = s.ch;
        if (c == Token.EOI) {
            throw s.incomplete(_T(XML_UNEXPECTED_EOI));
        } else if (c == '\r') {
            if (s.nextchar() == '\n') {
                c = '\n';
                s.nextchar();
            }
            s.pos = Position.nextline(s.pos);
        } else if (c == '\n') {
            s.nextchar();
            s.pos = Position.nextline(s.pos);
        } else {
            s.nextchar();
        }
        return (char)c;
    }

    private void readToMatch(String match) {
        int state = 0;

        for (;;) {
            if (next == match.charAt(state)) {
                if (++state == match.length())
                    return;
                next = read();
            } else if (state > 0) {
                state -= matchPrefix(match, state, next);
            } else {
                next = read();
            }
        }
    }

    private static int matchPrefix(String match, int length, char nextchar) {
        int prefix;
        for (prefix = 1; prefix < length; prefix++) {
            if (match.substring(prefix, length).equals(match.substring(0, length-prefix)) &&
                nextchar == match.charAt(length-prefix)) {
                break;
            }
        }
        return prefix;
    }
}
