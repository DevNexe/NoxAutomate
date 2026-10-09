package com.noxautomate.dsl

class Parser(private val tokens: List<Token>) {
    private var current = 0

    fun parse(): Program {
        val statements = mutableListOf<Stmt>()
        skipNewlines()
        while (!check(TokenType.EOF)) {
            statements.add(statement())
            skipNewlines()
        }
        return Program(statements)
    }

    private fun statement(): Stmt = when {
        match(TokenType.IF) -> ifStatement(previous())
        match(TokenType.WHILE) -> whileStatement(previous())
        match(TokenType.FOR) -> forStatement(previous())
        match(TokenType.PARALLEL) -> parallelStatement(previous())
        match(TokenType.ON) -> eventStatement(previous())
        match(TokenType.BREAK) -> { endStatement(); BreakStmt }
        match(TokenType.CONTINUE) -> { endStatement(); ContinueStmt }
        check(TokenType.IDENTIFIER) && checkNext(TokenType.EQUAL) -> {
            val name = advance().lexeme
            advance()
            val value = expression()
            endStatement()
            AssignStmt(name, value)
        }
        check(TokenType.IDENTIFIER) && looksLikeIndexAssignment() -> {
            val target = expression()
            consume(TokenType.EQUAL, "Ожидалось '=' в присваивании элемента")
            val value = expression()
            endStatement()
            val indexed = target as? IndexExpr ?: throw DslException("Ожидался индексируемый элемент", peek().position)
            IndexAssignStmt(indexed, value)
        }
        else -> {
            val expr = expression()
            endStatement()
            ExprStmt(expr)
        }
    }

    private fun ifStatement(token: Token): Stmt {
        val branches = mutableListOf<Pair<Expr, Block>>()
        val condition = expression()
        branches.add(condition to block(token))
        while (match(TokenType.ELIF)) {
            val elif = previous()
            branches.add(expression() to block(elif))
        }
        val otherwise = if (match(TokenType.ELSE)) block(previous()) else null
        return IfStmt(branches, otherwise)
    }

    private fun whileStatement(token: Token): Stmt = WhileStmt(expression(), block(token))

    private fun forStatement(token: Token): Stmt {
        val name = consume(TokenType.IDENTIFIER, "Ожидалось имя переменной цикла").lexeme
        consume(TokenType.IN, "Ожидалось 'in'")
        val iterable = expression()
        return ForStmt(name, iterable, block(token))
    }

    private fun parallelStatement(token: Token): Stmt {
        consume(TokenType.COLON, "Ожидалось ':' после parallel")
        consume(TokenType.NEWLINE, "Ожидался перевод строки после 'parallel:'")
        consume(TokenType.INDENT, "Ожидались параллельные ветки с отступом")
        val branches = mutableListOf<Block>()
        skipNewlines()
        while (!check(TokenType.DEDENT) && !check(TokenType.EOF)) {
            val branch = consume(TokenType.BRANCH, "Внутри parallel ожидается 'branch:'")
            branches += block(branch)
            skipNewlines()
        }
        if (branches.size < 2) {
            throw DslException("В parallel требуется минимум две ветки branch:", token.position)
        }
        consume(TokenType.DEDENT, "Ожидался конец parallel")
        return ParallelStmt(branches)
    }

    private fun eventStatement(token: Token): Stmt {
        val path = mutableListOf(consume(TokenType.IDENTIFIER, "Ожидалось имя события").lexeme)
        while (match(TokenType.DOT)) path.add(consume(TokenType.IDENTIFIER, "Ожидалось имя после '.'").lexeme)
        consume(TokenType.LEFT_PAREN, "Ожидалось '(' после имени события")
        val args = arguments(TokenType.RIGHT_PAREN)
        val filters = linkedMapOf<String, Expr>()
        args.forEach {
            val key = it.name ?: throw DslException("Параметры события должны быть именованными", token.position)
            filters[key] = it.value
        }
        consume(TokenType.RIGHT_PAREN, "Ожидалось ')'")
        return EventStmt(path.joinToString("."), filters, block(token))
    }

    private fun block(owner: Token): Block {
        consume(TokenType.COLON, "Ожидалось ':'")
        consume(TokenType.NEWLINE, "Ожидался перевод строки после ':'")
        consume(TokenType.INDENT, "Ожидался блок с отступом")
        val body = mutableListOf<Stmt>()
        skipNewlines()
        while (!check(TokenType.DEDENT) && !check(TokenType.EOF)) {
            val nested = statement()
            if (nested is EventStmt) throw DslException("Обработчики событий должны находиться на верхнем уровне", owner.position)
            body.add(nested)
            skipNewlines()
        }
        if (body.isEmpty()) throw DslException("Пустой блок", owner.position)
        consume(TokenType.DEDENT, "Ожидался конец блока")
        return Block(body)
    }

