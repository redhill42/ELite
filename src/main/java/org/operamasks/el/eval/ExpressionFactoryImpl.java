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
