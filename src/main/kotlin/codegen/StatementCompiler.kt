package codegen

import codegen.data.VariableInfo
import codegen.utils.LLVMUtils
import codegen.utils.TypeTags
import context.CompilerContext
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
import shch.ShchParser

class StatementCompiler(private val ctx: CompilerContext, private val exprCompiler: ExpressionCompiler) {

    fun compileStatement(stmt: ShchParser.StatementContext) {
        when {
            stmt.block() != null -> compileBlock(stmt.block())
            stmt.exprStmt() != null -> compileExprStmt(stmt.exprStmt())
            stmt.varDecl() != null -> compileVarDecl(stmt.varDecl())
            stmt.assignStmt() != null -> compileAssign(stmt.assignStmt())
            stmt.printStmt() != null -> compilePrint(stmt.printStmt())
            stmt.printlnStmt() != null -> compilePrintln(stmt.printlnStmt())
            stmt.readStmt() != null -> compileRead(stmt.readStmt())
            stmt.ifStmt() != null -> compileIf(stmt.ifStmt())
            stmt.whileStmt() != null -> compileWhile(stmt.whileStmt())
            stmt.returnStmt() != null -> compileReturn(stmt.returnStmt())  // ✅ DODAJ TO

            else -> error("Unsupported statement: ${stmt.text}")
        }
    }

    private fun compileExprStmt(exprStmt: ShchParser.ExprStmtContext) {
        val value = exprCompiler.compileExpr(exprStmt.expr())
        // Jeżeli wartość nie jest void, ale nie jest używana — po prostu ignorujemy.
        println("💡 Standalone expression compiled (value discarded): ${exprStmt.text}")
    }

    private fun compileReturn(returnStmt: ShchParser.ReturnStmtContext) {
        val value = returnStmt.expr()?.let {
            exprCompiler.compileExpr(it)
        }

        if (value != null) {
            LLVMBuildRet(ctx.builder, value)
            println("🔙 return ${LLVMPrintTypeToString(LLVMTypeOf(value)).string}")
        } else {
            LLVMBuildRetVoid(ctx.builder)
            println("🔙 return void")
        }
    }


