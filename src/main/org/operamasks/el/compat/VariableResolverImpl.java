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

package org.operamasks.el.compat;

import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.el.VariableResolver;
import javax.el.ELContext;
import javax.el.VariableMapper;
import javax.el.ValueExpression;

/**
 * This class implements JSP 2.0 EL API, which is deprecated. Provide it
 * for backward compatibility.
 */
public class VariableResolverImpl implements VariableResolver
{
    private PageContext pageContext;

    public VariableResolverImpl(PageContext pageContext) {
        this.pageContext = pageContext;
    }

    public Object resolveVariable(String name) {
        ELContext context = pageContext.getELContext();
        VariableMapper varMapper = context.getVariableMapper();

        if (varMapper != null) {
            ValueExpression expr = varMapper.resolveVariable(name);
            if (expr != null) return expr.getValue(context);
        }
        return context.getELResolver().getValue(context, null, name);
    }

    public ELContext getELContext() {
        return pageContext.getELContext();
    }
}
