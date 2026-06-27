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

package org.elite.compat;

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
