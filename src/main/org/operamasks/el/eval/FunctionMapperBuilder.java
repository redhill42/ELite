/*
 * $Id: FunctionMapperBuilder.java,v 1.2 2009/03/22 08:37:56 danielyuan Exp $
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

import java.lang.reflect.Method;
import javax.el.FunctionMapper;

class FunctionMapperBuilder extends FunctionMapper
{
    private FunctionMapper source;
    private FunctionMapperImpl target;

    FunctionMapperBuilder(FunctionMapper source) {
        this.source = source;
    }

    public Method resolveFunction(String prefix, String localName) {
        Method m = source.resolveFunction(prefix, localName);
        if (m != null) {
            if (target == null)
                target = new FunctionMapperImpl();
            target.addFunction(prefix, localName, m);
        }
        return m;
    }

    public FunctionMapper build() {
        return target;
    }
}
