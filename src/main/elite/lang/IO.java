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
        InputStream stream = new FileInputStream(file);
        try {
            proc.call(elctx, stream);
        } finally {
            stream.close();
        }
    }

    /**
     * 打开并写入文件, 过程结束时关闭文件.
     */
    @Expando
    public static void write(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        OutputStream stream = new FileOutputStream(file);
        try {
            proc.call(elctx, stream);
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式读取文件, 过程结束时关闭文件.
     */
    @Expando
    public static void readText(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        InputStream stream = new FileInputStream(file);
        try {
            Reader reader = new BufferedReader(new InputStreamReader(stream));
            proc.call(elctx, reader);
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式读取文件, 过程结束时关闭文件.
     */
    @Expando
    public static void readText(ELContext elctx, File file, String charset, Closure proc)
        throws IOException
    {
        InputStream stream = new FileInputStream(file);
        try {
            Reader reader = new BufferedReader(new InputStreamReader(stream, charset));
            proc.call(elctx, reader);
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式写入文件, 过程结束时关闭文件.
     */
    @Expando
    public static void writeText(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        OutputStream stream = new FileOutputStream(file);
        try {
            Writer writer = new OutputStreamWriter(stream);
            proc.call(elctx, writer);
            writer.flush();
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式写入文件, 过程结束时关闭文件.
     */
    @Expando
    public static void writeText(ELContext elctx, File file, String charset, Closure proc)
        throws IOException
    {
        OutputStream stream = new FileOutputStream(file);
        try {
            Writer writer = new OutputStreamWriter(stream, charset);
            proc.call(elctx, writer);
            writer.flush();
        } finally {
            stream.close();
        }
    }

    /**
     * 打开并读入文件的每一行, 过程结束时关闭文件.
     */
    @Expando
    public static void eachLine(ELContext elctx, File file, Closure proc)
        throws IOException
    {
        InputStream stream = new FileInputStream(file);
        try {
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
        } finally {
            stream.close();
        }
    }

    /**
     * 打开并读入文件的每一行, 过程结束时关闭文件.
     */
    @Expando
    public static void eachLine(ELContext elctx, File file, String charset, Closure proc)
        throws IOException
    {
        InputStream stream = new FileInputStream(file);
        try {
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
        } finally {
            stream.close();
        }
    }

    /**
     * 读取文件内容到一个字节数组中.
     */
    @Expando
    public static byte[] getBytes(File file) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        try {
            FileChannel channel = stream.getChannel();
            byte[] bytes = new byte[(int)channel.size()];
            channel.read(ByteBuffer.wrap(bytes));
            return bytes;
        } finally {
            stream.close();
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

        FileInputStream stream = new FileInputStream(file);
        try {
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
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式读取文件内容到一个字符串.
     */
    @Expando
    public static String getText(File file) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        try {
            Reader reader = new InputStreamReader(stream);
            StringBuilder buf = new StringBuilder((int)stream.getChannel().size());
            for (int c; (c = reader.read()) != -1; ) {
                buf.append((char)c);
            }
            return buf.toString();
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式读取文件内容到一个字符串.
     */
    @Expando
    public static String getText(File file, String charset) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        try {
            Reader reader = new InputStreamReader(stream, charset);
            StringBuilder buf = new StringBuilder((int)stream.getChannel().size());
            for (int c; (c = reader.read()) != -1; ) {
                buf.append((char)c);
            }
            return buf.toString();
        } finally {
            stream.close();
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
        InputStream stream = url.openStream();
        try {
            proc.call(elctx, stream);
        } finally {
            stream.close();
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
        OutputStream stream = uc.getOutputStream();
        try {
            proc.call(elctx, stream);
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式读取URL, 过程结束时关闭文件.
     */
    @Expando
    public static void readText(ELContext elctx, URL url, Closure proc)
        throws IOException
    {
        InputStream stream = url.openStream();
        try {
            Reader reader = new BufferedReader(new InputStreamReader(stream));
            proc.call(elctx, reader);
        } finally {
            stream.close();
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
        OutputStream stream = uc.getOutputStream();
        try {
            Writer writer = new OutputStreamWriter(stream);
            proc.call(elctx, writer);
            writer.flush();
        } finally {
            stream.close();
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
        OutputStream stream = uc.getOutputStream();
        try {
            Writer writer = new OutputStreamWriter(stream, charset);
            proc.call(elctx, writer);
            writer.flush();
        } finally {
            stream.close();
        }
    }

    /**
     * 读入URL的每一行, 过程结束时关闭文件.
     */
    @Expando
    public static void eachLine(ELContext elctx, URL url, Closure proc)
        throws IOException
    {
        InputStream stream = url.openStream();
        try {
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
        } finally {
            stream.close();
        }
    }

    /**
     * 读入URL的每一行, 过程结束时关闭文件.
     */
    @Expando
    public static void eachLine(ELContext elctx, URL url, String charset, Closure proc)
        throws IOException
    {
        InputStream stream = url.openStream();
        try {
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
        } finally {
            stream.close();
        }
    }

    /**
     * 读取URL内容到一个字节数组中.
     */
    @Expando
    public static byte[] getBytes(URL url) throws IOException {
        InputStream stream = new BufferedInputStream(url.openStream());
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (int b; (b = stream.read()) != -1; ) {
                buf.write(b);
            }
            return buf.toByteArray();
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式读取URL内容到一个字符串.
     */
    @Expando
    public static String getText(URL url) throws IOException {
        InputStream stream = url.openStream();
        try {
            Reader reader = new InputStreamReader(stream);
            StringBuilder buf = new StringBuilder();
            for (int c; (c = reader.read()) != -1; ) {
                buf.append((char)c);
            }
            return buf.toString();
        } finally {
            stream.close();
        }
    }

    /**
     * 按文本方式读取URL内容到一个字符串.
     */
    @Expando
    public static String getText(URL url, String charset) throws IOException {
        InputStream stream = url.openStream();
        try {
            Reader reader = new InputStreamReader(stream, charset);
            StringBuilder buf = new StringBuilder();
            for (int c; (c = reader.read()) != -1; ) {
                buf.append((char)c);
            }
            return buf.toString();
        } finally {
            stream.close();
        }
    }

    // Object serialization

    /**
     * 将对象序列化到指定文件.
     */
    @Expando
    public static void dump(File file, Object obj) throws IOException {
        OutputStream stream = new FileOutputStream(file);
        try {
            ObjectOutputStream out = new ObjectOutputStream(stream);
            out.writeObject(obj);
            out.flush();
        } finally {
            stream.close();
        }
    }

    /**
     * 从指定文件装载对象.
     */
    @Expando
    public static Object load(File file) throws IOException, ClassNotFoundException {
        InputStream stream = new FileInputStream(file);
        try {
            ObjectInputStream in = new ObjectInputStream(stream);
            return in.readObject();
        } finally {
            stream.close();
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
