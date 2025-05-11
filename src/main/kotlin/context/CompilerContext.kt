package context
import codegen.data.VariableInfo
import org.bytedeco.llvm.LLVM.*

data class CompilerContext(
    val context: LLVMContextRef,
    val builder: LLVMBuilderRef,
    val module: LLVMModuleRef,
    val namedValues: MutableMap<String, VariableInfo>,
    var mainFunction: LLVMValueRef
)
