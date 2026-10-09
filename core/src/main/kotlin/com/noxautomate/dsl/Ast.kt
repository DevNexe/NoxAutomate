package com.noxautomate.dsl

sealed interface AstNode
sealed interface Stmt : AstNode
sealed interface Expr : AstNode

data class Program(val statements: List<Stmt>) : AstNode
data class Block(val statements: List<Stmt>) : Stmt
data class AssignStmt(val name: String, val value: Expr) : Stmt
data class IndexAssignStmt(val target: IndexExpr, val value: Expr) : Stmt
data class ExprStmt(val expression: Expr) : Stmt
data class IfStmt(val branches: List<Pair<Expr, Block>>, val otherwise: Block?) : Stmt
data class WhileStmt(val condition: Expr, val body: Block) : Stmt
data class ForStmt(val name: String, val iterable: Expr, val body: Block) : Stmt
data object BreakStmt : Stmt
data object ContinueStmt : Stmt
data class EventStmt(val event: String, val filters: Map<String, Expr>, val body: Block) : Stmt
data class LiteralExpr(val value: Any?) : Expr
data class VariableExpr(val name: String) : Expr
data class MemberExpr(val receiver: Expr, val name: String) : Expr
data class IndexExpr(val receiver: Expr, val index: Expr) : Expr
data class CallArgument(val name: String?, val value: Expr)
data class CallExpr(val callee: Expr, val arguments: List<CallArgument>) : Expr
data class ListExpr(val elements: List<Expr>) : Expr
data class DictExpr(val entries: List<Pair<Expr, Expr>>) : Expr
data class UnaryExpr(val operator: TokenType, val operand: Expr) : Expr
data class BinaryExpr(val left: Expr, val operator: TokenType, val right: Expr) : Expr
