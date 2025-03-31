package shch.codegen

import codegen.LLVMUtils.buildGlobalStringPtr
import codegen.LLVMUtils.createEntryBlockAlloca
import codegen.LLVMUtils.getLLVMType
import codegen.LLVMUtils.isFloat
import codegen.LLVMUtils.promoteToFloat
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.global.LLVM.*
import org.bytedeco.llvm.LLVM.*
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
                compileStatement(stmt)
            }
            LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0))
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

        // Переход к проверке условия
        LLVMBuildBr(builder, condBB)

        // Условие
        LLVMPositionBuilderAtEnd(builder, condBB)
        val condValue = compileCondExpr(whileStmt.expr())
        LLVMBuildCondBr(builder, condValue, bodyBB, afterBB)

        // Тело цикла
        LLVMPositionBuilderAtEnd(builder, bodyBB)
        compileBlock(whileStmt.block())
        LLVMBuildBr(builder, condBB) // возврат в условие

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
        val value = compileExpr(print.expr())
        val typeKind = LLVMGetTypeKind(LLVMTypeOf(value))
        val formatStr = when (typeKind) {
            LLVMDoubleTypeKind -> "%f\n"
            LLVMIntegerTypeKind -> "%d\n"
            LLVMPointerTypeKind -> "%s\n" // строки — как указатели
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
        val printfFunc = LLVMGetNamedFunction(module, "printf") ?: LLVMAddFunction(module, "printf", printfType)
        val format = buildGlobalStringPtr(context, module, builder, formatStr, "fmt")

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

    private fun compileExpr(ctx: ShchParser.ExprContext): LLVMValueRef = when {
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
                .replace("\\\"", "\"")
            buildGlobalStringPtr(context, module, builder, text, "strtmp")
        }



        ctx.op != null -> {
            val left = compileExpr(ctx.expr(0))
            val right = compileExpr(ctx.expr(1))

            if (isFloat(left, right)) {
                val l = promoteToFloat(builder, left, context)
                val r = promoteToFloat(builder, right, context)
                when (ctx.op.text) {
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
                 when (ctx.op.text) {
                    "+" -> LLVMBuildAdd(builder, left, right, "addtmp")
                    "-" -> LLVMBuildSub(builder, left, right, "subtmp")
                    "*" -> LLVMBuildMul(builder, left, right, "multmp")
                    "/" -> LLVMBuildSDiv(builder, left, right, "divtmp")
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

    private fun compileIf(ifStmt: ShchParser.IfStmtContext) {
        val condValue = compileCondExpr(ifStmt.expr())

        val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
        val thenBB = LLVMAppendBasicBlockInContext(context, function, "if.then")
        val elseBB = LLVMAppendBasicBlockInContext(context, function, "if.else")
        val mergeBB = LLVMAppendBasicBlockInContext(context, function, "if.end")

        LLVMBuildCondBr(builder, condValue, thenBB, elseBB)

        // Then block
        LLVMPositionBuilderAtEnd(builder, thenBB)
        compileBlock(ifStmt.block(0))
        LLVMBuildBr(builder, mergeBB)

        // Else block
        LLVMPositionBuilderAtEnd(builder, elseBB)
        if (ifStmt.block().size > 1) {
            compileBlock(ifStmt.block(1))
        }
        LLVMBuildBr(builder, mergeBB)

        // Merge block
        LLVMPositionBuilderAtEnd(builder, mergeBB)
    }

    private fun compileBlock(block: ShchParser.BlockContext) {
        for (stmt in block.statement()) {
            compileStatement(stmt)
        }
    }

    private fun compileCondExpr(expr: ShchParser.ExprContext): LLVMValueRef {
        val value = compileExpr(expr)
        return when (LLVMGetTypeKind(LLVMTypeOf(value))) {
            LLVMIntegerTypeKind -> LLVMBuildICmp(builder, LLVMIntNE, value, LLVMConstInt(LLVMTypeOf(value), 0, 0), "ifcond")
            LLVMDoubleTypeKind -> LLVMBuildFCmp(builder, LLVMRealUNE, value, LLVMConstReal(LLVMTypeOf(value), 0.0), "ifcond")
            else -> error("Unsupported type for condition")
        }
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