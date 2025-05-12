package shch.codegen

import codegen.FunctionBodyCompiler
import codegen.FunctionDeclarationPass
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
        println("🌳 Parsed statements: ${tree.statement().size}")

        val mainType = LLVMFunctionType(LLVMInt32TypeInContext(context), null as PointerPointer<LLVMTypeRef>?, 0, 0)
        mainFunc = LLVMAddFunction(module, "main", mainType)
        val mainEntry = LLVMAppendBasicBlockInContext(context, mainFunc, "entry")
        LLVMPositionBuilderAtEnd(builder, mainEntry)

        val compilerCtx = CompilerContext(
            context, builder, module,
            namedValues,
            mainFunc,
            mutableMapOf()
        )

        try {
            val declPass = FunctionDeclarationPass(compilerCtx)
            val functionDecls = tree.children.filterIsInstance<ShchParser.FunctionDeclContext>()
            declPass.declareAll(functionDecls)

            val bodyCompiler = FunctionBodyCompiler(compilerCtx)
            bodyCompiler.compileAll(functionDecls)

            val stmtCompiler = codegen.StatementCompiler(compilerCtx, codegen.ExpressionCompiler(compilerCtx))
            LLVMPositionBuilderAtEnd(builder, mainEntry)
            for (stmt in tree.statement()) {
                val currentBB = LLVMGetInsertBlock(builder)
                if (LLVMGetBasicBlockTerminator(currentBB) != null) break
                stmtCompiler.compileStatement(stmt)
            }

            val currentBB = LLVMGetInsertBlock(builder)
            if (LLVMGetBasicBlockTerminator(currentBB) == null) {
                LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0))
                println("🔚 Appended implicit `return 0` to main")
            }

        } catch (e: Exception) {
            println("❌ Compilation error: ${e.message}")
            e.printStackTrace()
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
