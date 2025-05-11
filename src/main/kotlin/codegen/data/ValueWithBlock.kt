package codegen.data

import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMValueRef

data class ValueWithBlock(val value: LLVMValueRef, val block: LLVMBasicBlockRef)