    private fun arguments(end: TokenType): List<CallArgument> {
        val args = mutableListOf<CallArgument>()
        if (check(end)) return args
        do {
            val name = if (check(TokenType.IDENTIFIER) && checkNext(TokenType.EQUAL)) {
                val key = advance().lexeme
                advance()
                key
            } else null
            args.add(CallArgument(name, expression()))
        } while (match(TokenType.COMMA) && !check(end))
        return args
    }

    private fun expression(minPrecedence: Int = 0): Expr {
        var left = prefix()
        while (true) {
            if (match(TokenType.DOT)) {
                left = MemberExpr(left, consume(TokenType.IDENTIFIER, "Ожидалось имя свойства").lexeme)
                continue
            }
            if (match(TokenType.LEFT_PAREN)) {
                val args = arguments(TokenType.RIGHT_PAREN)
                consume(TokenType.RIGHT_PAREN, "Ожидалось ')'")
                left = CallExpr(left, args)
                continue
            }
            if (match(TokenType.LEFT_BRACKET)) {
                val index = expression()
                consume(TokenType.RIGHT_BRACKET, "Ожидалось ']'")
                left = IndexExpr(left, index)
                continue
            }
            val op = peek().type
            val precedence = precedence(op)
            if (precedence < minPrecedence) break
            advance()
            left = BinaryExpr(left, op, expression(precedence + 1))
        }
        return left
    }

    private fun prefix(): Expr {
        val token = advance()
        return when (token.type) {
            TokenType.INTEGER, TokenType.FLOAT, TokenType.STRING -> LiteralExpr(token.literal)
            TokenType.TRUE -> LiteralExpr(true)
            TokenType.FALSE -> LiteralExpr(false)
            TokenType.NULL -> LiteralExpr(null)
            TokenType.IDENTIFIER -> VariableExpr(token.lexeme)
            TokenType.MINUS, TokenType.NOT -> UnaryExpr(token.type, expression(7))
            TokenType.LEFT_PAREN -> expression().also { consume(TokenType.RIGHT_PAREN, "Ожидалось ')'") }
            TokenType.LEFT_BRACKET -> {
                val items = mutableListOf<Expr>()
                if (!check(TokenType.RIGHT_BRACKET)) do { items.add(expression()) } while (match(TokenType.COMMA) && !check(TokenType.RIGHT_BRACKET))
                consume(TokenType.RIGHT_BRACKET, "Ожидалось ']'")
                ListExpr(items)
            }
            TokenType.LEFT_BRACE -> {
                val entries = mutableListOf<Pair<Expr, Expr>>()
                if (!check(TokenType.RIGHT_BRACE)) do {
                    val key = expression()
                    consume(TokenType.COLON, "Ожидалось ':' между ключом и значением")
                    entries.add(key to expression())
                } while (match(TokenType.COMMA) && !check(TokenType.RIGHT_BRACE))
                consume(TokenType.RIGHT_BRACE, "Ожидалось '}'")
                DictExpr(entries)
            }
            else -> throw DslException("Ожидалось выражение", token.position)
        }
    }

    private fun precedence(type: TokenType): Int = when (type) {
        TokenType.OR -> 1; TokenType.AND -> 2
        TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL -> 3
        TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL, TokenType.IN -> 4
        TokenType.PLUS, TokenType.MINUS -> 5
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 6
        else -> -1
    }

    private fun endStatement() {
        if (!match(TokenType.NEWLINE) && !check(TokenType.DEDENT) && !check(TokenType.EOF)) {
            throw DslException("Ожидался конец строки", peek().position)
        }
    }
    private fun skipNewlines() { while (match(TokenType.NEWLINE)) Unit }
    private fun consume(type: TokenType, message: String): Token =
        if (check(type)) advance() else throw DslException(message, peek().position)
    private fun match(type: TokenType): Boolean { if (!check(type)) return false; advance(); return true }
    private fun check(type: TokenType): Boolean = peek().type == type
    private fun checkNext(type: TokenType): Boolean = current + 1 < tokens.size && tokens[current + 1].type == type
    private fun looksLikeIndexAssignment(): Boolean {
        var depth = 0
        var index = current + 1
        while (index < tokens.size) {
            when (tokens[index].type) {
                TokenType.LEFT_BRACKET -> depth++
                TokenType.RIGHT_BRACKET -> {
                    depth--
                    if (depth == 0) return tokens.getOrNull(index + 1)?.type == TokenType.EQUAL
                }
                TokenType.NEWLINE, TokenType.EOF -> return false
                else -> Unit
            }
            index++
        }
        return false
    }
    private fun advance(): Token { if (!check(TokenType.EOF)) current++; return previous() }
    private fun peek(): Token = tokens[current]
    private fun previous(): Token = tokens[current - 1]
}
