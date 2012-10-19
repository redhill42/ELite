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

import javax.servlet.jsp.el.ExpressionEvaluator;
import javax.servlet.jsp.el.Expression;
import javax.servlet.jsp.el.FunctionMapper;
import javax.servlet.jsp.el.VariableResolver;
import javax.servlet.jsp.el.ELException;

import javax.el.ExpressionFactory;
import java.beans.FeatureDescriptor;
import java.lang.reflect.Method;
import java.util.Iterator;
import org.operamasks.el.eval.ELContextImpl;

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
