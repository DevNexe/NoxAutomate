package com.noxautomate.dsl

data class SourcePosition(val line: Int, val column: Int)

data class Token(
    val type: TokenType,
    val lexeme: String,
    val position: SourcePosition,
    val literal: Any? = null
)

enum class TokenType {
    IDENTIFIER, INTEGER, FLOAT, STRING,
    TRUE, FALSE, NULL, IF, ELIF, ELSE, WHILE, FOR, IN, ON, BREAK, CONTINUE,
    LEFT_PAREN, RIGHT_PAREN, LEFT_BRACKET, RIGHT_BRACKET, LEFT_BRACE, RIGHT_BRACE,
    COMMA, COLON, DOT, PLUS, MINUS, STAR, SLASH, PERCENT,
    EQUAL, EQUAL_EQUAL, BANG_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
    AND, OR, NOT, NEWLINE, INDENT, DEDENT, EOF
}

class DslException(message: String, val position: SourcePosition? = null) :
    RuntimeException(if (position == null) message else "$message (строка ${position.line}, столбец ${position.column})")
