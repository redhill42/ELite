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

package org.operamasks.el.types;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Function type: (paramTypes) -> returnType.
 */
public class FunctionType extends Type {
    public final List<Type> paramTypes;
    public final Type returnType;

    public FunctionType(List<Type> paramTypes, Type returnType) {
        this.paramTypes = paramTypes;
        this.returnType = returnType;
    }

    public FunctionType(Type returnType, Type... paramTypes) {
        this.paramTypes = Arrays.asList(paramTypes);
        this.returnType = returnType;
    }

    @Override
    public boolean isSubtypeOf(Type other) {
        if (other == DYNAMIC || other == TOP) return true;
        if (other instanceof FunctionType) {
            FunctionType ft = (FunctionType) other;
            if (this.paramTypes.size() != ft.paramTypes.size()) return false;
            // Contravariant in parameters, covariant in return
            for (int i = 0; i < paramTypes.size(); i++) {
                if (!ft.paramTypes.get(i).isSubtypeOf(this.paramTypes.get(i)))
                    return false;
            }
            return this.returnType.isSubtypeOf(ft.returnType);
        }
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof FunctionType) {
            FunctionType ft = (FunctionType) obj;
            return paramTypes.equals(ft.paramTypes) && returnType.equals(ft.returnType);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return paramTypes.hashCode() * 31 + returnType.hashCode();
    }

    @Override
    public Type subst(Map<VarType, Type> subst) {
        List<Type> newParams = paramTypes.stream()
            .map(t -> t.subst(subst))
            .collect(Collectors.toList());
        Type newRet = returnType.subst(subst);
        if (newParams.equals(paramTypes) && newRet == returnType) return this;
        return new FunctionType(newParams, newRet);
    }

    @Override
    public String toTypeString() {
        String params = paramTypes.stream()
            .map(Type::toTypeString)
            .collect(Collectors.joining(", "));
        return "(" + params + ") -> " + returnType.toTypeString();
    }
}
