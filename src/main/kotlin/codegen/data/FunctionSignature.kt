package codegen.data

import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef

data class FunctionSignature(
    val function: LLVMValueRef,
    val paramTypes: List<LLVMTypeRef>,
    val returnType: LLVMTypeRef
)
