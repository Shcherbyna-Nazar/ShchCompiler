package shch.codegen

import codegen.ExpressionCompiler
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


            val structDeclPass = StructDeclarationPass(compilerCtx)
            structDeclPass.declareBuiltinAnyStruct()  // ← ADD THIS LINE FIRST!
            val structDecls = tree.children.filterIsInstance<ShchParser.StructDeclContext>()
            structDeclPass.declareAll(structDecls)


            // 🌍 GLOBAL VARIABLE DECLARATIONS
            val globalVarDecls = tree.statement().mapNotNull { it.varDecl() }
            for (decl in globalVarDecls) {
                val name = decl.ID().text
                val type = LLVMUtils.getLLVMType(compilerCtx, decl.type().text)


                val isAny = LLVMUtils.isAnyType(compilerCtx, type)
                val initializer = if (isAny) LLVMGetUndef(type) else {
                    decl.expr()?.let {
                        val const = ExpressionCompiler(compilerCtx).compileExpr(it)
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
                }

                val global = LLVMAddGlobal(module, type, name)
                LLVMSetInitializer(global, initializer)
                compilerCtx.declare(name, VariableInfo(global, type))
                println("🌍 Global variable '$name' declared and initialized")


            }


            // 1️⃣ Funkcje - deklaracje
            val declPass = FunctionDeclarationPass(compilerCtx)
            val functionDecls = tree.children.filterIsInstance<ShchParser.FunctionDeclContext>()
            declPass.declareAll(functionDecls)

            // 2️⃣ Funkcje - ciała
            val bodyCompiler = FunctionBodyCompiler(compilerCtx)
            bodyCompiler.compileAll(functionDecls)

            // 2️⃣ Runtime assignment of Any global variables
            for (decl in globalVarDecls) {
                if (LLVMUtils.isAnyType(compilerCtx, LLVMUtils.getLLVMType(compilerCtx, decl.type().text))) {
                    val exprValue = ExpressionCompiler(compilerCtx).compileExpr(decl.expr()!!)
                    val varInfo = compilerCtx.lookup(decl.ID().text)!!

                    val boxedValue = when (LLVMGetTypeKind(LLVMTypeOf(exprValue))) {
                        LLVMIntegerTypeKind -> {
                            if (LLVMGetIntTypeWidth(LLVMTypeOf(exprValue)) == 1)
                                LLVMUtils.boxBool(compilerCtx, exprValue)
                            else
                                LLVMUtils.boxInt(compilerCtx, exprValue)
                        }

                        LLVMDoubleTypeKind -> LLVMUtils.boxFloat64(compilerCtx, exprValue)
                        LLVMPointerTypeKind -> LLVMUtils.boxString(compilerCtx, exprValue)  // ✅ добавь это

                        else -> error(
                            "Cannot box unsupported type into Any: ${
                                LLVMPrintTypeToString(
                                    LLVMTypeOf(
                                        exprValue
                                    )
                                ).string
                            }"
                        )
                    }

                    LLVMBuildStore(builder, boxedValue, varInfo.ptr)
                    println("📤 Assigned runtime boxed value to global '${decl.ID().text}'")

                }
            }


            // 3️⃣ main body
            val stmtCompiler = codegen.StatementCompiler(compilerCtx, ExpressionCompiler(compilerCtx))
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
