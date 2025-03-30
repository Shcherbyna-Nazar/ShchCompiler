package shch

class ShchInterpreter : ShchBaseVisitor<Any?>() {

    private val variables = mutableMapOf<String, Any>()

    override fun visitProgram(ctx: ShchParser.ProgramContext): Any? {
        for (stmt in ctx.statement()) {
            visit(stmt)
        }
        return null
    }

    override fun visitVarDecl(ctx: ShchParser.VarDeclContext): Any? {
        val name = ctx.ID().text
        val type = ctx.type().text
        val value = ctx.expr()?.let { visit(it) }

        val castedValue = when (type) {
            "Int" -> (value as? Number)?.toInt() ?: 0
            "Float" -> (value as? Number)?.toDouble() ?: 0.0
            else -> error("Nieznany typ: $type")
        }

        variables[name] = castedValue
        return null
    }

    override fun visitAssignStmt(ctx: ShchParser.AssignStmtContext): Any? {
        val name = ctx.ID().text
        val value = visit(ctx.expr())
        if (!variables.containsKey(name)) error("Zmienna '$name' nie została zadeklarowana")
        variables[name] = value!!
        return null
    }

    override fun visitPrintStmt(ctx: ShchParser.PrintStmtContext): Any? {
        val value = visit(ctx.expr())
        println(value)
        return null
    }

    override fun visitReadStmt(ctx: ShchParser.ReadStmtContext): Any? {
        val name = ctx.ID().text
        print("Wprowadź wartość dla $name: ")
        val input = readLine() ?: ""
        val old = variables[name]
        val value = when (old) {
            is Int -> input.toIntOrNull() ?: 0
            is Double -> input.toDoubleOrNull() ?: 0.0
            else -> error("Zmienna '$name' nie została zadeklarowana lub ma nieznany typ")
        }
        variables[name] = value
        return null
    }

    override fun visitExpr(ctx: ShchParser.ExprContext): Any? {
        return when {
            ctx.NUMBER() != null -> {
                val text = ctx.NUMBER().text
                if (text.contains(".")) text.toDouble() else text.toInt()
            }
            ctx.ID() != null -> {
                if (!variables.containsKey(ctx.ID().text)) {
                    error("Błąd semantyczny: zmienna '${ctx.ID().text}' nie została zadeklarowana.")
                }
                variables[ctx.ID().text]
            }
            ctx.op != null -> {
                val left = visit(ctx.expr(0))!!
                val right = visit(ctx.expr(1))!!
                when (ctx.op.text) {
                    "+", "-", "*", "/" -> {
                        if (left is Number && right is Number) {
                            when (ctx.op.text) {
                                "+" -> if (left is Double || right is Double) left.toDouble() + right.toDouble() else left.toInt() + right.toInt()
                                "-" -> if (left is Double || right is Double) left.toDouble() - right.toDouble() else left.toInt() - right.toInt()
                                "*" -> if (left is Double || right is Double) left.toDouble() * right.toDouble() else left.toInt() * right.toInt()
                                "/" -> if (left is Double || right is Double) left.toDouble() / right.toDouble() else left.toInt() / right.toInt()
                                else -> error("Nieznany operator ${ctx.op.text}")
                            }
                        } else error("Błąd semantyczny: operacja arytmetyczna na nie-liczbach.")
                    }
                    "==", "!=", "<", ">", "<=", ">=" -> {
                        if (left is Number && right is Number) {
                            val l = left.toDouble()
                            val r = right.toDouble()
                            when (ctx.op.text) {
                                "==" -> l == r
                                "!=" -> l != r
                                "<" -> l < r
                                ">" -> l > r
                                "<=" -> l <= r
                                ">=" -> l >= r
                                else -> error("Nieznany operator porównania ${ctx.op.text}")
                            }
                        } else error("Błąd semantyczny: porównanie nie-liczbowe.")
                    }
                    else -> error("Nieznany operator ${ctx.op.text}")
                }
            }
            else -> visit(ctx.expr(0)) // nawiasy
        }
    }


    override fun visitIfStmt(ctx: ShchParser.IfStmtContext): Any? {
        val condition = visit(ctx.expr())
        if (condition is Boolean && condition) {
            visit(ctx.block())
        } else if (condition is Number && condition.toInt() != 0) {
            visit(ctx.block())
        }
        return null
    }

    override fun visitWhileStmt(ctx: ShchParser.WhileStmtContext): Any? {
        while (true) {
            val condition = visit(ctx.expr())
            val shouldContinue = when (condition) {
                is Boolean -> condition
                is Number -> condition.toInt() != 0
                else -> false
            }
            if (!shouldContinue) break
            visit(ctx.block())
        }
        return null
    }

}
