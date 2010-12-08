/*
 * $Id: ExpandoScope.java,v 1.1 2009/05/07 16:47:09 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.lang.annotation;

/**
 * 指定Expando函数的作用域范围.
 */
public enum ExpandoScope
{
    /**
     * Expando函数作为指定类的成员函数使用, 该函数不被引入到全局名字空间
     * 并且不能通过静态方法查找到该函数.
     */
    EXPANDO,

    /**
     * Expando函数作为全局函数使用, 可通过静态方法查找到该函数.
     */
    GLOBAL,

    /**
     * Expando函数作为运算符使用, 该函数不被引入到全局名字空间, 但可以
     * 通过静态方法查找到该函数.
     */
    OPERATOR
}
