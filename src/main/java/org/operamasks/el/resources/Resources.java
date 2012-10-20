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

package org.operamasks.el.resources;

import java.util.ResourceBundle;
import java.util.Locale;
import java.text.MessageFormat;

import org.operamasks.el.eval.ELUtils;

/**
 * Utility class for i18n.
 */
public final class Resources
{
    private static final String RESOURCE_BUNDLE_NAME = "org.operamasks.el.resources.Messages";

    /**
     * Get a string from the underlying resource bundle.
     *
     * @param key the resource key
     * @return a localized and formatted string.
     */
    public static String getText(String key) {
        Locale locale = ELUtils.getCurrentLocale();
        ResourceBundle bundle = ResourceBundle.getBundle(RESOURCE_BUNDLE_NAME, locale);
        return bundle.getString(key);
    }

    /**
     * Get a string from the underlying resource bundle and format
     * it with the given set of arguments.
     *
     * @param key the resource key
     * @param args arguments used to format string
     * @return a localized and formatted string.
     */
    public static String getText(String key, Object... args) {
        String format = getText(key);
        return MessageFormat.format(format, args);
    }

    /**
     * Short convenient method.
     */
    public static String _T(String key) { return getText(key); }
    public static String _T(String key, Object... args) { return getText(key, args); }


    // Message keys

    public static final String ELITE_WELCOME = "ELITE_WELCOME";
    public static final String ELITE_USAGE = "ELITE_USAGE";

