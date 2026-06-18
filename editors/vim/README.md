# Vim Syntax Highlighting for ELite (.xel)

## Installation

### Manual (recommended)

```bash
mkdir -p ~/.vim
cp -r editors/vim/* ~/.vim/
```

### Pathogen

```bash
cd ~/.vim/bundle
ln -s /path/to/ELite/editors/vim elite
```

### Vim-Plug

Add to your `~/.vimrc`:

```vim
Plug '/path/to/ELite/editors/vim', { 'rtp': 'editors/vim' }
```

Or if cloned to a standard plugin location:

```vim
Plug 'hongun/ELite', { 'rtp': 'editors/vim' }
```

### Lazy.nvim (Neovim)

```lua
{
  'hongun/ELite',
  rtp = 'editors/vim',
  ft = 'elite',
}
```

## What's Highlighted

- **Keywords**: `define`, `class`, `if`, `else`, `for`, `while`, `match`, `case`, `try`, `catch`, `import`, `require`, etc.
- **Operators**: `->`, `::`, `=>`, `==`, `!=`, `~`, `??`, bitwise (`:|:`, `:&:`, `:^:`, `:!:`), etc.
- **Literals**: strings (`""`, `"""`), characters (`'a'`), numbers, hex (`0xFF`), floats, BigInteger (`42b`), Rational (`1/3r`)
- **String interpolation**: `${expr}` inside strings
- **Type annotations**: `::Integer`, `::List<String>`
- **Meta-programming**: `@data`, `@infix`, `@prefix`, `@test`
- **Lambda expressions**: `\x => x + 1`
- **Grammar blocks**: `grammar { ... }`
- **Comments**: `//`, `/* */`, `/** */`
- **Regular expression literals**: `/pattern/`

## File Detection

Files with the `.xel` extension are automatically detected and highlighted.

## Customization

To change the indentation width, add to your `~/.vimrc`:

```vim
autocmd FileType elite setlocal shiftwidth=2 softtabstop=2 tabstop=2
```
