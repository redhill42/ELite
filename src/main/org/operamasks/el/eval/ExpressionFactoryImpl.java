/*
 * $Id: ExpressionFactoryImpl.java,v 1.3 2009/05/04 08:35:55 jackyzhang Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
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

package org.operamasks.el.eval;

import javax.el.ExpressionFactory;
import javax.el.ValueExpression;
import javax.el.ELContext;
import javax.el.MethodExpression;
import javax.el.ELException;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;

import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;

public class ExpressionFactoryImpl extends ExpressionFactory
{
    /**
     * @deprecated replaced by {@link ELEngine#getExpressionFactory()}
     */
    @Deprecated
    public static ExpressionFactoryImpl getInstance() {
        return ELEngine.factory;
    }

    ExpressionFactoryImpl() {}

    public ValueExpression createValueExpression(ELContext context,
                                                 String expression,
                                                 Class<?> expectedType)
    {
        ELNode node = Parser.parse(expression);

        FunctionMapper fm = context.getFunctionMapper();
        if (fm != null) {
            FunctionMapperBuilder fmb = new FunctionMapperBuilder(fm);
            node.applyFunctionMapper(fmb);
            fm = fmb.build();
        }

        VariableMapper vm = context.getVariableMapper();
        if (vm != null) {
            VariableMapperBuilder vmb = new VariableMapperBuilder(vm);
            node.applyVariableMapper(vmb);
            vm = vmb.build();
        }

        return new ValueExpressionImpl(expression, node, expectedType, fm, vm);
    }

    /**
     * @deprecated replaced by {@link ELEngine#evaluateExpression(javax.el.ELContext, String, Class)}
     */
    @Deprecated
    public Object evaluateExpression(ELContext context, String expression, Class<?> expectedType) {
        return ELEngine.evaluateExpression(context, expression, expectedType);
    }

    public ValueExpression createValueExpression(Object value, Class<?> expectedType) {
        return new LiteralValueExpression(value, expectedType);
    }

    public MethodExpression createMethodExpression(ELContext  context,
                                                   String     expression,
                                                   Class<?>   expectedType,
                                                   Class<?>[] expectedParamTypes)
    {
        if (expectedParamTypes == null) {
            expectedParamTypes = new Class<?>[0];
        }

        ELNode node = Parser.parse(expression);

        if (node instanceof ELNode.LITERAL) {
            if (expectedParamTypes.length != 0) { // FIXME
                throw new ELException("The literal method expression cannot have parameters");
            }
            return new LiteralMethodExpression(expression, expectedType, expectedParamTypes);
        }

        FunctionMapper fm = context.getFunctionMapper();
        if (fm != null) {
            FunctionMapperBuilder fmb = new FunctionMapperBuilder(fm);
            node.applyFunctionMapper(fmb);
            fm = fmb.build();
        }

        VariableMapper vm = context.getVariableMapper();
        if (vm != null) {
            VariableMapperBuilder vmb = new VariableMapperBuilder(vm);
            node.applyVariableMapper(vmb);
            vm = vmb.build();
        }

        return new MethodExpressionImpl(expression, node, expectedType, expectedParamTypes, fm, vm);
    }

    public Object coerceToType(Object obj, Class<?> targetType) {
        return TypeCoercion.coerce(obj, targetType);
    }
}
