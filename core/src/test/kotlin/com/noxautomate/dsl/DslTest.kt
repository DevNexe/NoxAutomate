package com.noxautomate.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DslTest {
    @Test
    fun lexerEmitsNestedIndentAndDedentTokens() {
        val tokens = Lexer("if true:\n    if false:\n        x = 1\n    x = 2\ny = 3").tokenize()
        assertEquals(2, tokens.count { it.type == TokenType.INDENT })
        assertEquals(2, tokens.count { it.type == TokenType.DEDENT })
    }

    @Test
    fun interpreterRunsBranchesAndLoops() {
        val program = Parser(Lexer("""
            total = 0
            for item in [1, 2, 3, 4]:
                if item == 3:
                    continue
                total = total + item
            system.record(value=total)
        """.trimIndent()).tokenize()).parse()
        var recorded: DslValue? = null
        val host = object : DslHost {
            override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue {
                assertEquals("system.record", function)
                recorded = named.getValue("value")
                return DslValue.Null
            }
        }
        Interpreter(host).execute(program)
        assertEquals(DslValue.Number(7.0, true), recorded)
    }

    @Test
    fun rejectsInconsistentIndentation() {
        assertFailsWith<DslException> { Lexer("if true:\n    x = 1\n  y = 2").tokenize() }
    }

    @Test
    fun parsesNamedEventHandler() {
        val program = Parser(Lexer("on wifi.connected(ssid=\"Home\"):\n    system.toast(message=ssid)").tokenize()).parse()
        assertEquals("wifi.connected", (program.statements.single() as EventStmt).event)
    }

    @Test
    fun batteryEventUsesThresholdAndDirection() {
        val program = Parser(Lexer("""
            on battery.changed(threshold=20, direction="below"):
                system.record(level=level)
        """.trimIndent()).tokenize()).parse()
        val observed = mutableListOf<DslValue>()
        val interpreter = Interpreter(object : DslHost {
            override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue {
                observed.add(named.getValue("level"))
                return DslValue.Null
            }
        })
        interpreter.executeEvent(program, "battery.changed", mapOf("level" to DslValue.Number(15.0, true)))
        interpreter.executeEvent(program, "battery.changed", mapOf("level" to DslValue.Number(25.0, true)))
        assertEquals<List<DslValue>>(listOf(DslValue.Number(15.0, true)), observed)
    }

    @Test
    fun nestedLoopsRespectGlobalExecutionBudget() {
        val program = Parser(Lexer("""
            while true:
                while true:
                    continue
        """.trimIndent()).tokenize()).parse()
        val error = assertFailsWith<DslException> {
            Interpreter(object : DslHost {
                override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>) = DslValue.Null
            }, maxExecutionSteps = 40).execute(program)
        }
        assertEquals("Превышен лимит операций скрипта", error.message)
    }

    @Test
    fun supportsListAndDictionaryIndexingAndBlockScope() {
        val program = Parser(Lexer("""
            values = [1, 2]
            data = {"name": "Nox"}
            if true:
                values[1] = 7
                result = values[1] + 1
            data["value"] = result
            system.record(value=data["value"])
        """.trimIndent()).tokenize()).parse()
        var recorded: DslValue? = null
        Interpreter(object : DslHost {
            override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue {
                recorded = named.getValue("value")
                return DslValue.Null
            }
        }).execute(program)
        assertEquals(DslValue.Number(8.0, true), recorded)
    }

    @Test
    fun comparisonAfterIndexExpressionIsNotParsedAsAssignment() {
        val program = Parser(Lexer("""
            values = [2]
            if values[0] == 2:
                system.record(value=true)
        """.trimIndent()).tokenize()).parse()
        assertEquals(2, program.statements.size)
    }

    @Test
    fun nullVariablesRemainDefinedAndBreakOutsideLoopIsAnError() {
        val nullProgram = Parser(Lexer("""
            value = null
            if value == null:
                system.record(value=true)
        """.trimIndent()).tokenize()).parse()
        var recorded: DslValue? = null
        Interpreter(object : DslHost {
            override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue {
                recorded = named.getValue("value")
                return DslValue.Null
            }
        }).execute(nullProgram)
        assertEquals(DslValue.Bool(true), recorded)

        val breakProgram = Parser(Lexer("break").tokenize()).parse()
        assertFailsWith<DslException> {
            Interpreter(object : DslHost {
                override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>) = DslValue.Null
            }).execute(breakProgram)
        }
    }

    @Test
    fun dispatchesDeviceNetworkTimerAndAppEvents() {
        val program = Parser(Lexer("""
            on power.connected():
                system.record(value="charging")
            on screen.off():
                system.record(value="screen")
            on network.connected(transport="wifi"):
                system.record(value="network")
            on time.every(minutes=5):
                system.record(value="timer")
            on app.foreground(package_name="com.example"):
                system.record(value="app")
        """.trimIndent()).tokenize()).parse()
        val events = mutableListOf<String>()
        val interpreter = Interpreter(object : DslHost {
            override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue {
                events += (named.getValue("value") as DslValue.Text).value
                return DslValue.Null
            }
        })
        interpreter.executeEvent(program, "power.connected")
        interpreter.executeEvent(program, "screen.off")
        interpreter.executeEvent(program, "network.connected", mapOf("transport" to DslValue.Text("wifi")))
        interpreter.executeEvent(program, "time.every", mapOf("minutes" to DslValue.Number(5.0, true)))
        interpreter.executeEvent(program, "app.foreground", mapOf("package_name" to DslValue.Text("com.example")))
        assertEquals(listOf("charging", "screen", "network", "timer", "app"), events)
    }

    @Test
    fun smsEventExposesSenderAndBodyToConditions() {
        val program = Parser(Lexer("""
            on sms.received():
                if sender == "+79990000000" and "НАЙДИ" in message:
                    system.record(value=body)
        """.trimIndent()).tokenize()).parse()
        var recorded: DslValue? = null
        val interpreter = Interpreter(object : DslHost {
            override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue {
                recorded = named.getValue("value")
                return DslValue.Null
            }
        })

        interpreter.executeEvent(
            program,
            "sms.received",
            mapOf(
                "sender" to DslValue.Text("+79990000000"),
                "message" to DslValue.Text("НАЙДИ ТЕЛЕФОН"),
                "body" to DslValue.Text("НАЙДИ ТЕЛЕФОН")
            )
        )

        assertEquals(DslValue.Text("НАЙДИ ТЕЛЕФОН"), recorded)
    }
}
