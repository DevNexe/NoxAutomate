package com.noxautomate.dsl

class Lexer(private val source: String) {
    private val tokens = mutableListOf<Token>()
    private val indents = mutableListOf(0)

    fun tokenize(): List<Token> {
        source.split('\n').forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.removeSuffix("\r")
            var offset = 0
            var width = 0
            while (offset < line.length && (line[offset] == ' ' || line[offset] == '\t')) {
                width += if (line[offset] == '\t') 4 - width % 4 else 1
                offset++
            }
            val content = line.substring(offset)
            if (content.isBlank() || content.trimStart().startsWith("#")) return@forEachIndexed
            val indentation = line.take(offset)
            if (indentation.contains('\t') && indentation.contains(' ')) {
                throw DslException("Нельзя смешивать табуляцию и пробелы в отступе", SourcePosition(lineNumber, 1))
            }
            if (!indentation.contains('\t') && width % 4 != 0) {
                throw DslException("Отступ должен быть кратен четырём пробелам", SourcePosition(lineNumber, 1))
            }

            val current = indents.last()
            when {
                width > current -> {
                    indents.add(width)
                    tokens.add(Token(TokenType.INDENT, "", SourcePosition(lineNumber, 1)))
                }
                width < current -> {
                    while (indents.size > 1 && width < indents.last()) {
                        indents.removeAt(indents.lastIndex)
                        tokens.add(Token(TokenType.DEDENT, "", SourcePosition(lineNumber, 1)))
                    }
                    if (indents.last() != width) {
                        throw DslException("Отступ не совпадает с предыдущим уровнем", SourcePosition(lineNumber, 1))
                    }
                }
            }
            scanLine(content, lineNumber, offset)
            tokens.add(Token(TokenType.NEWLINE, "\\n", SourcePosition(lineNumber, line.length + 1)))
        }
        while (indents.size > 1) {
            indents.removeAt(indents.lastIndex)
            tokens.add(Token(TokenType.DEDENT, "", SourcePosition(source.count { it == '\n' } + 1, 1)))
        }
        tokens.add(Token(TokenType.EOF, "", SourcePosition(source.count { it == '\n' } + 1, 1)))
        return tokens
    }

    private fun scanLine(line: String, lineNumber: Int, baseColumn: Int) {
        var i = 0
        fun add(type: TokenType, start: Int, literal: Any? = null) {
            tokens.add(Token(type, line.substring(start, i), SourcePosition(lineNumber, baseColumn + start + 1), literal))
        }
        while (i < line.length) {
            val start = i
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> return
                c.isDigit() -> {
                    while (i < line.length && line[i].isDigit()) i++
                    var type = TokenType.INTEGER
                    if (i + 1 < line.length && line[i] == '.' && line[i + 1].isDigit()) {
                        type = TokenType.FLOAT
                        i++
                        while (i < line.length && line[i].isDigit()) i++
                    }
                    val text = line.substring(start, i)
                    tokens.add(Token(type, text, SourcePosition(lineNumber, baseColumn + start + 1),
                        if (type == TokenType.INTEGER) text.toLongOrNull() ?: throw DslException("Целое число вне диапазона", SourcePosition(lineNumber, baseColumn + start + 1))
                        else text.toDouble()))
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    val value = StringBuilder()
                    while (i < line.length && line[i] != quote) {
                        if (line[i] == '\\') {
                            i++
                            if (i >= line.length) break
                            value.append(when (line[i++]) {
                                'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '\\' -> '\\'
                                '"' -> '"'; '\'' -> '\''; else -> throw DslException("Неизвестная escape-последовательность", SourcePosition(lineNumber, baseColumn + i))
                            })
                        } else value.append(line[i++])
                    }
                    if (i >= line.length) throw DslException("Незакрытая строка", SourcePosition(lineNumber, baseColumn + start + 1))
                    i++
                    tokens.add(Token(TokenType.STRING, line.substring(start, i), SourcePosition(lineNumber, baseColumn + start + 1), value.toString()))
                }
                c.isLetter() || c == '_' -> {
                    i++
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                    val text = line.substring(start, i)
                    val type = when (text) {
                        "true" -> TokenType.TRUE; "false" -> TokenType.FALSE; "null" -> TokenType.NULL
                        "if" -> TokenType.IF; "elif" -> TokenType.ELIF; "else" -> TokenType.ELSE
                        "while" -> TokenType.WHILE; "for" -> TokenType.FOR; "in" -> TokenType.IN
                        "on" -> TokenType.ON; "break" -> TokenType.BREAK; "continue" -> TokenType.CONTINUE
                        "and" -> TokenType.AND; "or" -> TokenType.OR; "not" -> TokenType.NOT
                        else -> TokenType.IDENTIFIER
                    }
                    tokens.add(Token(type, text, SourcePosition(lineNumber, baseColumn + start + 1)))
                }
                else -> {
                    i++
                    val pair = if (i < line.length) "$c${line[i]}" else ""
                    val type = when (pair) {
                        "==" -> { i++; TokenType.EQUAL_EQUAL }; "!=" -> { i++; TokenType.BANG_EQUAL }
                        "<=" -> { i++; TokenType.LESS_EQUAL }; ">=" -> { i++; TokenType.GREATER_EQUAL }
                        else -> when (c) {
                            '(' -> TokenType.LEFT_PAREN; ')' -> TokenType.RIGHT_PAREN
                            '[' -> TokenType.LEFT_BRACKET; ']' -> TokenType.RIGHT_BRACKET
                            '{' -> TokenType.LEFT_BRACE; '}' -> TokenType.RIGHT_BRACE
                            ',' -> TokenType.COMMA; ':' -> TokenType.COLON; '.' -> TokenType.DOT
                            '+' -> TokenType.PLUS; '-' -> TokenType.MINUS; '*' -> TokenType.STAR
                            '/' -> TokenType.SLASH; '%' -> TokenType.PERCENT; '=' -> TokenType.EQUAL
                            '<' -> TokenType.LESS; '>' -> TokenType.GREATER
                            else -> throw DslException("Неожиданный символ '$c'", SourcePosition(lineNumber, baseColumn + start + 1))
                        }
                    }
                    add(type, start)
                }
            }
        }
    }
}
