package codegen

import codegen.data.VariableInfo
import codegen.utils.LLVMUtils
import context.CompilerContext
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import shch.ShchParser

class FunctionBodyCompiler(private val ctx: CompilerContext) {
    fun compileAll(functionDecls: List<ShchParser.FunctionDeclContext>) {
        for (funcDecl in functionDecls) {
            val name = funcDecl.ID().text
            val signature = ctx.declaredFunctions[name] ?: error("Function '$name' not declared")
            val function = signature.function

            val entry = LLVMAppendBasicBlockInContext(ctx.context, function, "entry")
            LLVMPositionBuilderAtEnd(ctx.builder, entry)
            ctx.enterScope()


            funcDecl.parameters()?.parameter()?.forEachIndexed { i, param ->
                val paramName = param.ID().text
                val paramType = signature.paramTypes[i]
                val llvmParam = LLVMGetParam(function, i)
                val alloca = LLVMUtils.createEntryBlockAlloca(ctx.builder, function, paramName, paramType)
                LLVMBuildStore(ctx.builder, llvmParam, alloca)
                ctx.declare(paramName, VariableInfo(alloca, paramType))
            }

            val stmtCompiler = StatementCompiler(ctx, ExpressionCompiler(ctx))
            val hasTerminator = stmtCompiler.compileBlock(funcDecl.block())

            if (!hasTerminator) {
                when (LLVMGetTypeKind(signature.returnType)) {
                    LLVMVoidTypeKind -> LLVMBuildRetVoid(ctx.builder)
                    LLVMIntegerTypeKind -> LLVMBuildRet(ctx.builder, LLVMConstInt(signature.returnType, 0, 0))
                    LLVMDoubleTypeKind -> LLVMBuildRet(ctx.builder, LLVMConstReal(signature.returnType, 0.0))
                    else -> error("Unsupported return type for function '$name'")
                }
                println("🔚 Implicit return added to function '$name'")
            }

            println("✅ Function '$name' compiled")
            ctx.exitScope()
        }
    }
}
