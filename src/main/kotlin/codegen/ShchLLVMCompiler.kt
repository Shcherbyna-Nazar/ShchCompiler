package shch.codegen

import codegen.ExpressionCompiler
import codegen.StatementCompiler
import codegen.data.VariableInfo
import context.CompilerContext
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

    private val namedValues = mutableMapOf<String, VariableInfo>()

    fun compile(tree: ShchParser.ProgramContext) {
        val mainType = LLVMFunctionType(LLVMInt32TypeInContext(context), null as PointerPointer<LLVMTypeRef>?, 0, 0)
        mainFunc = LLVMAddFunction(module, "main", mainType)

        val entry = LLVMAppendBasicBlockInContext(context, mainFunc, "entry")
        LLVMPositionBuilderAtEnd(builder, entry)
        val compilerCtx = CompilerContext(context, builder, module, namedValues, mainFunc)
        val exprCompiler = ExpressionCompiler(compilerCtx)
        val stmtCompiler = StatementCompiler(compilerCtx, exprCompiler)

        try {
            for (stmt in tree.statement()) {
                val currentBB = LLVMGetInsertBlock(builder)
                val terminator = LLVMGetBasicBlockTerminator(currentBB)
                if (terminator != null && !terminator.isNull) break
                stmtCompiler.compileStatement(stmt)
            }

            val terminator = LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder))
            if (terminator == null || terminator.isNull) {
                LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0))
            }
        } catch (e: Exception) {
            println("Compilation error: ${e.message}")
            compilationFailed = true
            LLVMDeleteFunction(mainFunc)
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