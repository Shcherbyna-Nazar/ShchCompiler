package codegen

import codegen.data.StructInfo
import codegen.utils.LLVMUtils
import context.CompilerContext
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.LLVMStructCreateNamed
import org.bytedeco.llvm.global.LLVM.LLVMStructSetBody
import shch.ShchParser

class StructDeclarationPass(private val ctx: CompilerContext) {
    fun declareAll(structDecls: List<ShchParser.StructDeclContext>) {
        for (decl in structDecls) {
            val name = decl.ID().text
            val fieldTypes = mutableListOf<LLVMTypeRef>()
            val fieldIndices = mutableMapOf<String, Int>()

            decl.structField().forEachIndexed { i, field ->
                val type = LLVMUtils.getLLVMType(ctx, field.type().text)
                val fieldName = field.ID().text
                fieldTypes.add(type)
                fieldIndices[fieldName] = i
            }

            val llvmStructType = LLVMStructCreateNamed(ctx.context, name)
            LLVMStructSetBody(llvmStructType, PointerPointer(*fieldTypes.toTypedArray()), fieldTypes.size, 0)

            ctx.declaredStructs[name] = StructInfo(name, llvmStructType, fieldIndices, fieldTypes)
            println("🏗️ Struct '$name' declared with ${fieldTypes.size} field(s)")
        }
    }
}
