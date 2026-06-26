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

package elite.lang;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import javax.el.ELContext;

import elite.lang.annotation.Expando;
import org.operamasks.el.eval.Control;
import org.operamasks.el.resolver.ClassResolver;

/**
 * Input/Output functions
 */
@SuppressWarnings("unused")
public final class IO
{
    private IO() {}

    // Module initialization

    public static void __init__(ELContext elctx) {
        ClassResolver resolver = ClassResolver.getInstance(elctx);
        resolver.addImport("java.io.*");
    }

    // File operations

    /**
     * 打开并读取文件, 过程结束时关闭文件.
     */
    @Expando
    public static void read(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        try (InputStream stream = new FileInputStream(file)) {
            proc.call(elctx, stream);
        }
    }

    /**
     * 打开并写入文件, 过程结束时关闭文件.
     */
    @Expando
    public static void write(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        try (OutputStream stream = new FileOutputStream(file)) {
            proc.call(elctx, stream);
        }
    }

    /**
     * 按文本方式读取文件, 过程结束时关闭文件.
     */
    @Expando
    public static void readText(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        try (InputStream stream = new FileInputStream(file)) {
            Reader reader = new BufferedReader(new InputStreamReader(stream));
            proc.call(elctx, reader);
        }
    }

    /**
     * 按文本方式读取文件, 过程结束时关闭文件.
     */
    @Expando
    public static void readText(ELContext elctx, File file, String charset, Closure proc)
        throws IOException
    {
        try (InputStream stream = new FileInputStream(file)) {
            Reader reader = new BufferedReader(new InputStreamReader(stream, charset));
            proc.call(elctx, reader);
        }
    }

    /**
     * 按文本方式写入文件, 过程结束时关闭文件.
     */
    @Expando
    public static void writeText(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        try (OutputStream stream = new FileOutputStream(file)) {
            Writer writer = new OutputStreamWriter(stream);
            proc.call(elctx, writer);
            writer.flush();
        }
    }

    /**
     * 按文本方式写入文件, 过程结束时关闭文件.
     */
    @Expando
    public static void writeText(ELContext elctx, File file, String charset, Closure proc)
        throws IOException
    {
        try (OutputStream stream = new FileOutputStream(file)) {
            Writer writer = new OutputStreamWriter(stream, charset);
            proc.call(elctx, writer);
            writer.flush();
        }
    }

    /**
     * 打开并读入文件的每一行, 过程结束时关闭文件.
     */
    @Expando
    public static void eachLine(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        try (InputStream stream = new FileInputStream(file)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    proc.call(elctx, line);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        }
    }

