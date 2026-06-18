" Vim syntax file
" Language:     ELite (.xel)
" Maintainer:   Daniel Yuan
" URL:          https://github.com/hongun/ELite
" Last Change:  2026-06-09
" License:      Apache License 2.0

if exists("b:current_syntax")
  finish
endif

" ---- Keywords ----

syn keyword eliteKeyword    abstract break continue do else extends false
syn keyword eliteKeyword    for grammar if import in instanceof
syn keyword eliteKeyword    let match new null private protected public
syn keyword eliteKeyword    require return static switch this throw
syn keyword eliteKeyword    true try type void while yield

syn keyword eliteDefine     define undef class
syn keyword eliteConditional if else
syn keyword eliteRepeat     for while
syn keyword eliteBranch     case default
syn keyword eliteException  try catch finally throw
syn keyword eliteModule     import require module
syn keyword eliteClass      class extends implements abstract static
syn keyword eliteVisibility private protected public

" ---- Boolean & null literals ----

syn keyword eliteBoolean    true false
syn keyword eliteNull       null void

" ---- Type annotations ----

syn match   eliteType       "\w\@<!::[ \t]*\K\k*"
syn keyword elitePrimType   Integer Long Double Float Boolean String Char Number Object Void

" ---- Meta-programming annotations ----

syn match   eliteAnnotation "@\(data\|infix\|prefix\|test\)\>"
syn match   eliteAnnotationOp "@infix(\d\+)"

" ---- Operators ----

" Pipe / message passing
syn match   eliteOperator   "->"
" Type annotation
syn match   eliteOperator   "::"
" Lambda arrow
syn match   eliteOperator   "=>"
" Elvis / coalesce / safe ref
syn match   eliteOperator   "??"
" Bitwise
syn match   eliteOperator   ":|:\|:&:\|:^:\|:!:"
" Comparison
syn match   eliteOperator   "===\|!==\|==\|!=\|<=\|>=\|<\|>"
" Assignment
syn match   eliteOperator   "+=\|-=\|\*=\|/=\|%="
syn match   eliteOperator   "\~="
syn match   eliteOperator   ":="
" Arithmetic
syn match   eliteOperator   "+\|-\|\*\|/\|%\|div\>"
" Power
syn match   eliteOperator   "\^"
" Shift
syn match   eliteOperator   "<<<\|<<\|>>\|>>>"
" String concat
syn match   eliteOperator   "\~"
" Logical
syn match   eliteOperator   "&&\|||\|!"
syn match   eliteOperator   "\<and\>\|\<or\>\|\<not\>"
" Membership
syn keyword eliteOperator   in
syn match   eliteOperator   "\<not\s\+in\>"
" Type check
syn match   eliteOperator   "\<is\>\|\<instanceof\>"
" Access
syn match   eliteOperator   "\."
" Pair / list
syn match   eliteOperator   "::"

" ---- Lambda ----

syn match   eliteLambda     "\\\ze[ \t]*=>"
syn match   eliteLambda     "\\\ze[ \t]*[a-zA-Z_$][a-zA-Z0-9_$]*[ \t]*=>"
syn match   eliteLambda     "\\\ze[ \t]*\([a-zA-Z_$][a-zA-Z0-9_$]*[ \t]*,[ \t]*\)*[a-zA-Z_$][a-zA-Z0-9_$]*[ \t]*=>"

" ---- Strings ----

" Double-quoted string
syn region  eliteString     start='"' end='"' skip='\\"'
    \ contains=eliteInterpolation

" Heredoc / triple-quoted
syn region  eliteString     start='"""' end='"""'
    \ contains=eliteInterpolation

" String interpolation ${...}
syn region  eliteInterpolation matchgroup=eliteInterpDelim
    \ start='\${' end='}' contained
    \ contains=ALLBUT,eliteInterpolation,eliteString

" Escape sequences
syn match   eliteEscape     '\\[ntr\\"]' contained containedin=eliteString
syn match   eliteEscape     '\\u\x\{4}'  contained containedin=eliteString

" ---- Character literals ----