    public static final String EL_ILLEGAL_CHARACTER = "EL_ILLEGAL_CHARACTER";
    public static final String EL_ILLEGAL_ESCAPE_CHAR = "EL_ILLEGAL_ESCAPE_CHAR";
    public static final String EL_INTEGER_LITERAL_OVERFLOW = "EL_INTEGER_LITERAL_OVERFLOW";
    public static final String EL_FLOATING_LITERAL_FORMAT_ERROR = "EL_FLOATING_LITERAL_FORMAT_ERROR";
    public static final String EL_FLOATING_LITERAL_OVERFLOW = "EL_FLOATING_LITERAL_OVERFLOW";
    public static final String EL_FLOATING_LITERAL_UNDERFLOW = "EL_FLOATING_LITERAL_UNDERFLOW";
    public static final String EL_UNTERMINATED_STRING = "EL_UNTERMINATED_STRING";
    public static final String EL_IDENTIFIER_EXPECTED = "EL_IDENTIFIER_EXPECTED";
    public static final String EL_EXTRA_CHAR_IN_INPUT = "EL_EXTRA_CHAR_IN_INPUT";
    public static final String EL_TOKEN_EXPECTED = "EL_TOKEN_EXPECTED";
    public static final String EL_MIXED_SYNTAX = "EL_MIXED_SYNTAX";
    public static final String EL_MISSING_TERM = "EL_MISSING_TERM";
    public static final String EL_DUPLICATE_VAR_NAME = "EL_DUPLICATE_VAR_NAME";
    public static final String EL_DUPLICATE_ARG_NAME = "EL_DUPLICATE_ARG_NAME";
    public static final String EL_UNKNOWN_ARG_NAME = "EL_UNKNOWN_ARG_NAME";
    public static final String EL_MISSING_ARG_VALUE = "EL_MISSING_ARG_VALUE";
    public static final String EL_NON_DFLT_ARG_FOLLOWS_DFLT_ARG = "EL_NON_DFLT_ARG_FOLLOWS_DFLT_ARG";
    public static final String EL_FN_NO_SUCH_METHOD = "EL_FN_NO_SUCH_METHOD";
    public static final String EL_FN_METHOD_NOT_FOUND = "EL_FN_METHOD_NOT_FOUND";
    public static final String EL_FN_METHOD_NOT_PUBLIC_STATIC = "EL_FN_METHOD_NOT_PUBLIC_STATIC";
    public static final String EL_FN_BAD_ARG_COUNT = "EL_FN_BAD_ARG_COUNT";
    public static final String EL_FN_INVOKE_ERROR = "EL_FN_INVOKE_ERROR";
    public static final String EL_NOT_METHOD_EXPRESSION = "EL_NOT_METHOD_EXPRESSION";
    public static final String EL_INVALID_METHOD_EXPRESSION = "EL_INVALID_METHOD_EXPRESSION";
    public static final String EL_PROPERTY_NOT_FOUND = "EL_PROPERTY_NOT_FOUND";
    public static final String EL_METHOD_NOT_FOUND = "EL_METHOD_NOT_FOUND";
    public static final String EL_UNDEFINED_IDENTIFIER = "EL_UNDEFINED_IDENTIFIER";
    public static final String EL_VARIABLE_NOT_WRITABLE = "EL_VARIABLE_NOT_WRITABLE";
    public static final String EL_PROPERTY_NOT_READABLE = "EL_PROPERTY_NOT_READABLE";
    public static final String EL_PROPERTY_NOT_WRITABLE = "EL_PROPERTY_NOT_WRITABLE";
    public static final String EL_PROPERTY_READ_ERROR = "EL_PROPERTY_READ_ERROR";
    public static final String EL_PROPERTY_WRITE_ERROR = "EL_PROPERTY_WRITE_ERROR";
    public static final String EL_RESOLVER_NOT_WRITABLE = "EL_RESOLVER_NOT_WRITABLE";
    public static final String EL_READONLY_EXPRESSION = "EL_READONLY_EXPRESSION";
    public static final String EL_TUPLE_PATTERN_NOT_MATCH = "EL_TUPLE_PATTERN_NOT_MATCH";
    public static final String EL_CIRCULAR_CLASS_DEFINITION = "EL_CIRCULAR_CLASS_DEFINITION";
    public static final String EL_ABSTRACT_CLASS = "EL_ABSTRACT_CLASS";
    public static final String EL_SUBCLASS_FINAL = "EL_SUBCLASS_FINAL";
    public static final String EL_BASE_CLASS_INITIALIZED = "EL_BASE_CLASS_INITIALIZED";
    public static final String EL_REPEATED_MODIFIER = "EL_REPEATED_MODIFIER";
    public static final String EL_INVALID_MODIFIER = "EL_INVALID_MODIFIER";
    public static final String EL_INVALID_MODIFIER_COMBINATION = "EL_INVALID_MODIFIER_COMBINATION";
    public static final String EL_INVALID_METHOD_BODY = "EL_INVALID_METHOD_BODY";
    public static final String EL_NO_METHOD_BODY = "EL_NO_METHOD_BODY";
    public static final String EL_INVOKE_ABSTRACT_METHOD = "EL_INVOKE_ABSTRACT_METHOD";
    public static final String EL_EMPTY_DO_CONSTRUCT = "EL_EMPTY_DO_CONSTRUCT";
    public static final String EL_LAST_DO_STATEMENT = "EL_LAST_DO_STATEMENT";
    public static final String EL_RETURN_CONTINUATION = "EL_RETURN_CONTINUATION";

    public static final String JSPRT_COERCE_ERROR = "JSPRT_COERCE_ERROR";
    public static final String JSPRT_UNSUPPORTED_EVAL_TYPE = "JSPRT_UNSUPPORTED_EVAL_TYPE";

    public static final String XML_BAD_TAG_FORMAT = "XML_BAD_TAG_FORMAT";
    public static final String XML_NO_START_TAG = "XML_NO_START_TAG";
    public static final String XML_CLOSE_TAG_NOT_MATCH = "XML_CLOSE_TAG_NOT_MATCH";
    public static final String XML_NO_GT_IN_CLOSE_TAG = "XML_NO_GT_IN_CLOSE_TAG";
    public static final String XML_NO_GT_AFTER_SLASH = "XML_NO_GT_AFTER_SLASH";
    public static final String XML_NO_EQ_IN_NAME_VALUE_PAIR = "XML_NO_EQ_IN_NAME_VALUE_PAIR";
    public static final String XML_ILLEGAL_NAME_CHAR = "XML_ILLEGAL_NAME_CHAR";
    public static final String XML_DUPLICATE_ATTRIBUTE = "XML_DUPLICATE_ATTRIBUTE";
    public static final String XML_UNQUOTED_VALUE = "XML_UNQUOTED_VALUE";
    public static final String XML_UNEXPECTED_EOI = "XML_UNEXPECTED_EOI";

    private Resources() {}
}