    fun compileBlock(block: ShchParser.BlockContext): Boolean {
        ctx.enterScope()
        for (stmt in block.statement()) {
            compileStatement(stmt)
            val currentBB = LLVMGetInsertBlock(ctx.builder)
            val terminator = LLVMGetBasicBlockTerminator(currentBB)
            if (terminator != null && !terminator.isNull) {
                println("🛑 Block terminated early after: ${stmt.text}")
                ctx.exitScope()
                return true
            }
        }
        ctx.exitScope()
        val finalTerm = LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(ctx.builder))
        return finalTerm != null && !finalTerm.isNull
    }

    private fun compileIf(ifStmt: ShchParser.IfStmtContext) {
        val condValue = exprCompiler.compileCondExpr(ifStmt.expr())

        val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(ctx.builder))
        val thenBB = LLVMAppendBasicBlockInContext(ctx.context, function, "if.then")
        val elseBB = LLVMAppendBasicBlockInContext(ctx.context, function, "if.else")
        val mergeBB = LLVMAppendBasicBlockInContext(ctx.context, function, "if.end")

        LLVMBuildCondBr(ctx.builder, condValue, thenBB, elseBB)

        // Then block
        LLVMPositionBuilderAtEnd(ctx.builder, thenBB)
        val thenHasTerminator = compileBlock(ifStmt.block(0))
        if (!thenHasTerminator) LLVMBuildBr(ctx.builder, mergeBB)

        // Else block
        LLVMPositionBuilderAtEnd(ctx.builder, elseBB)
        val elseHasTerminator = if (ifStmt.block().size > 1) {
            compileBlock(ifStmt.block(1))
        } else false
        if (!elseHasTerminator) LLVMBuildBr(ctx.builder, mergeBB)

        LLVMPositionBuilderAtEnd(ctx.builder, mergeBB)
    }

    private fun compileWhile(whileStmt: ShchParser.WhileStmtContext) {
        val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(ctx.builder))

        val condBB = LLVMAppendBasicBlockInContext(ctx.context, function, "while.cond")
        val bodyBB = LLVMAppendBasicBlockInContext(ctx.context, function, "while.body")
        val afterBB = LLVMAppendBasicBlockInContext(ctx.context, function, "while.end")

        LLVMBuildBr(ctx.builder, condBB)

        // Условие
        LLVMPositionBuilderAtEnd(ctx.builder, condBB)
        val condValue = exprCompiler.compileCondExpr(whileStmt.expr())
        LLVMBuildCondBr(ctx.builder, condValue, bodyBB, afterBB)

        // Тело цикла
        LLVMPositionBuilderAtEnd(ctx.builder, bodyBB)
        val bodyHasTerminator = compileBlock(whileStmt.block())
        if (!bodyHasTerminator) LLVMBuildBr(ctx.builder, condBB)

        // После цикла
        LLVMPositionBuilderAtEnd(ctx.builder, afterBB)
    }

    private fun compilePrint(print: ShchParser.PrintStmtContext) {
        var value = exprCompiler.compileExpr(print.expr())
        val isAny = LLVMUtils.isAnyType(ctx, LLVMTypeOf(value))

        val printfArgTypes = PointerPointer<LLVMTypeRef>(1)
        printfArgTypes.put(0, LLVMPointerType(LLVMInt8TypeInContext(ctx.context), 0))
        val printfType = LLVMFunctionType(
            LLVMInt32TypeInContext(ctx.context),
            printfArgTypes,
            1,
            1
        )
        val printfFunc = LLVMGetNamedFunction(ctx.module, "printf")
            ?: LLVMAddFunction(ctx.module, "printf", printfType)

        if (isAny) {
            val tag = LLVMUtils.getTag(ctx, value)
            val parent = LLVMGetBasicBlockParent(LLVMGetInsertBlock(ctx.builder))

            val intBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "print.int")
            val floatBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "print.float")
            val boolBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "print.bool")
            val endBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "print.end")

            val switch = LLVMBuildSwitch(ctx.builder, tag, endBB, 3)
            LLVMAddCase(switch, LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_INT.toLong(), 0), intBB)
            LLVMAddCase(
                switch,
                LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_FLOAT64.toLong(), 0),
                floatBB
            )
            LLVMAddCase(
                switch,
                LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_BOOL.toLong(), 0),
                boolBB
            )

            // Int
            LLVMPositionBuilderAtEnd(ctx.builder, intBB)
            val intVal = LLVMUtils.unboxInt(ctx, value)
            val fmtInt = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, "%d", "fmt_int")
            LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(fmtInt, intVal), 2, "")
            LLVMBuildBr(ctx.builder, endBB)

            // Float
            LLVMPositionBuilderAtEnd(ctx.builder, floatBB)
            val floatVal = LLVMUtils.unboxFloat64(ctx, value)
            val fmtFloat = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, "%lf", "fmt_float")
            LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(fmtFloat, floatVal), 2, "")
            LLVMBuildBr(ctx.builder, endBB)

            // Bool
            LLVMPositionBuilderAtEnd(ctx.builder, boolBB)
            val boolVal = LLVMUtils.unboxBool(ctx, value)
            val boolInt = LLVMBuildZExt(ctx.builder, boolVal, LLVMInt32TypeInContext(ctx.context), "booltoint")
            val fmtBool = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, "%d", "fmt_bool")
            LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(fmtBool, boolInt), 2, "")
            LLVMBuildBr(ctx.builder, endBB)

            LLVMPositionBuilderAtEnd(ctx.builder, endBB)
            return
        }

        // non-Any fallback
        var typeKind = LLVMGetTypeKind(LLVMTypeOf(value))

        if (typeKind == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(LLVMTypeOf(value)) == 1) {
            value = LLVMBuildZExt(ctx.builder, value, LLVMInt32TypeInContext(ctx.context), "booltoint")
            typeKind = LLVMIntegerTypeKind
        }
        if (typeKind == LLVMFloatTypeKind) {
            value = LLVMBuildFPExt(ctx.builder, value, LLVMDoubleTypeInContext(ctx.context), "fpext_to_double")
        }

        val formatStr = when (typeKind) {
            LLVMFloatTypeKind -> "%f"
            LLVMDoubleTypeKind -> "%lf"
            LLVMIntegerTypeKind -> "%d"
            LLVMPointerTypeKind -> "%s"
            else -> error("Unsupported type in print")
        }

        val format = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, formatStr, "fmt")
        LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(format, value), 2, "printfcall")
    }


    private fun compilePrintln(printlnCtx: ShchParser.PrintlnStmtContext) {
        var value = exprCompiler.compileExpr(printlnCtx.expr())
        val isAny = LLVMUtils.isAnyType(ctx, LLVMTypeOf(value))

        val printfArgTypes = PointerPointer<LLVMTypeRef>(1)
        printfArgTypes.put(0, LLVMPointerType(LLVMInt8TypeInContext(ctx.context), 0))
        val printfType = LLVMFunctionType(
            LLVMInt32TypeInContext(ctx.context),
            printfArgTypes,
            1,
            1
        )
        val printfFunc = LLVMGetNamedFunction(ctx.module, "printf")
            ?: LLVMAddFunction(ctx.module, "printf", printfType)

        if (isAny) {
            println("🧩 println: dynamic Any type detected")
            println("🛠 println type: ${LLVMPrintTypeToString(LLVMTypeOf(value)).string}")


            val tag = LLVMUtils.getTag(ctx, value)
            val parent = LLVMGetBasicBlockParent(LLVMGetInsertBlock(ctx.builder))

            val intBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "println.int")
            val floatBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "println.float")
            val boolBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "println.bool")
            val endBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "println.end")
            val stringBB = LLVMAppendBasicBlockInContext(ctx.context, parent, "println.string")


            val switch = LLVMBuildSwitch(ctx.builder, tag, endBB, 3)
            LLVMAddCase(switch, LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_INT.toLong(), 0), intBB)
            LLVMAddCase(
                switch,
                LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_FLOAT64.toLong(), 0),
                floatBB
            )
            LLVMAddCase(
                switch,
                LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_BOOL.toLong(), 0),
                boolBB
            )
            LLVMAddCase(
                switch,
                LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_STRING.toLong(), 0),
                stringBB
            )


            // Int
            LLVMPositionBuilderAtEnd(ctx.builder, intBB)
            val intVal = LLVMUtils.unboxInt(ctx, value)
            val fmtInt = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, "%d\n", "fmt_int_ln")
            LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(fmtInt, intVal), 2, "")
            LLVMBuildBr(ctx.builder, endBB)

            // Float
            LLVMPositionBuilderAtEnd(ctx.builder, floatBB)
            val floatVal = LLVMUtils.unboxFloat64(ctx, value)
            val fmtFloat = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, "%lf\n", "fmt_float_ln")
            LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(fmtFloat, floatVal), 2, "")
            LLVMBuildBr(ctx.builder, endBB)

            // Bool
            LLVMPositionBuilderAtEnd(ctx.builder, boolBB)
            val boolVal = LLVMUtils.unboxBool(ctx, value)
            val boolInt = LLVMBuildZExt(ctx.builder, boolVal, LLVMInt32TypeInContext(ctx.context), "booltoint")
            val fmtBool = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, "%d\n", "fmt_bool_ln")
            LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(fmtBool, boolInt), 2, "")
            LLVMBuildBr(ctx.builder, endBB)

            // String
            LLVMPositionBuilderAtEnd(ctx.builder, stringBB)
            val strVal = LLVMUtils.unboxString(ctx, value)
            val fmtString =
                LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, "%s\n", "fmt_string_ln")
            LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(fmtString, strVal), 2, "")
            LLVMBuildBr(ctx.builder, endBB)

            LLVMPositionBuilderAtEnd(ctx.builder, endBB)
            return
        }

        // non-Any fallback
        var typeKind = LLVMGetTypeKind(LLVMTypeOf(value))
        if (typeKind == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(LLVMTypeOf(value)) == 1) {
            value = LLVMBuildZExt(ctx.builder, value, LLVMInt32TypeInContext(ctx.context), "booltoint")
            typeKind = LLVMIntegerTypeKind
        }
        if (typeKind == LLVMFloatTypeKind) {
            value = LLVMBuildFPExt(ctx.builder, value, LLVMDoubleTypeInContext(ctx.context), "fpext_to_double")
        }

        val formatStr = when (typeKind) {
            LLVMFloatTypeKind -> "%f\n"
            LLVMDoubleTypeKind -> "%lf\n"
            LLVMIntegerTypeKind -> "%d\n"
            LLVMPointerTypeKind -> "%s\n"
            else -> error("Unsupported type in println")
        }

        val format = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, formatStr, "fmt_ln")
        LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(format, value), 2, "printfcall")
    }


    private fun compileRead(readStmt: ShchParser.ReadStmtContext) {
        val name = readStmt.ID().text
        val varInfo = ctx.lookup(name) ?: error("Variable '$name' not declared")

        val typeKind = LLVMGetTypeKind(varInfo.type)
        val formatStr = when (typeKind) {
            LLVMIntegerTypeKind -> "%d"
            LLVMDoubleTypeKind -> "%lf"
            LLVMPointerTypeKind -> "%255s"
            else -> error("Unsupported type for read")
        }

        // Prepare scanf function
        val scanfArgTypes = PointerPointer<LLVMTypeRef>(1)
        scanfArgTypes.put(0, LLVMPointerType(LLVMInt8TypeInContext(ctx.context), 0))
        val scanfType = LLVMFunctionType(
            LLVMInt32TypeInContext(ctx.context),
            scanfArgTypes,
            1,
            1
        )
        val scanfFunc =
            LLVMGetNamedFunction(ctx.module, "scanf") ?: LLVMAddFunction(ctx.module, "scanf", scanfType)
        val format = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, formatStr, "fmt_read")

        val args = when (typeKind) {
            LLVMPointerTypeKind -> {
                // Alloca: stack buffer [256 x i8]
                val arrayType = LLVMArrayType(LLVMInt8TypeInContext(ctx.context), 256)
                val bufferAlloca = LLVMBuildAlloca(ctx.builder, arrayType, "strbuf.alloca")

                // Cast [256 x i8]* → i8*
                val bufferPtr = LLVMBuildBitCast(
                    ctx.builder,
                    bufferAlloca,
                    LLVMPointerType(LLVMInt8TypeInContext(ctx.context), 0),
                    "strbuf.ptr"
                )

                // Store pointer to variable
                LLVMBuildStore(ctx.builder, bufferPtr, varInfo.ptr)

                PointerPointer(format, bufferPtr)
            }

            else -> PointerPointer(format, varInfo.ptr)
        }

        LLVMBuildCall2(ctx.builder, scanfType, scanfFunc, args, 2, "scanfcall")
    }

    private fun compileVarDecl(decl: ShchParser.VarDeclContext) {
        val name = decl.ID().text
        val llvmType = LLVMUtils.getLLVMType(ctx, decl.type().text)

        val currentFunction = LLVMGetBasicBlockParent(LLVMGetInsertBlock(ctx.builder))
        val alloca = LLVMUtils.createEntryBlockAlloca(ctx.builder, currentFunction, name, llvmType)
        ctx.declare(name, VariableInfo(alloca, llvmType))

        decl.expr()?.let {
            val value = exprCompiler.compileExpr(it)
            val finalValue = if (LLVMUtils.isAnyType(ctx, llvmType)) {
                val valType = LLVMTypeOf(value)
                when {
                    LLVMGetTypeKind(valType) == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(valType) == 1 ->
                        LLVMUtils.boxBool(ctx, value)

                    LLVMGetTypeKind(valType) == LLVMIntegerTypeKind ->
                        LLVMUtils.boxInt(ctx, value)

                    LLVMGetTypeKind(valType) == LLVMDoubleTypeKind ->
                        LLVMUtils.boxFloat64(ctx, value)

                    LLVMGetTypeKind(valType) == LLVMPointerTypeKind ->
                        LLVMUtils.boxString(ctx, value)

                    else -> error("Cannot box unsupported type into Any: ${LLVMPrintTypeToString(valType).string}")
                }
            } else value
            LLVMBuildStore(ctx.builder, finalValue, alloca)
        }

    }

    private fun compileAssign(assign: ShchParser.AssignStmtContext) {
        val name = assign.ID().text
        val varInfo = ctx.lookup(name) ?: error("Variable '$name' not declared")
        val value = exprCompiler.compileExpr(assign.expr())
        LLVMBuildStore(ctx.builder, value, varInfo.ptr)
    }
}
