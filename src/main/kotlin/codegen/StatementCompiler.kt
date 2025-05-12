package codegen

import codegen.data.VariableInfo
import codegen.utils.LLVMUtils
import context.CompilerContext
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM
import org.bytedeco.llvm.global.LLVM.*
import shch.ShchParser

class StatementCompiler(private val ctx: CompilerContext, private val exprCompiler: ExpressionCompiler) {

    fun compileStatement(stmt: ShchParser.StatementContext) {
        when {
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
        for (stmt in block.statement()) {
            compileStatement(stmt)

            val currentBB = LLVMGetInsertBlock(ctx.builder)
            val terminator = LLVMGetBasicBlockTerminator(currentBB)
            if (terminator != null && !terminator.isNull) {
                println("🛑 Found terminator after: ${stmt.text}")
                return true
            }
        }

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
        // (same logic you already had, but remove the trailing "\n" from the format strings)
        var value = exprCompiler.compileExpr(print.expr())
        var typeKind = LLVMGetTypeKind(LLVMTypeOf(value))

        if (typeKind == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(LLVMTypeOf(value)) == 1) {
            value = LLVMBuildZExt(ctx.builder, value, LLVMInt32TypeInContext(ctx.context), "booltoint")
            typeKind = LLVMIntegerTypeKind // теперь это точно целое число i32
        }

        // For 'print', we deliberately *do not* add the newline in the format string
        val formatStr = when (typeKind) {
            LLVMDoubleTypeKind -> "%f"       // no \n
            LLVMIntegerTypeKind -> "%d"       // no \n
            LLVMPointerTypeKind -> "%s"       // no \n (for strings)
            else -> error("Unsupported type in print")
        }

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

        val format = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, formatStr, "fmt")
        LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(format, value), 2, "printfcall")
    }

    private fun compilePrintln(printlnCtx: ShchParser.PrintlnStmtContext) {
        var value = exprCompiler.compileExpr(printlnCtx.expr())
        var typeKind = LLVMGetTypeKind(LLVMTypeOf(value))


        if (typeKind == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(LLVMTypeOf(value)) == 1) {

            value = LLVMBuildZExt(ctx.builder, value, LLVMInt32TypeInContext(ctx.context), "booltoint")
            typeKind = LLVMIntegerTypeKind // теперь это точно целое число i32
        }


        val formatStr = when (typeKind) {
            LLVMDoubleTypeKind -> "%f\n"
            LLVMIntegerTypeKind -> "%d\n"
            LLVMPointerTypeKind -> "%s\n"
            else -> error("Unsupported type in println")
        }


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

        val format = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, formatStr, "fmt_ln")
        LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(format, value), 2, "printfcall")
    }


    private fun compileRead(readStmt: ShchParser.ReadStmtContext) {
        val name = readStmt.ID().text
        val varInfo = ctx.namedValues[name] ?: error("Variable '$name' not declared")

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
        val llvmType = LLVMUtils.getLLVMType(ctx.context, decl.type().text)

        val currentFunction = LLVMGetBasicBlockParent(LLVMGetInsertBlock(ctx.builder))
        val alloca = LLVMUtils.createEntryBlockAlloca(ctx.builder, currentFunction, name, llvmType)
        ctx.namedValues[name] = VariableInfo(alloca, llvmType)

        decl.expr()?.let {
            val value = exprCompiler.compileExpr(it)
            LLVMBuildStore(ctx.builder, value, alloca)
        }
    }

    private fun compileAssign(assign: ShchParser.AssignStmtContext) {
        val name = assign.ID().text
        val varInfo = ctx.namedValues[name] ?: error("Variable '$name' not declared")
        val value = exprCompiler.compileExpr(assign.expr())
        LLVMBuildStore(ctx.builder, value, varInfo.ptr)
    }
}
