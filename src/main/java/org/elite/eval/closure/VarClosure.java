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

package org.elite.eval.closure;

import org.elite.parser.ELNode;
import org.elite.eval.EvaluationContext;

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
