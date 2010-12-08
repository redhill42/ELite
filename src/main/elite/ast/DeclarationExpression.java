/*
 * $Id: DeclarationExpression.java,v 1.2 2009/05/13 05:39:00 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.ast;

import org.operamasks.el.parser.ELNode;
import elite.lang.annotation.Data;

@Data({"name", "expression"})
public class DeclarationExpression extends Expression
{
    protected String name;
    protected Expression expression;

    protected DeclarationExpression(String name, Expression expression) {
        super(ExpressionType.DECLARATION);
        this.name = name;
        this.expression = expression;
    }

    public String getName() {
        return name;
    }

    public Expression getExpression() {
        return expression;
    }

    protected ELNode toInternal(int pos) {
        return new ELNode.DEFINE(pos, name, null, null, expression.getNode(pos), true);
    }

    public String toString() {
        return "define " + name + "=" + expression;
    }
}
