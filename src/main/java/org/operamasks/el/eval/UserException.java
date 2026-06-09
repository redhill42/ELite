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

import javax.el.ELContext;

public class UserException extends EvaluationException
{
    public UserException(ELContext elctx) {
        super(elctx, (String)null);
    }

    public UserException(ELContext elctx, String message) {
        super(elctx, message);
    }

    public UserException(ELContext elctx, Throwable cause) {
        super(elctx, cause);
    }

    public UserException(ELContext elctx, String message, Throwable cause) {
        super(elctx, message, cause);
    }
}
