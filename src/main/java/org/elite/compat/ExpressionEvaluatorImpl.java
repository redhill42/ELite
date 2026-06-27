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

import javax.servlet.jsp.el.ExpressionEvaluator;
import javax.servlet.jsp.el.Expression;
import javax.servlet.jsp.el.FunctionMapper;
import javax.servlet.jsp.el.VariableResolver;
import javax.servlet.jsp.el.ELException;

import javax.el.ExpressionFactory;
import java.beans.FeatureDescriptor;
import java.lang.reflect.Method;
import java.util.Iterator;
import org.elite.eval.ELContextImpl;

/**
 * This class implements JSP 2.0 EL API, which is deprecated. Provide it
 * for backward compatibility.
 */
@SuppressWarnings("deprecation")
public class ExpressionEvaluatorImpl extends ExpressionEvaluator
{
    private ExpressionFactory factory;

    public ExpressionEvaluatorImpl(ExpressionFactory factory) {
        this.factory = factory;
    }

    public Expression parseExpression(String expression,
                                      Class expectedType,
                                      FunctionMapper fnMapper)
        throws ELException
    {
        ELContextImpl context = new ELContextImpl(new ELResolverWrapper(null));
        if (fnMapper != null) {
            context.setFunctionMapper(new FunctionMapperWrapper(fnMapper));
        }

        try {
            javax.el.ValueExpression expr = factory.createValueExpression(context, expression, expectedType);
            return new ExpressionWrapper(expr);
        } catch (javax.el.ELException ex) {
            ELException ex2 = new ELException(ex.getMessage());
            ex2.setStackTrace(ex.getStackTrace());
            throw ex2;
        }
    }

    public Object evaluate(String expression,
                           Class expectedType,
                           VariableResolver varResolver,
                           FunctionMapper fnMapper)
        throws ELException
    {
        ELContextImpl context;
        if (varResolver instanceof VariableResolverImpl) {
            context = (ELContextImpl)((VariableResolverImpl)varResolver).getELContext();
        } else {
            context = new ELContextImpl(new ELResolverWrapper(varResolver));
        }

        javax.el.FunctionMapper oldMapper = context.getFunctionMapper();
        if (fnMapper != null) {
            context.setFunctionMapper(new FunctionMapperWrapper(fnMapper));
        } else {
            context.setFunctionMapper(null);
        }

        try {
            javax.el.ValueExpression expr = factory.createValueExpression(context, expression, expectedType);
            return expr.getValue(context);
        } catch (javax.el.ELException ex) {
            ELException ex2 = new ELException(ex.getMessage());
            ex2.setStackTrace(ex.getStackTrace());
            throw ex2;
        } finally {
            context.setFunctionMapper(oldMapper);
        }
    }

    private static class ExpressionWrapper extends Expression {
        private javax.el.ValueExpression expr;

        public ExpressionWrapper(javax.el.ValueExpression expr) {
            this.expr = expr;
        }

        public Object evaluate(VariableResolver varResolver)
            throws ELException
        {
            ELContextImpl context;
            if (varResolver instanceof VariableResolverImpl) {
                context = (ELContextImpl)((VariableResolverImpl)varResolver).getELContext();
            } else {
                context = new ELContextImpl(new ELResolverWrapper(varResolver));
            }

            try {
                return expr.getValue(context);
            } catch (javax.el.ELException ex) {
                ELException ex2 = new ELException(ex.getMessage());
                ex2.setStackTrace(ex.getStackTrace());
                throw ex2;
            }
        }
    }

    private static class ELResolverWrapper extends javax.el.ELResolver {
        private VariableResolver resolver;

        ELResolverWrapper(VariableResolver resolver) {
            this.resolver = resolver;
        }

        public Object getValue(javax.el.ELContext context, Object base, Object property) {
            if (resolver != null && base == null) {
                try {
                    context.setPropertyResolved(true);
                    return resolver.resolveVariable((String)property);
                } catch (javax.servlet.jsp.el.ELException ex) {
                    javax.el.ELException ex2 = new javax.el.ELException(ex.getMessage());
                    ex2.setStackTrace(ex.getStackTrace());
                    throw ex2;
                }
            } else {
                return null;
            }
        }

        public Class<?> getType(javax.el.ELContext context, Object base, Object property) {
            throw new UnsupportedOperationException();
        }
        public void setValue(javax.el.ELContext context, Object base, Object property, Object value) {
            throw new UnsupportedOperationException();
        }
        public boolean isReadOnly(javax.el.ELContext context, Object base, Object property) {
            throw new UnsupportedOperationException();
        }
        public Iterator<FeatureDescriptor> getFeatureDescriptors(javax.el.ELContext context, Object base) {
            throw new UnsupportedOperationException();
        }
        public Class<?> getCommonPropertyType(javax.el.ELContext context, Object base) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FunctionMapperWrapper extends javax.el.FunctionMapper {
        private FunctionMapper mapper;

        public FunctionMapperWrapper(javax.servlet.jsp.el.FunctionMapper mapper) {
            this.mapper = mapper;
        }

        public Method resolveFunction(String prefix, String localName) {
            return mapper.resolveFunction(prefix, localName);
        }
    }
}
