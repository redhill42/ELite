/*
 * $Id: VarClosure.java,v 1.3 2009/05/12 10:24:41 danielyuan Exp $
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

package org.operamasks.el.eval.closure;

import org.operamasks.el.parser.ELNode;
import org.operamasks.el.eval.EvaluationContext;

public class VarClosure extends DelayEvalClosure
{
    private String id;

    public VarClosure(EvaluationContext ctx, ELNode.IDENT node) {
        super(ctx, node);
        this.id = node.id;
    }

    public String id() {
        return id;
    }
}