syn match   eliteChar       "'[^']'" contains=eliteCharEscape
syn match   eliteChar       "'\\[ntr\\']'" contains=eliteCharEscape
syn match   eliteChar       "'\\u\x\{4}'" contains=eliteCharEscape
syn match   eliteCharEscape '\\[ntr\\u]' contained

" ---- Numbers ----

" Hex
syn match   eliteNumber     "\<0[xX]\x\+\>"
syn match   eliteNumber     "\<0[xX]\x\+\(_\x\+\)*\>"
" Decimal with separators
syn match   eliteNumber     "\<\d\+\(_\d\+\)*\>"
" Float
syn match   eliteFloat      "\<\d\+\.\d\+\([eE][+-]\=\d\+\)\?\>"
syn match   eliteFloat      "\<\d\+[eE][+-]\=\d\+\>"
" BigInteger suffix
syn match   eliteNumber     "\<\d\+\(_\d\+\)*[bB]\>"
" Rational suffix
syn match   eliteNumber     "\<\d\+\(_\d\+\)*[rR]\>"

" ---- Regular expression literals ----

syn match   eliteRegexp     '/[^/*].\{-}/' contains=eliteRegexpEscape
syn match   eliteRegexpEscape '\\/' contained

" ---- Symbol literals ----

syn match   eliteSymbol     '#[a-zA-Z_$][a-zA-Z0-9_$]*'

" ---- Comments ----

syn match   eliteComment    "//.*$"
    \ contains=eliteTodo
syn region  eliteComment    start="/\*" end="\*/"
    \ contains=eliteTodo,eliteDocComment
syn region  eliteDocComment start="/\*\*" end="\*/" keepend
    \ contains=eliteTodo

syn keyword eliteTodo       TODO FIXME XXX NOTE contained

" ---- Delimiters ----

syn match   eliteDelimiter  "[{}()\[\]]"
syn match   eliteComma      ","
syn match   eliteSemicolon  ";"
syn match   eliteColon      ":"

" ---- Grammar blocks ----

syn region  eliteGrammar    start="\<grammar\>" end="}"
    \ contains=eliteGrammarKeyword,eliteGrammarString,eliteGrammarCapture,eliteGrammarArrow

syn keyword eliteGrammarKeyword goal contained
syn match   eliteGrammarString   "'[^']*'" contained
syn match   eliteGrammarCapture  "#\k\+" contained
syn match   eliteGrammarArrow    "->" contained

" ---- Identifiers (must be last to not override keywords) ----

syn match   eliteIdentifier "\<[a-zA-Z_$][a-zA-Z0-9_$]*\>"

" ---- Highlight groups ----

hi def link eliteKeyword      Keyword
hi def link eliteDefine       Keyword
hi def link eliteConditional  Conditional
hi def link eliteRepeat       Repeat
hi def link eliteBranch       Conditional
hi def link eliteException    Exception
hi def link eliteModule       Include
hi def link eliteClass        Structure
hi def link eliteVisibility   StorageClass
hi def link eliteBoolean      Boolean
hi def link eliteNull         Constant
hi def link eliteType         Type
hi def link elitePrimType     Type
hi def link eliteAnnotation   PreProc
hi def link eliteAnnotationOp PreProc
hi def link eliteOperator     Operator
hi def link eliteLambda       Special
hi def link eliteString       String
hi def link eliteInterpDelim  Special
hi def link eliteEscape       SpecialChar
hi def link eliteChar         Character
hi def link eliteCharEscape   SpecialChar
hi def link eliteNumber       Number
hi def link eliteFloat        Float
hi def link eliteRegexp       String
hi def link eliteRegexpEscape SpecialChar
hi def link eliteSymbol       Constant
hi def link eliteComment      Comment
hi def link eliteDocComment   Comment
hi def link eliteTodo         Todo
hi def link eliteDelimiter    Delimiter
hi def link eliteComma        Delimiter
hi def link eliteSemicolon    Delimiter
hi def link eliteColon        Delimiter
hi def link eliteGrammar      PreProc
hi def link eliteGrammarKeyword Statement
hi def link eliteGrammarString String
hi def link eliteGrammarCapture Identifier
hi def link eliteGrammarArrow Operator
hi def link eliteIdentifier   Identifier

let b:current_syntax = "elite"
