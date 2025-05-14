package shch.codegen

import codegen.FunctionBodyCompiler
import codegen.FunctionDeclarationPass
import codegen.StructDeclarationPass
import codegen.data.VariableInfo
import codegen.utils.LLVMUtils
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
            mutableMapOf()
        )

        try {
            compilerCtx.enterScope()

            // 🌍 GLOBAL VARIABLE DECLARATIONS
            val globalVarDecls = tree.statement().mapNotNull { it.varDecl() }
            for (decl in globalVarDecls) {
                val name = decl.ID().text
                val type = LLVMUtils.getLLVMType(compilerCtx, decl.type().text)

                val initialValue = decl.expr()?.let {
                    val const = codegen.ExpressionCompiler(compilerCtx).compileExpr(it)
                    if (LLVMIsConstant(const) == 0) {
                        error("Global variable '$name' must be initialized with a constant value")
                    }
                    const
                } ?: when (LLVMGetTypeKind(type)) {
                    LLVMIntegerTypeKind -> LLVMConstInt(type, 0, 0)
                    LLVMDoubleTypeKind -> LLVMConstReal(type, 0.0)
                    LLVMPointerTypeKind -> LLVMConstNull(type)
                    else -> error("Unsupported type for global variable '$name'")
                }

                val global = LLVMAddGlobal(module, type, name)
                LLVMSetInitializer(global, initialValue)
                compilerCtx.declare(name, VariableInfo(global, type))
                println("🌍 Global variable '$name' declared and initialized")
            }

            val structDeclPass = StructDeclarationPass(compilerCtx)
            val structDecls = tree.children.filterIsInstance<ShchParser.StructDeclContext>()
            structDeclPass.declareAll(structDecls)

            // 1️⃣ Funkcje - deklaracje
            val declPass = FunctionDeclarationPass(compilerCtx)
            val functionDecls = tree.children.filterIsInstance<ShchParser.FunctionDeclContext>()
            declPass.declareAll(functionDecls)

            // 2️⃣ Funkcje - ciała
            val bodyCompiler = FunctionBodyCompiler(compilerCtx)
            bodyCompiler.compileAll(functionDecls)

            // 3️⃣ main body
            val stmtCompiler = codegen.StatementCompiler(compilerCtx, codegen.ExpressionCompiler(compilerCtx))
            LLVMPositionBuilderAtEnd(builder, mainEntry)
            for (stmt in tree.statement()) {
                // ❌ pomijamy globalne varDecl (już skompilowane)
                if (stmt.varDecl() != null) continue

                val currentBB = LLVMGetInsertBlock(builder)
                if (LLVMGetBasicBlockTerminator(currentBB) != null) break
                stmtCompiler.compileStatement(stmt)
            }

            // 4️⃣ Domknięcie main
            val currentBB = LLVMGetInsertBlock(builder)
            if (LLVMGetBasicBlockTerminator(currentBB) == null) {
                LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0))
                println("🔚 Appended implicit `return 0` to main")
            }

            compilerCtx.exitScope()

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
