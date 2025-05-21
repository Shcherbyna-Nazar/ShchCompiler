package codegen

import codegen.data.StructInfo
import codegen.utils.LLVMUtils
import context.CompilerContext
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
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

    fun declareBuiltinAnyStruct() {
        val tagType = LLVMInt32TypeInContext(ctx.context) // for runtime type tag
        val valueType = LLVMArrayType(LLVMInt8TypeInContext(ctx.context), 8) // raw storage, like union (64 bits)

        val anyStructType = LLVMStructCreateNamed(ctx.context, "Any")
        LLVMStructSetBody(anyStructType, PointerPointer(tagType, valueType), 2, 0)

        ctx.declaredStructs["Any"] = StructInfo(
            name = "Any",
            type = anyStructType,
            fields = mapOf("tag" to 0, "value" to 1),
            fieldTypes = listOf(tagType, valueType)
        )

        println("🔧 Built-in dynamic struct 'Any' registered")
    }

}
