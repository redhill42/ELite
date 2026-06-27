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
import org.elite.resolver.ClassResolver;
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
