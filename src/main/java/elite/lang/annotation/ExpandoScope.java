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
