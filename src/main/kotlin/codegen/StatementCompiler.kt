package codegen;

import codegen.data.VariableInfo
import codegen.utils.LLVMUtils
import context.CompilerContext
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM
import shch.ShchParser

class StatementCompiler(private val ctx: CompilerContext, private val exprCompiler: ExpressionCompiler) {

    fun compileStatement(stmt: ShchParser.StatementContext) {
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

    private fun compileBlock(block: ShchParser.BlockContext): Boolean {
        for (stmt in block.statement()) {
            compileStatement(stmt)
        }
        val terminator = LLVM.LLVMGetBasicBlockTerminator(LLVM.LLVMGetInsertBlock(ctx.builder))
        return terminator != null && !terminator.isNull
    }

    private fun compileIf(ifStmt: ShchParser.IfStmtContext) {
        val condValue = exprCompiler.compileCondExpr(ifStmt.expr())

        val function = LLVM.LLVMGetBasicBlockParent(LLVM.LLVMGetInsertBlock(ctx.builder))
        val thenBB = LLVM.LLVMAppendBasicBlockInContext(ctx.context, function, "if.then")
        val elseBB = LLVM.LLVMAppendBasicBlockInContext(ctx.context, function, "if.else")
        val mergeBB = LLVM.LLVMAppendBasicBlockInContext(ctx.context, function, "if.end")

        LLVM.LLVMBuildCondBr(ctx.builder, condValue, thenBB, elseBB)

        // Then block
        LLVM.LLVMPositionBuilderAtEnd(ctx.builder, thenBB)
        val thenHasTerminator = compileBlock(ifStmt.block(0))
        if (!thenHasTerminator) LLVM.LLVMBuildBr(ctx.builder, mergeBB)

        // Else block
        LLVM.LLVMPositionBuilderAtEnd(ctx.builder, elseBB)
        val elseHasTerminator = if (ifStmt.block().size > 1) {
            compileBlock(ifStmt.block(1))
        } else false
        if (!elseHasTerminator) LLVM.LLVMBuildBr(ctx.builder, mergeBB)

        LLVM.LLVMPositionBuilderAtEnd(ctx.builder, mergeBB)
    }

    private fun compileWhile(whileStmt: ShchParser.WhileStmtContext) {
        val function = LLVM.LLVMGetBasicBlockParent(LLVM.LLVMGetInsertBlock(ctx.builder))

        val condBB = LLVM.LLVMAppendBasicBlockInContext(ctx.context, function, "while.cond")
        val bodyBB = LLVM.LLVMAppendBasicBlockInContext(ctx.context, function, "while.body")
        val afterBB = LLVM.LLVMAppendBasicBlockInContext(ctx.context, function, "while.end")

        LLVM.LLVMBuildBr(ctx.builder, condBB)

        // Условие
        LLVM.LLVMPositionBuilderAtEnd(ctx.builder, condBB)
        val condValue = exprCompiler.compileCondExpr(whileStmt.expr())
        LLVM.LLVMBuildCondBr(ctx.builder, condValue, bodyBB, afterBB)

        // Тело цикла
        LLVM.LLVMPositionBuilderAtEnd(ctx.builder, bodyBB)
        val bodyHasTerminator = compileBlock(whileStmt.block())
        if (!bodyHasTerminator) LLVM.LLVMBuildBr(ctx.builder, condBB)

        // После цикла
        LLVM.LLVMPositionBuilderAtEnd(ctx.builder, afterBB)
    }

    private fun compilePrint(print: ShchParser.PrintStmtContext) {
        // (same logic you already had, but remove the trailing "\n" from the format strings)
        var value = exprCompiler.compileExpr(print.expr())
        var typeKind = LLVM.LLVMGetTypeKind(LLVM.LLVMTypeOf(value))

        if (typeKind == LLVM.LLVMIntegerTypeKind && LLVM.LLVMGetIntTypeWidth(LLVM.LLVMTypeOf(value)) == 1) {
            value = LLVM.LLVMBuildZExt(ctx.builder, value, LLVM.LLVMInt32TypeInContext(ctx.context), "booltoint")
            typeKind = LLVM.LLVMIntegerTypeKind // теперь это точно целое число i32
        }

        // For 'print', we deliberately *do not* add the newline in the format string
        val formatStr = when (typeKind) {
            LLVM.LLVMDoubleTypeKind -> "%f"       // no \n
            LLVM.LLVMIntegerTypeKind -> "%d"       // no \n
            LLVM.LLVMPointerTypeKind -> "%s"       // no \n (for strings)
            else -> error("Unsupported type in print")
        }

        val printfArgTypes = PointerPointer<LLVMTypeRef>(1)
        printfArgTypes.put(0, LLVM.LLVMPointerType(LLVM.LLVMInt8TypeInContext(ctx.context), 0))
        val printfType = LLVM.LLVMFunctionType(
            LLVM.LLVMInt32TypeInContext(ctx.context),
            printfArgTypes,
            1,
            1
        )
        val printfFunc = LLVM.LLVMGetNamedFunction(ctx.module, "printf")
            ?: LLVM.LLVMAddFunction(ctx.module, "printf", printfType)

        val format = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, formatStr, "fmt")
        LLVM.LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(format, value), 2, "printfcall")
    }

    private fun compilePrintln(printlnCtx: ShchParser.PrintlnStmtContext) {
        var value = exprCompiler.compileExpr(printlnCtx.expr())
        var typeKind = LLVM.LLVMGetTypeKind(LLVM.LLVMTypeOf(value))


        if (typeKind == LLVM.LLVMIntegerTypeKind && LLVM.LLVMGetIntTypeWidth(LLVM.LLVMTypeOf(value)) == 1) {

            value = LLVM.LLVMBuildZExt(ctx.builder, value, LLVM.LLVMInt32TypeInContext(ctx.context), "booltoint")
            typeKind = LLVM.LLVMIntegerTypeKind // теперь это точно целое число i32
        }


        val formatStr = when (typeKind) {
            LLVM.LLVMDoubleTypeKind -> "%f\n"
            LLVM.LLVMIntegerTypeKind -> "%d\n"
            LLVM.LLVMPointerTypeKind -> "%s\n"
            else -> error("Unsupported type in println")
        }


        val printfArgTypes = PointerPointer<LLVMTypeRef>(1)
        printfArgTypes.put(0, LLVM.LLVMPointerType(LLVM.LLVMInt8TypeInContext(ctx.context), 0))
        val printfType = LLVM.LLVMFunctionType(
            LLVM.LLVMInt32TypeInContext(ctx.context),
            printfArgTypes,
            1,
            1
        )
        val printfFunc = LLVM.LLVMGetNamedFunction(ctx.module, "printf")
            ?: LLVM.LLVMAddFunction(ctx.module, "printf", printfType)

        val format = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, formatStr, "fmt_ln")
        LLVM.LLVMBuildCall2(ctx.builder, printfType, printfFunc, PointerPointer(format, value), 2, "printfcall")
    }


    private fun compileRead(readStmt: ShchParser.ReadStmtContext) {
        val name = readStmt.ID().text
        val varInfo = ctx.namedValues[name] ?: error("Variable '$name' not declared")

        val typeKind = LLVM.LLVMGetTypeKind(varInfo.type)
        val formatStr = when (typeKind) {
            LLVM.LLVMIntegerTypeKind -> "%d"
            LLVM.LLVMDoubleTypeKind -> "%lf"
            LLVM.LLVMPointerTypeKind -> "%255s"
            else -> error("Unsupported type for read")
        }

        // Prepare scanf function
        val scanfArgTypes = PointerPointer<LLVMTypeRef>(1)
        scanfArgTypes.put(0, LLVM.LLVMPointerType(LLVM.LLVMInt8TypeInContext(ctx.context), 0))
        val scanfType = LLVM.LLVMFunctionType(
            LLVM.LLVMInt32TypeInContext(ctx.context),
            scanfArgTypes,
            1,
            1
        )
        val scanfFunc =
            LLVM.LLVMGetNamedFunction(ctx.module, "scanf") ?: LLVM.LLVMAddFunction(ctx.module, "scanf", scanfType)
        val format = LLVMUtils.buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, formatStr, "fmt_read")

        val args = when (typeKind) {
            LLVM.LLVMPointerTypeKind -> {
                // Alloca: stack buffer [256 x i8]
                val arrayType = LLVM.LLVMArrayType(LLVM.LLVMInt8TypeInContext(ctx.context), 256)
                val bufferAlloca = LLVM.LLVMBuildAlloca(ctx.builder, arrayType, "strbuf.alloca")

                // Cast [256 x i8]* → i8*
                val bufferPtr = LLVM.LLVMBuildBitCast(
                    ctx.builder,
                    bufferAlloca,
                    LLVM.LLVMPointerType(LLVM.LLVMInt8TypeInContext(ctx.context), 0),
                    "strbuf.ptr"
                )

                // Store pointer to variable
                LLVM.LLVMBuildStore(ctx.builder, bufferPtr, varInfo.ptr)

                PointerPointer(format, bufferPtr)
            }

            else -> PointerPointer(format, varInfo.ptr)
        }

        LLVM.LLVMBuildCall2(ctx.builder, scanfType, scanfFunc, args, 2, "scanfcall")
    }

    private fun compileVarDecl(decl: ShchParser.VarDeclContext) {
        val name = decl.ID().text
        val llvmType = LLVMUtils.getLLVMType(ctx.context, decl.type().text)

        val alloca = LLVMUtils.createEntryBlockAlloca(ctx.builder, ctx.mainFunction, name, llvmType)
        ctx.namedValues[name] = VariableInfo(alloca, llvmType)

        decl.expr()?.let {
            val value = exprCompiler.compileExpr(it)
            LLVM.LLVMBuildStore(ctx.builder, value, alloca)
        }
    }

    private fun compileAssign(assign: ShchParser.AssignStmtContext) {
        val name = assign.ID().text
        val varInfo = ctx.namedValues[name] ?: error("Variable '$name' not declared")
        val value = exprCompiler.compileExpr(assign.expr())
        LLVM.LLVMBuildStore(ctx.builder, value, varInfo.ptr)
    }
}
