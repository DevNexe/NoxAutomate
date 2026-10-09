package com.noxautomate.dsl

sealed interface DslValue {
    data class Number(val value: Double, val integral: Boolean) : DslValue {
        override fun toString(): String = if (integral) value.toLong().toString() else value.toString()
    }

    data class Text(val value: String) : DslValue { override fun toString() = value }
    data class Bool(val value: Boolean) : DslValue { override fun toString() = value.toString() }
    data class Sequence(val value: MutableList<DslValue>) : DslValue { override fun toString() = value.toString() }
    data class Mapping(val value: MutableMap<DslValue, DslValue>) : DslValue { override fun toString() = value.toString() }
    data object Null : DslValue { override fun toString() = "null" }
}

interface DslHost {
    fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue
}

class Scope(private val parent: Scope? = null) {
    private val values = mutableMapOf<String, DslValue>()
    fun define(name: String, value: DslValue) { values[name] = value }
    fun assign(name: String, value: DslValue) {
        if (name in values) values[name] = value
        else if (parent != null) parent.assign(name, value)
        else values[name] = value
    }
    fun get(name: String): DslValue {
        if (name in values) return values.getValue(name)
        return parent?.get(name) ?: throw DslException("Неизвестная переменная '$name'")
    }
}

class Interpreter(
    private val host: DslHost,
    private val maxLoopIterations: Int = 100_000,
    private val maxExecutionSteps: Int = 1_000_000
) {
    private val globals = Scope()
    private var executionSteps = 0
    private var loopDepth = 0

    fun execute(program: Program) {
        executionSteps = 0
        loopDepth = 0
        program.statements.filterNot { it is EventStmt }.forEach { executeStmt(it, globals) }
    }

    fun executeEvent(program: Program, event: String, parameters: Map<String, DslValue> = emptyMap()) {
        executionSteps = 0
        loopDepth = 0
        for (handler in program.statements.filterIsInstance<EventStmt>().filter { it.event == event }) {
            val scope = Scope(globals)
            var matches = true
            if (event == "battery.changed") {
                val direction = handler.filters["direction"]?.let { evaluate(it, globals) } as? DslValue.Text
                if (direction != null && direction.value != "below" && direction.value != "above") {
                    throw DslException("Направление battery.changed должно быть 'below' или 'above'")
                }
            }
            handler.filters.forEach { (name, expression) ->
                val expected = evaluate(expression, globals)
                val actual = if (event == "battery.changed" && name == "threshold") parameters["level"] else parameters[name]
                val matched = if (event == "battery.changed" && name == "threshold") {
                    val direction = (handler.filters["direction"]?.let { evaluate(it, globals) } as? DslValue.Text)?.value
                    val level = (actual as? DslValue.Number)?.value
                    val threshold = (expected as? DslValue.Number)?.value
                    if (level == null || threshold == null) false
                    else when (direction) {
                        "below" -> level < threshold
                        "above" -> level > threshold
                        else -> level == threshold
                    }
                } else if (event == "battery.changed" && name == "direction") {
                    (expected as? DslValue.Text)?.value?.let { it == "below" || it == "above" } == true
                } else actual == expected
                if (!matched) matches = false
            }
            if (!matches) continue
            parameters.forEach(scope::define)
            executeStmt(handler.body, scope)
        }
    }

    private fun executeStmt(stmt: Stmt, scope: Scope) {
        if (++executionSteps > maxExecutionSteps) throw DslException("Превышен лимит операций скрипта")
        when (stmt) {
            is Block -> stmt.statements.forEach { executeStmt(it, scope) }
            is AssignStmt -> scope.assign(stmt.name, evaluate(stmt.value, scope))
            is IndexAssignStmt -> {
                val receiver = evaluate(stmt.target.receiver, scope)
                val index = evaluate(stmt.target.index, scope)
                val value = evaluate(stmt.value, scope)
                when (receiver) {
                    is DslValue.Sequence -> receiver.value[sequenceIndex(index, receiver.value.size)] = value
                    is DslValue.Mapping -> receiver.value[index] = value
                    else -> throw DslException("Можно изменять только элементы списка или словаря")
                }
            }
            is ExprStmt -> evaluate(stmt.expression, scope)
            is IfStmt -> {
                val branch = stmt.branches.firstOrNull { truthy(evaluate(it.first, scope)) }
                if (branch != null) executeStmt(branch.second, scope)
                else stmt.otherwise?.let { executeStmt(it, scope) }
            }
            is WhileStmt -> {
                var count = 0
                while (truthy(evaluate(stmt.condition, scope))) {
                    if (++count > maxLoopIterations) throw DslException("Превышен лимит итераций цикла")
                    loopDepth++
                    try {
                        executeStmt(stmt.body, scope)
                    } catch (_: ContinueSignal) {
                        continue
                    } catch (_: BreakSignal) {
                        break
                    } finally {
                        loopDepth--
                    }
                }
            }
            is ForStmt -> {
                val items = iterable(evaluate(stmt.iterable, scope))
                if (items.size > maxLoopIterations) throw DslException("Превышен лимит итераций цикла")
                for (item in items) {
                    scope.assign(stmt.name, item)
                    loopDepth++
                    try {
                        executeStmt(stmt.body, scope)
                    } catch (_: ContinueSignal) {
                        continue
                    } catch (_: BreakSignal) {
                        break
                    } finally {
                        loopDepth--
                    }
                }
            }
            BreakStmt -> if (loopDepth == 0) throw DslException("'break' допустим только внутри цикла") else throw BreakSignal
            ContinueStmt -> if (loopDepth == 0) throw DslException("'continue' допустим только внутри цикла") else throw ContinueSignal
            is EventStmt -> Unit
        }
    }

    private fun evaluate(expr: Expr, scope: Scope): DslValue = when (expr) {
        is LiteralExpr -> fromAny(expr.value)
        is VariableExpr -> scope.get(expr.name)
        is MemberExpr -> DslValue.Text(path(expr))
        is IndexExpr -> index(evaluate(expr.receiver, scope), evaluate(expr.index, scope))
        is ListExpr -> DslValue.Sequence(expr.elements.map { evaluate(it, scope) }.toMutableList())
        is DictExpr -> DslValue.Mapping(expr.entries.associateTo(mutableMapOf()) { evaluate(it.first, scope) to evaluate(it.second, scope) })
        is UnaryExpr -> {
            val value = evaluate(expr.operand, scope)
            when (expr.operator) {
                TokenType.NOT -> DslValue.Bool(!truthy(value))
                TokenType.MINUS -> number(-number(value).value)
                else -> throw DslException("Недопустимый унарный оператор")
            }
        }
        is BinaryExpr -> binary(expr, scope)
        is CallExpr -> {
            val function = path(expr.callee)
            val positional = expr.arguments.filter { it.name == null }.map { evaluate(it.value, scope) }
            val named = expr.arguments.filter { it.name != null }.associate { it.name!! to evaluate(it.value, scope) }
            host.invoke(function, positional, named)
        }
    }

    private fun binary(expr: BinaryExpr, scope: Scope): DslValue {
        val left = evaluate(expr.left, scope)
        if (expr.operator == TokenType.AND && !truthy(left)) return DslValue.Bool(false)
        if (expr.operator == TokenType.OR && truthy(left)) return DslValue.Bool(true)
        val right = evaluate(expr.right, scope)
        return when (expr.operator) {
            TokenType.AND -> DslValue.Bool(truthy(right))
            TokenType.OR -> DslValue.Bool(truthy(right))
            TokenType.PLUS -> if (left is DslValue.Text || right is DslValue.Text) DslValue.Text(left.toString() + right.toString())
                else number(number(left).value + number(right).value)
            TokenType.MINUS -> number(number(left).value - number(right).value)
            TokenType.STAR -> number(number(left).value * number(right).value)
            TokenType.SLASH -> {
                val divisor = number(right).value
                if (divisor == 0.0) throw DslException("Деление на ноль")
                number(number(left).value / divisor)
            }
            TokenType.PERCENT -> {
                val divisor = number(right).value
                if (divisor == 0.0) throw DslException("Деление на ноль")
                number(number(left).value % divisor)
            }
            TokenType.EQUAL_EQUAL -> DslValue.Bool(equalValues(left, right))
            TokenType.BANG_EQUAL -> DslValue.Bool(!equalValues(left, right))
            TokenType.LESS -> DslValue.Bool(compare(left, right) < 0)
            TokenType.LESS_EQUAL -> DslValue.Bool(compare(left, right) <= 0)
            TokenType.GREATER -> DslValue.Bool(compare(left, right) > 0)
            TokenType.GREATER_EQUAL -> DslValue.Bool(compare(left, right) >= 0)
            TokenType.IN -> DslValue.Bool(contains(right, left))
            else -> throw DslException("Неизвестный оператор")
        }
    }

    private fun compare(a: DslValue, b: DslValue): Int =
        if (a is DslValue.Number && b is DslValue.Number) a.value.compareTo(b.value)
        else if (a is DslValue.Text && b is DslValue.Text) a.value.compareTo(b.value)
        else throw DslException("Несовместимые типы для сравнения")

    private fun index(receiver: DslValue, key: DslValue): DslValue = when (receiver) {
        is DslValue.Sequence -> receiver.value[sequenceIndex(key, receiver.value.size)]
        is DslValue.Text -> {
            val chars = receiver.value
            DslValue.Text(chars[sequenceIndex(key, chars.length)].toString())
        }
        is DslValue.Mapping -> if (key in receiver.value) receiver.value.getValue(key)
            else throw DslException("Ключ отсутствует в словаре")
        else -> throw DslException("Индексировать можно только список, строку или словарь")
    }

    private fun sequenceIndex(key: DslValue, size: Int): Int {
        val raw = (key as? DslValue.Number)?.value
            ?: throw DslException("Индекс должен быть целым числом")
        if (raw % 1.0 != 0.0) throw DslException("Индекс должен быть целым числом")
        val normalized = raw.toInt().let { if (it < 0) size + it else it }
        if (normalized !in 0 until size) throw DslException("Индекс вне диапазона")
        return normalized
    }

    private fun contains(container: DslValue, value: DslValue): Boolean = when (container) {
        is DslValue.Sequence -> value in container.value
        is DslValue.Text -> value is DslValue.Text && value.value in container.value
        is DslValue.Mapping -> value in container.value
        else -> throw DslException("Оператор 'in' требует список, словарь или строку")
    }

    private fun equalValues(left: DslValue, right: DslValue): Boolean =
        if (left is DslValue.Number && right is DslValue.Number) left.value == right.value else left == right

    private fun iterable(value: DslValue): List<DslValue> = when (value) {
        is DslValue.Sequence -> value.value
        is DslValue.Text -> value.value.map { DslValue.Text(it.toString()) }
        is DslValue.Mapping -> value.value.keys.toList()
        else -> throw DslException("Значение не является перебираемым")
    }

    private fun truthy(value: DslValue): Boolean = when (value) {
        DslValue.Null -> false
        is DslValue.Bool -> value.value
        is DslValue.Number -> value.value != 0.0
        is DslValue.Text -> value.value.isNotEmpty()
        is DslValue.Sequence -> value.value.isNotEmpty()
        is DslValue.Mapping -> value.value.isNotEmpty()
    }

    private fun number(value: DslValue): DslValue.Number =
        value as? DslValue.Number ?: throw DslException("Ожидалось число")

    private fun number(value: Double): DslValue.Number = DslValue.Number(value, value % 1.0 == 0.0)

    private fun fromAny(value: Any?): DslValue = when (value) {
        null -> DslValue.Null
        is Boolean -> DslValue.Bool(value)
        is Number -> DslValue.Number(value.toDouble(), value is Byte || value is Short || value is Int || value is Long)
        is String -> DslValue.Text(value)
        else -> throw DslException("Неподдерживаемое значение")
    }

    private fun path(expr: Expr): String = when (expr) {
        is VariableExpr -> expr.name
        is MemberExpr -> "${path(expr.receiver)}.${expr.name}"
        else -> throw DslException("Можно вызывать только встроенные функции")
    }

    private data object BreakSignal : RuntimeException()
    private data object ContinueSignal : RuntimeException()
}
