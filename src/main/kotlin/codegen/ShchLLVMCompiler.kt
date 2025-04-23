package shch.codegen

import codegen.LLVMUtils.boolToInt
import codegen.LLVMUtils.buildGlobalStringPtr
import codegen.LLVMUtils.createEntryBlockAlloca
import codegen.LLVMUtils.getLLVMType
import codegen.LLVMUtils.isFloat
import codegen.LLVMUtils.promoteToFloat
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import shch.ShchParser

class ShchLLVMCompiler {

    private val context: LLVMContextRef = LLVMContextCreate()
    private val module: LLVMModuleRef = LLVMModuleCreateWithNameInContext("shch_module", context)
    private val builder: LLVMBuilderRef = LLVMCreateBuilderInContext(context)
    private lateinit var mainFunc: LLVMValueRef
    private var compilationFailed = false

    data class VariableInfo(val ptr: LLVMValueRef, val type: LLVMTypeRef)
    private val namedValues = mutableMapOf<String, VariableInfo>()

    fun compile(tree: ShchParser.ProgramContext) {
        val mainType = LLVMFunctionType(LLVMInt32TypeInContext(context), null as PointerPointer<LLVMTypeRef>?, 0, 0)
        mainFunc = LLVMAddFunction(module, "main", mainType)

        val entry = LLVMAppendBasicBlockInContext(context, mainFunc, "entry")
        LLVMPositionBuilderAtEnd(builder, entry)

        try {
            for (stmt in tree.statement()) {
                val currentBB = LLVMGetInsertBlock(builder)
                val terminator = LLVMGetBasicBlockTerminator(currentBB)
                if (terminator != null && !terminator.isNull) {
                    break // don't emit more code into a terminated block
                }
                compileStatement(stmt)
            }


            // 🛠 Проверяем, нужен ли терминатор
            val currentBB = LLVMGetInsertBlock(builder)
            val terminator = LLVMGetBasicBlockTerminator(currentBB)
            if (terminator == null || terminator.isNull) {
                LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0))
            }

        } catch (e: Exception) {
            println("❌ Compilation error: ${e.message}")
            compilationFailed = true
            LLVMDeleteFunction(mainFunc)
        }
    }


    private fun compileStatement(stmt: ShchParser.StatementContext) {
        when {
            stmt.varDecl() != null -> compileVarDecl(stmt.varDecl())
            stmt.assignStmt() != null -> compileAssign(stmt.assignStmt())
            stmt.printStmt() != null -> compilePrint(stmt.printStmt())
            stmt.printlnStmt() != null -> compilePrintln(stmt.printlnStmt())
            stmt.readStmt() != null -> compileRead(stmt.readStmt())
            stmt.ifStmt() != null -> compileIf(stmt.ifStmt())
            stmt.whileStmt() != null -> compileWhile(stmt.whileStmt())
            else -> error("Unsupported statement: ${stmt.text}")
        }
    }

    private fun compileWhile(whileStmt: ShchParser.WhileStmtContext) {
        val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))

        val condBB = LLVMAppendBasicBlockInContext(context, function, "while.cond")
        val bodyBB = LLVMAppendBasicBlockInContext(context, function, "while.body")
        val afterBB = LLVMAppendBasicBlockInContext(context, function, "while.end")

        LLVMBuildBr(builder, condBB)

        // Условие
        LLVMPositionBuilderAtEnd(builder, condBB)
        val condValue = compileCondExpr(whileStmt.expr())
        LLVMBuildCondBr(builder, condValue, bodyBB, afterBB)

        // Тело цикла
        LLVMPositionBuilderAtEnd(builder, bodyBB)
        val bodyHasTerminator = compileBlock(whileStmt.block())
        if (!bodyHasTerminator) LLVMBuildBr(builder, condBB)

        // После цикла
        LLVMPositionBuilderAtEnd(builder, afterBB)
    }


    private fun compileVarDecl(decl: ShchParser.VarDeclContext) {
        val name = decl.ID().text
        val llvmType = getLLVMType(context, decl.type().text)

        val alloca = createEntryBlockAlloca(builder, mainFunc, name, llvmType)
        namedValues[name] = VariableInfo(alloca, llvmType)

        decl.expr()?.let {
            val value = compileExpr(it)
            LLVMBuildStore(builder, value, alloca)
        }
    }

    private fun compileAssign(assign: ShchParser.AssignStmtContext) {
        val name = assign.ID().text
        val varInfo = namedValues[name] ?: error("Variable '$name' not declared")
        val value = compileExpr(assign.expr())
        LLVMBuildStore(builder, value, varInfo.ptr)
    }

    private fun compilePrint(print: ShchParser.PrintStmtContext) {
        // (same logic you already had, but remove the trailing "\n" from the format strings)
        var value = compileExpr(print.expr())
        var typeKind = LLVMGetTypeKind(LLVMTypeOf(value))

        if (typeKind == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(LLVMTypeOf(value)) == 1) {
            value = LLVMBuildZExt(builder, value, LLVMInt32TypeInContext(context), "booltoint")
            typeKind = LLVMIntegerTypeKind // теперь это точно целое число i32
        }

        // For 'print', we deliberately *do not* add the newline in the format string
        val formatStr = when (typeKind) {
            LLVMDoubleTypeKind   -> "%f"       // no \n
            LLVMIntegerTypeKind  -> "%d"       // no \n
            LLVMPointerTypeKind  -> "%s"       // no \n (for strings)
            else -> error("Unsupported type in print")
        }

        val printfArgTypes = PointerPointer<LLVMTypeRef>(1)
        printfArgTypes.put(0, LLVMPointerType(LLVMInt8TypeInContext(context), 0))
        val printfType = LLVMFunctionType(
            LLVMInt32TypeInContext(context),
            printfArgTypes,
            1,
            1
        )
        val printfFunc = LLVMGetNamedFunction(module, "printf")
            ?: LLVMAddFunction(module, "printf", printfType)

        val format = buildGlobalStringPtr(context, module, builder, formatStr, "fmt")
        LLVMBuildCall2(builder, printfType, printfFunc, PointerPointer(format, value), 2, "printfcall")
    }

    private fun compilePrintln(printlnCtx: ShchParser.PrintlnStmtContext) {
        var value = compileExpr(printlnCtx.expr())
        var typeKind = LLVMGetTypeKind(LLVMTypeOf(value))


        if (typeKind == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(LLVMTypeOf(value)) == 1) {

            value = LLVMBuildZExt(builder, value, LLVMInt32TypeInContext(context), "booltoint")
            typeKind = LLVMIntegerTypeKind // теперь это точно целое число i32
        }


        val formatStr = when (typeKind) {
            LLVMDoubleTypeKind   -> "%f\n"
            LLVMIntegerTypeKind  -> "%d\n"
            LLVMPointerTypeKind  -> "%s\n"
            else -> error("Unsupported type in println")
        }


        val printfArgTypes = PointerPointer<LLVMTypeRef>(1)
        printfArgTypes.put(0, LLVMPointerType(LLVMInt8TypeInContext(context), 0))
        val printfType = LLVMFunctionType(
            LLVMInt32TypeInContext(context),
            printfArgTypes,
            1,
            1
        )
        val printfFunc = LLVMGetNamedFunction(module, "printf")
            ?: LLVMAddFunction(module, "printf", printfType)

        val format = buildGlobalStringPtr(context, module, builder, formatStr, "fmt_ln")
        LLVMBuildCall2(builder, printfType, printfFunc, PointerPointer(format, value), 2, "printfcall")
    }


    private fun compileRead(readStmt: ShchParser.ReadStmtContext) {
        val name = readStmt.ID().text
        val varInfo = namedValues[name] ?: error("Variable '$name' not declared")

        val formatStr = when (LLVMGetTypeKind(varInfo.type)) {
            LLVMIntegerTypeKind -> "%d"
            LLVMDoubleTypeKind -> "%lf"
            else -> error("Unsupported type for read")
        }
        val printfArgTypes = PointerPointer<LLVMTypeRef>(1)
        printfArgTypes.put(0, LLVMPointerType(LLVMInt8TypeInContext(context), 0))
        val scanfType = LLVMFunctionType(
            LLVMInt32TypeInContext(context),
            printfArgTypes,
            1,
            1
        )

        val scanfFunc = LLVMGetNamedFunction(module, "scanf") ?: LLVMAddFunction(module, "scanf", scanfType)
        val format = buildGlobalStringPtr(context, module, builder, formatStr, "fmt_read")

        LLVMBuildCall2(builder, scanfType, scanfFunc, PointerPointer(format, varInfo.ptr), 2, "scanfcall")
    }

    private fun compileExpr(ctx: ShchParser.ExprContext): LLVMValueRef {

        return when {
            ctx.TRUE() != null -> LLVMConstInt(LLVMInt1TypeInContext(context), 1, 0)

            ctx.FALSE() != null -> LLVMConstInt(LLVMInt1TypeInContext(context), 0, 0)

            ctx.not != null && ctx.not.text == "!" -> {
                val value = compileCondExpr(ctx.expr(0))
                val valueType = LLVMTypeOf(value)


                val i1Value = when {
                    LLVMGetTypeKind(valueType) == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(valueType) == 1 -> value
                    LLVMGetTypeKind(valueType) == LLVMIntegerTypeKind ->
                        LLVMBuildICmp(builder, LLVMIntNE, value, LLVMConstInt(valueType, 0, 0), "boolify")
                    LLVMGetTypeKind(valueType) == LLVMDoubleTypeKind ->
                        LLVMBuildFCmp(builder, LLVMRealUNE, value, LLVMConstReal(valueType, 0.0), "boolify")
                    else -> error("Unsupported type for '!' operator: $valueType")
                }

                val result = LLVMBuildXor(builder, i1Value, LLVMConstInt(LLVMInt1TypeInContext(context), 1, 0), "nottmp")
                result
            }


            ctx.op != null && ctx.op.text in setOf("&", "|", "^") -> {
                val left = compileCondExpr(ctx.expr(0))
                val right = compileCondExpr(ctx.expr(1))


                val result = when (ctx.op.text) {
                    "&" -> LLVMBuildAnd(builder, left, right, "andtmp")
                    "|" -> LLVMBuildOr(builder, left, right, "ortmp")
                    "^" -> LLVMBuildXor(builder, left, right, "xortmp")
                    else -> error("Unknown boolean operator: ${ctx.op.text}")
                }

                result
            }


            ctx.NUMBER() != null && ctx.NUMBER().text.contains(".") ->
                LLVMConstReal(LLVMDoubleTypeInContext(context), ctx.NUMBER().text.toDouble())

            ctx.NUMBER() != null ->
                LLVMConstInt(LLVMInt32TypeInContext(context), ctx.NUMBER().text.toLong(), 0)

            ctx.ID() != null -> {
                val varInfo = namedValues[ctx.ID().text] ?: error("Variable '${ctx.ID().text}' not declared")
                LLVMBuildLoad2(builder, varInfo.type, varInfo.ptr, BytePointer(*("${ctx.ID().text}\u0000".toByteArray())))
            }

            ctx.STRING() != null -> {
                val raw = ctx.STRING().text
                val text = raw.substring(1, raw.length - 1)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"") + "\u0000"  // ⬅️ explicitly add null terminator
                buildGlobalStringPtr(context, module, builder, text, "strtmp")
            }

            ctx.op != null -> {
                val left = compileExpr(ctx.expr(0))
                val right = compileExpr(ctx.expr(1))

                if (isFloat(left, right)) {
                    var l = promoteToFloat(builder, left, context)
                    var r = promoteToFloat(builder, right, context)

                    if (LLVMGetTypeKind(LLVMTypeOf(l)) == LLVMIntegerTypeKind &&
                        LLVMGetIntTypeWidth(LLVMTypeOf(l)) == 1) {
                        l = LLVMBuildUIToFP(builder, l, LLVMDoubleTypeInContext(context), "booltofloat_l")
                    }
                    if (LLVMGetTypeKind(LLVMTypeOf(r)) == LLVMIntegerTypeKind &&
                        LLVMGetIntTypeWidth(LLVMTypeOf(r)) == 1) {
                        r = LLVMBuildUIToFP(builder, r, LLVMDoubleTypeInContext(context), "booltofloat_r")
                    }

                    return when (ctx.op.text) {
                        "+" -> LLVMBuildFAdd(builder, l, r, "faddtmp")
                        "-" -> LLVMBuildFSub(builder, l, r, "fsubtmp")
                        "*" -> LLVMBuildFMul(builder, l, r, "fmultmp")
                        "/" -> LLVMBuildFDiv(builder, l, r, "fdivtmp")
                        "==" -> LLVMBuildFCmp(builder, LLVMRealOEQ, l, r, "cmptmp")
                        "!=" -> LLVMBuildFCmp(builder, LLVMRealUNE, l, r, "cmptmp")
                        "<"  -> LLVMBuildFCmp(builder, LLVMRealOLT, l, r, "cmptmp")
                        "<=" -> LLVMBuildFCmp(builder, LLVMRealOLE, l, r, "cmptmp")
                        ">"  -> LLVMBuildFCmp(builder, LLVMRealOGT, l, r, "cmptmp")
                        ">=" -> LLVMBuildFCmp(builder, LLVMRealOGE, l, r, "cmptmp")
                        else -> error("Unknown float operator: ${ctx.op.text}")
                    }
                } else {
                    return when (ctx.op.text) {
                        "+"  -> LLVMBuildAdd(builder, left, right, "addtmp")
                        "-"  -> LLVMBuildSub(builder, left, right, "subtmp")
                        "*"  -> LLVMBuildMul(builder, left, right, "multmp")
                        "/" -> {
                            var l = left
                            var r = right

                            if (LLVMGetTypeKind(LLVMTypeOf(l)) == LLVMIntegerTypeKind &&
                                LLVMGetIntTypeWidth(LLVMTypeOf(l)) == 1) {
                                l = boolToInt(l, builder, context)
                            }
                            if (LLVMGetTypeKind(LLVMTypeOf(r)) == LLVMIntegerTypeKind &&
                                LLVMGetIntTypeWidth(LLVMTypeOf(r)) == 1) {
                                r = boolToInt(r, builder, context   )
                            }

                            if (LLVMGetTypeKind(LLVMTypeOf(l)) != LLVMIntegerTypeKind ||
                                LLVMGetTypeKind(LLVMTypeOf(r)) != LLVMIntegerTypeKind) {
                                error("Operands to '/' must be both Int")
                            }

                            LLVMBuildSDiv(builder, l, r, "divtmp")
                        }

                        "==" -> LLVMBuildICmp(builder, LLVMIntEQ, left, right, "cmptmp")
                        "!=" -> LLVMBuildICmp(builder, LLVMIntNE, left, right, "cmptmp")
                        "<"  -> LLVMBuildICmp(builder, LLVMIntSLT, left, right, "cmptmp")
                        "<=" -> LLVMBuildICmp(builder, LLVMIntSLE, left, right, "cmptmp")
                        ">"  -> LLVMBuildICmp(builder, LLVMIntSGT, left, right, "cmptmp")
                        ">=" -> LLVMBuildICmp(builder, LLVMIntSGE, left, right, "cmptmp")
                        else -> error("Unknown integer operator: ${ctx.op.text}")
                    }
                }
            }

            else -> compileExpr(ctx.expr(0))
        }
    }

    private fun compileShortCircuit(ctx: ShchParser.ExprContext): LLVMValueRef {
        val lhsExpr = ctx.expr(0)
        val rhsExpr = ctx.expr(1)
        val op = ctx.op.text

        val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))

        val evalRhsBB = LLVMAppendBasicBlockInContext(context, function, "sc.rhs")
        val endBB = LLVMAppendBasicBlockInContext(context, function, "sc.end")

        val result = LLVMBuildAlloca(builder, LLVMInt1TypeInContext(context), "sc.tmp")

        val lhsValue = compileCondExpr(lhsExpr)

        if (op == "&&") {
            LLVMBuildCondBr(builder, lhsValue, evalRhsBB, endBB)
        } else {
            LLVMBuildCondBr(builder, lhsValue, endBB, evalRhsBB)
        }

        LLVMPositionBuilderAtEnd(builder, evalRhsBB)
        val rhsValue = compileCondExpr(rhsExpr)
        LLVMBuildStore(builder, rhsValue, result)
        LLVMBuildBr(builder, endBB)
        LLVMPositionBuilderAtEnd(builder, endBB)

        LLVMBuildStore(builder, lhsValue, result)

        return LLVMBuildLoad2(builder, LLVMInt1TypeInContext(context), result, "scload")
    }


    private fun compileIf(ifStmt: ShchParser.IfStmtContext) {
        val condValue = compileCondExpr(ifStmt.expr())

        val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
        val thenBB = LLVMAppendBasicBlockInContext(context, function, "if.then")
        val elseBB = LLVMAppendBasicBlockInContext(context, function, "if.else")
        val mergeBB = LLVMAppendBasicBlockInContext(context, function, "if.end")

        LLVMBuildCondBr(builder, condValue, thenBB, elseBB)

        // Then block
        LLVMPositionBuilderAtEnd(builder, thenBB)
        val thenHasTerminator = compileBlock(ifStmt.block(0))
        if (!thenHasTerminator) LLVMBuildBr(builder, mergeBB)

        // Else block
        LLVMPositionBuilderAtEnd(builder, elseBB)
        val elseHasTerminator = if (ifStmt.block().size > 1) {
            compileBlock(ifStmt.block(1))
        } else false
        if (!elseHasTerminator) LLVMBuildBr(builder, mergeBB)

        LLVMPositionBuilderAtEnd(builder, mergeBB)
    }


    private fun compileBlock(block: ShchParser.BlockContext): Boolean {
        for (stmt in block.statement()) {
            compileStatement(stmt)
        }
        val terminator = LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder))
        return terminator != null && !terminator.isNull
    }


    private fun compileCondExpr(expr: ShchParser.ExprContext): LLVMValueRef {

        if (expr.op != null && (expr.op.text == "&&" || expr.op.text == "||")) {
            val result = compileShortCircuit(expr)
            return result
        }

        val value = compileExpr(expr)
        val type = LLVMTypeOf(value)


        val result = when {
            LLVMGetTypeKind(type) == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(type) == 1 ->
                value
            LLVMGetTypeKind(type) == LLVMIntegerTypeKind ->
                LLVMBuildICmp(builder, LLVMIntNE, value, LLVMConstInt(type, 0, 0), "ifcond")
            LLVMGetTypeKind(type) == LLVMDoubleTypeKind ->
                LLVMBuildFCmp(builder, LLVMRealUNE, value, LLVMConstReal(type, 0.0), "ifcond")
            else ->
                error("Unsupported type for condition")
        }

        return result
    }


    fun saveToFile(path: String) {
        if (compilationFailed) {
            println("⚠️ Skipping IR save due to compilation failure.")
            return
        }
        if (LLVMVerifyModule(module, LLVMAbortProcessAction, null as BytePointer?) == 0) {
            LLVMPrintModuleToFile(module, path, null as BytePointer?)
            println("✅ LLVM IR saved: $path")
        } else {
            println("❌ LLVM module verification failed")
        }
    }

    fun dispose() {
        LLVMDisposeBuilder(builder)
        LLVMDisposeModule(module)
        LLVMContextDispose(context)
    }
}