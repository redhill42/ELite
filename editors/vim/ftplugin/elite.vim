" Vim ftplugin for ELite (.xel)
" Language settings and indentation

setlocal commentstring=//\ %s
setlocal comments=://,s1:/*,mb:*,ex:*/,:///,://
setlocal iskeyword+=?,!,$,%,^

" Indentation: 4 spaces
setlocal expandtab
setlocal shiftwidth=4
setlocal softtabstop=4
setlocal tabstop=4

" Folding: fold by syntax (class/function/block bodies)
setlocal foldmethod=syntax
setlocal foldlevel=99
