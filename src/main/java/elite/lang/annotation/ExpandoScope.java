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
