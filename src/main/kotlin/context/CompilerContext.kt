package context
import codegen.data.FunctionSignature
import codegen.data.VariableInfo
import org.bytedeco.llvm.LLVM.*

class CompilerContext(
    val context: LLVMContextRef,
    val builder: LLVMBuilderRef,
    val module: LLVMModuleRef,
    val currentFunction: LLVMValueRef,
    val declaredFunctions: MutableMap<String, FunctionSignature>
) {
    private val namedValueScopes: ArrayDeque<MutableMap<String, VariableInfo>> = ArrayDeque(listOf(mutableMapOf()))

    fun enterScope() {
        namedValueScopes.add(mutableMapOf())
        println("🔹 Entered new scope (depth = ${namedValueScopes.size})")
    }

    fun exitScope() {
        if (namedValueScopes.size <= 1) {
            error("🚫 Attempt to exit global scope")
        }
        namedValueScopes.removeLast()
        println("🔸 Exited scope (depth = ${namedValueScopes.size})")
    }

    fun declare(name: String, info: VariableInfo) {
        println("📦 Declared variable '$name' in scope ${namedValueScopes.size}")
        namedValueScopes.last()[name] = info
    }

    fun lookup(name: String): VariableInfo? {
        val found = namedValueScopes.asReversed().firstNotNullOfOrNull { it[name] }
        if (found != null) {
            println("🔍 Variable '$name' found in scope")
        } else {
            println("❓ Variable '$name' not found in any scope")
        }
        return found
    }
}
