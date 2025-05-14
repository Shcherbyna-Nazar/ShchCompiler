package codegen.data

import org.bytedeco.llvm.LLVM.LLVMTypeRef

data class StructInfo(
    val name: String,
    val type: LLVMTypeRef,
    val fields: Map<String, Int>, // field name → index
    val fieldTypes: List<LLVMTypeRef>
)