    /**
     * 打开并读入文件的每一行, 过程结束时关闭文件.
     */
    @Expando
    public static void eachLine(ELContext elctx, File file, String charset, Closure proc)
        throws IOException
    {
        try (InputStream stream = new FileInputStream(file)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, charset));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    proc.call(elctx, line);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        }
    }

    /**
     * 读取文件内容到一个字节数组中.
     */
    @Expando
    public static byte[] getBytes(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            FileChannel channel = stream.getChannel();
            byte[] bytes = new byte[(int)channel.size()];
            channel.read(ByteBuffer.wrap(bytes));
            return bytes;
        }
    }

    /**
     * 读取文件的一部分到一个字节数组中.
     * <p>
     * range参数指定文件的读取范围, 其起始值不能小于零, 其步长必须为1. 当起始值
     * 超出文件结尾时返回长度为0的字节数组, 当结束值超出文件结尾时仅读到文件结尾.
     * 范围结束值可以不指定, 这时将读到文件结尾.
     */
    @Expando(name="[]")
    public static byte[] extractBytes(File file, Range range) throws IOException {
        long begin = range.getBegin();
        long end = range.getEnd();
        if (begin < 0 || end < begin || range.getStep() != 1) {
            throw new IllegalArgumentException(range.toString());
        }

        try (FileInputStream stream = new FileInputStream(file)) {
            FileChannel channel = stream.getChannel();
            channel.position(begin);
            if (end >= channel.size())
                end = channel.size() - 1;
            int size = (int)(end - channel.position() + 1);
            if (size <= 0)
                return new byte[0];
            byte[] bytes = new byte[size];
            channel.read(ByteBuffer.wrap(bytes));
            return bytes;
        }
    }

    /**
     * 按文本方式读取文件内容到一个字符串.
     */
    @Expando
    public static String getText(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            Reader reader = new InputStreamReader(stream);
            StringBuilder buf = new StringBuilder((int)stream.getChannel().size());
            for (int c; (c = reader.read()) != -1; ) {
                buf.append((char)c);
            }
            return buf.toString();
        }
    }

    /**
     * 按文本方式读取文件内容到一个字符串.
     */
    @Expando
    public static String getText(File file, String charset) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            Reader reader = new InputStreamReader(stream, charset);
            StringBuilder buf = new StringBuilder((int)stream.getChannel().size());
            for (int c; (c = reader.read()) != -1; ) {
                buf.append((char)c);
            }
            return buf.toString();
        }
    }

    // URL operations

    /**
     * 读取URL, 过程结束时关闭文件.
     */
    @Expando
    public static void read(ELContext elctx, URL url, Closure proc)
        throws IOException
    {
        try (InputStream stream = url.openStream()) {
            proc.call(elctx, stream);
        }
    }

    /**
     * 写入URL, 过程结束时关闭文件.
     */
    @Expando
    public static void write(ELContext elctx, URL url, Closure proc)
        throws IOException
    {
        URLConnection uc = url.openConnection();
        uc.setDoOutput(true);
        try (OutputStream stream = uc.getOutputStream()) {
            proc.call(elctx, stream);
        }
    }

    /**
     * 按文本方式读取URL, 过程结束时关闭文件.
     */
    @Expando
    public static void readText(ELContext elctx, URL url, Closure proc)
        throws IOException
    {
        try (InputStream stream = url.openStream()) {
            Reader reader = new BufferedReader(new InputStreamReader(stream));
            proc.call(elctx, reader);
        }
    }

    /**
     * 按文本方式写入URL, 过程结束时关闭文件.
     */
    @Expando
    public static void writeText(ELContext elctx, URL url, Closure proc)
        throws IOException
    {
        URLConnection uc = url.openConnection();
        uc.setDoOutput(true);
        try (OutputStream stream = uc.getOutputStream()) {
            Writer writer = new OutputStreamWriter(stream);
            proc.call(elctx, writer);
            writer.flush();
        }
    }

    /**
     * 按文本方式写入URL, 过程结束时关闭文件.
     */
    @Expando
    public static void writeText(ELContext elctx, URL url, String charset, Closure proc)
        throws IOException
    {
        URLConnection uc = url.openConnection();
        uc.setDoOutput(true);
        try (OutputStream stream = uc.getOutputStream()) {
            Writer writer = new OutputStreamWriter(stream, charset);
            proc.call(elctx, writer);
            writer.flush();
        }
    }

    /**
     * 读入URL的每一行, 过程结束时关闭文件.
     */
    @Expando
    public static void eachLine(ELContext elctx, URL url, Closure proc)
        throws IOException
    {
        try (InputStream stream = url.openStream()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    proc.call(elctx, line);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        }
    }

    /**
     * 读入URL的每一行, 过程结束时关闭文件.
     */
    @Expando
    public static void eachLine(ELContext elctx, URL url, String charset, Closure proc)
        throws IOException
    {
        try (InputStream stream = url.openStream()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, charset));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    proc.call(elctx, line);
                } catch (Control.Continue c) {
                    continue;
                } catch (Control.Break b) {
                    break;
                }
            }
        }
    }

    /**
     * 读取URL内容到一个字节数组中.
     */
    @Expando
    public static byte[] getBytes(URL url) throws IOException {
        try (InputStream stream = new BufferedInputStream(url.openStream())) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (int b; (b = stream.read()) != -1; ) {
                buf.write(b);
            }
            return buf.toByteArray();
        }
    }

    /**
     * 按文本方式读取URL内容到一个字符串.
     */
    @Expando
    public static String getText(URL url) throws IOException {
        try (InputStream stream = url.openStream()) {
            Reader reader = new InputStreamReader(stream);
            StringBuilder buf = new StringBuilder();
            for (int c; (c = reader.read()) != -1; ) {
                buf.append((char)c);
            }
            return buf.toString();
        }
    }

    /**
     * 按文本方式读取URL内容到一个字符串.
     */
    @Expando
    public static String getText(URL url, String charset) throws IOException {
        try (InputStream stream = url.openStream()) {
            Reader reader = new InputStreamReader(stream, charset);
            StringBuilder buf = new StringBuilder();
            for (int c; (c = reader.read()) != -1; ) {
                buf.append((char)c);
            }
            return buf.toString();
        }
    }

    // Object serialization

    /**
     * 将对象序列化到指定文件.
     */
    @Expando
    public static void dump(File file, Object obj) throws IOException {
        try (OutputStream stream = new FileOutputStream(file)) {
            ObjectOutputStream out = new ObjectOutputStream(stream);
            out.writeObject(obj);
            out.flush();
        }
    }

    /**
     * 从指定文件装载对象.
     */
    @Expando
    public static Object load(File file) throws IOException, ClassNotFoundException {
        try (InputStream stream = new FileInputStream(file)) {
            ObjectInputStream in = new ObjectInputStream(stream);
            return in.readObject();
        }
    }

    /**
     * 将对象序列化为一个字节数组.
     */
    public static byte[] dump(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(obj);
        out.close();
        return bos.toByteArray();
    }

    /**
     * 从一个字节数组中读取对象.
     */
    public static Object load(byte[] bytes) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        ObjectInputStream in = new ObjectInputStream(bis);
        return in.readObject();
    }
}
