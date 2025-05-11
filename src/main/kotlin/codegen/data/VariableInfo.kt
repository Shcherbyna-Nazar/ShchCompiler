package codegen.data

import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef

data class VariableInfo(val ptr: LLVMValueRef, val type: LLVMTypeRef)
