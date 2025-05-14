package codegen

import codegen.data.FunctionSignature
import codegen.utils.LLVMUtils
import context.CompilerContext
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.global.LLVM.LLVMFunctionType
import org.bytedeco.llvm.global.LLVM.LLVMAddFunction
import shch.ShchParser

class FunctionDeclarationPass(private val ctx: CompilerContext) {
    fun declareAll(functionDecls: List<ShchParser.FunctionDeclContext>) {
        for (funcDecl in functionDecls) {
            val name = funcDecl.ID().text
            val returnType = LLVMUtils.getLLVMType(ctx, funcDecl.type().text)
            val paramTypes = funcDecl.parameters()?.parameter()?.map {
                LLVMUtils.getLLVMType(ctx, it.type().text)
            } ?: emptyList()

            val funcType = LLVMFunctionType(returnType, PointerPointer(*paramTypes.toTypedArray()), paramTypes.size, 0)
            val func = LLVMAddFunction(ctx.module, name, funcType)
            ctx.declaredFunctions[name] = FunctionSignature(func, paramTypes, returnType)

            println("🔧 Declared function '$name' with ${paramTypes.size} param(s)")
        }
    }
}
