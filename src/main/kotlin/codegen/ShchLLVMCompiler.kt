package shch.codegen

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

    data class VariableInfo(val ptr: LLVMValueRef, val type: LLVMTypeRef)
    private val namedValues = mutableMapOf<String, VariableInfo>()

    fun compile(tree: ShchParser.ProgramContext) {
        val mainType = LLVMFunctionType(LLVMInt32TypeInContext(context), null as PointerPointer<LLVMTypeRef>?, 0, 0)
        mainFunc = LLVMAddFunction(module, "main", mainType)

        val entry = LLVMAppendBasicBlockInContext(context, mainFunc, "entry")
        LLVMPositionBuilderAtEnd(builder, entry)

        for (stmt in tree.statement()) {
            compileStatement(stmt)
        }

        LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0))
    }

    private fun compileStatement(stmt: ShchParser.StatementContext) {
        when {
            stmt.varDecl() != null -> compileVarDecl(stmt.varDecl())
            stmt.assignStmt() != null -> compileAssign(stmt.assignStmt())
            stmt.printStmt() != null -> compilePrint(stmt.printStmt())
            stmt.readStmt() != null -> compileRead(stmt.readStmt())
            else -> println("Unsupported statement: ${stmt.text}")
        }
    }

    private fun compileVarDecl(decl: ShchParser.VarDeclContext) {
        val name = decl.ID().text
        val llvmType = getLLVMType(decl.type().text)
        val alloca = createEntryBlockAlloca(name, llvmType)
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
        val formatStr = when (LLVMGetTypeKind(LLVMTypeOf(value))) {
            LLVMDoubleTypeKind -> "%f\n"
            LLVMIntegerTypeKind -> "%d\n"
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
        val format = buildGlobalStringPtr(formatStr, "fmt")

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
        val format = buildGlobalStringPtr(formatStr, "fmt_read")

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

        ctx.op != null -> {
            val left = compileExpr(ctx.expr(0))
            val right = compileExpr(ctx.expr(1))
            if (isFloat(left, right)) {
                val l = promoteToFloat(left)
                val r = promoteToFloat(right)
                when (ctx.op.text) {
                    "+" -> LLVMBuildFAdd(builder, l, r, "faddtmp")
                    "-" -> LLVMBuildFSub(builder, l, r, "fsubtmp")
                    "*" -> LLVMBuildFMul(builder, l, r, "fmultmp")
                    "/" -> LLVMBuildFDiv(builder, l, r, "fdivtmp")
                    else -> error("Unknown float operator: ${ctx.op.text}")
                }
            }
            else {
                when (ctx.op.text) {
                    "+" -> LLVMBuildAdd(builder, left, right, "addtmp")
                    "-" -> LLVMBuildSub(builder, left, right, "subtmp")
                    "*" -> LLVMBuildMul(builder, left, right, "multmp")
                    "/" -> LLVMBuildSDiv(builder, left, right, "divtmp")
                    else -> error("Unknown integer operator: ${ctx.op.text}")
                }
            }
        }
        else -> compileExpr(ctx.expr(0))
    }

    private fun promoteToFloat(value: LLVMValueRef): LLVMValueRef {
        val type = LLVMTypeOf(value)
        return if (LLVMGetTypeKind(type) == LLVMIntegerTypeKind) {
            LLVMBuildSIToFP(builder, value, LLVMDoubleTypeInContext(context), "intToFloat")
        } else {
            value
        }
    }

    private fun getLLVMType(type: String): LLVMTypeRef = when (type) {
        "Int" -> LLVMInt32TypeInContext(context)
        "Float" -> LLVMDoubleTypeInContext(context)
        else -> error("Unsupported type: $type")
    }

    private fun createEntryBlockAlloca(name: String, type: LLVMTypeRef): LLVMValueRef {
        val entry = LLVMGetEntryBasicBlock(mainFunc)
        LLVMPositionBuilderAtEnd(builder, entry)
        return LLVMBuildAlloca(builder, type, BytePointer(*("$name\u0000".toByteArray()))).also {
            println("📥 DECLARE — $name (type=$type) -> $it")
        }
    }

    private fun buildGlobalStringPtr(str: String, name: String): LLVMValueRef {
        val strConst = LLVMConstStringInContext(context, str, str.length, 0)
        val globalVar = LLVMAddGlobal(module, LLVMTypeOf(strConst), name)
        LLVMSetInitializer(globalVar, strConst)
        LLVMSetGlobalConstant(globalVar, 1)
        LLVMSetLinkage(globalVar, LLVMPrivateLinkage)
        return LLVMBuildPointerCast(builder, globalVar, LLVMPointerType(LLVMInt8TypeInContext(context), 0), "${name}_ptr")
    }

    private fun isFloat(a: LLVMValueRef, b: LLVMValueRef): Boolean {
        val t1 = LLVMTypeOf(a)
        val t2 = LLVMTypeOf(b)
        return LLVMGetTypeKind(t1) == LLVMDoubleTypeKind || LLVMGetTypeKind(t2) == LLVMDoubleTypeKind
    }

    fun saveToFile(path: String) {
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