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

import java.io.StringReader;
import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.io.File;
import java.net.URL;
import javax.el.ELContext;
import javax.el.ELException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;
import org.xml.sax.InputSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.operamasks.el.resolver.ClassResolver;
import elite.lang.annotation.Expando;

public final class XMLLib
{
    private XMLLib() {}

    public static void __init__(ELContext elctx) {
        ClassResolver resolver = ClassResolver.getInstance(elctx);
        resolver.addImport("org.w3c.dom.*");
    }

    /**
     * 分析给定的字符串并将其解析成XML文档片段, 返回该文档片段的XmlNode包装.
     */
    @Expando
    public static XmlNode toXML(ELContext elctx, String str)
        throws SAXException
    {
        try {
            return parse(elctx, new InputSource(new StringReader(str)));
        } catch (IOException ex) {
            throw new ELException(ex);
        }
    }

    /**
     * 分析给定的输入流并将其解析成XML文档片段, 返回该文档片段的XmlNode包装.
     */
    @Expando
    public static XmlNode getXML(ELContext elctx, Reader reader)
        throws IOException, SAXException
    {
        return parse(elctx, new InputSource(reader));
    }

    /**
     * 分析给定的输入流并将其解析成XML文档片段, 返回该文档片段的XmlNode包装.
     */
    @Expando
    public static XmlNode getXML(ELContext elctx, InputStream ins)
        throws IOException, SAXException
    {
        return parse(elctx, new InputSource(ins));
    }

    /**
     * 分析给定的文件并将其解析成XML文档片段, 返回该文档片段的XmlNode包装.
     */
    @Expando
    public static XmlNode getXML(ELContext elctx, File file)
        throws IOException, SAXException
    {
        return parse(elctx, new InputSource(file.toURI().toString()));
    }

    /**
     * 分析给定的URL并将其解析成XML文档片段, 返回该文档片段的XmlNode包装.
     */
    @Expando
    public static XmlNode getXML(ELContext elctx, URL url)
        throws IOException, SAXException
    {
        return parse(elctx, new InputSource(url.toString()));
    }

    private static XmlNode parse(ELContext elctx, InputSource input)
        throws IOException, SAXException
    {
        try {
            // parse the XML input
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(input);
            Element root = doc.getDocumentElement();

            // import root node into the context Document
            Document owner = (Document)elctx.getContext(Document.class);
            if (owner == null) {
                elctx.putContext(Document.class, doc);
            } else {
                root = (Element)owner.importNode(root, true);
            }

            // return the encapsulation of root element
            return XmlNode.valueOf(root);
        } catch (ParserConfigurationException ex) {
            throw new ELException(ex);
        }
    }
}
