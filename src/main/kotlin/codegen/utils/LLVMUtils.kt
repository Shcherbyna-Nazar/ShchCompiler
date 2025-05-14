package codegen.utils

import context.CompilerContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

object LLVMUtils {
    fun getLLVMType(ctx: CompilerContext, type: String): LLVMTypeRef = when (type) {
        "Int" -> LLVMInt32TypeInContext(ctx.context)
        "Float32" -> LLVMFloatTypeInContext(ctx.context)     // ← nowy typ
        "Float64", "Float" -> LLVMDoubleTypeInContext(ctx.context)
        "String" -> LLVMPointerType(LLVMInt8TypeInContext(ctx.context), 0)
        "Boolean" -> LLVMInt1TypeInContext(ctx.context)
        "Void" -> LLVMVoidTypeInContext(ctx.context)  // ✅ ← this line
        else -> ctx.declaredStructs[type]?.type ?: error("Unsupported or unknown type: $type")
    }

    fun isFloat(a: LLVMValueRef, b: LLVMValueRef): Boolean {
        val t1 = LLVMTypeOf(a)
        val t2 = LLVMTypeOf(b)
        return LLVMGetTypeKind(t1) in setOf(LLVMFloatTypeKind, LLVMDoubleTypeKind) ||
                LLVMGetTypeKind(t2) in setOf(LLVMFloatTypeKind, LLVMDoubleTypeKind)
    }


    fun promoteToFloat(targetType: LLVMTypeRef, value: LLVMValueRef, builder: LLVMBuilderRef): LLVMValueRef {
        val valType = LLVMTypeOf(value)
        return when {
            LLVMGetTypeKind(valType) == LLVMIntegerTypeKind ->
                LLVMBuildSIToFP(builder, value, targetType, "intToFloat")

            LLVMGetTypeKind(valType) == LLVMFloatTypeKind &&
                    LLVMGetTypeKind(targetType) == LLVMDoubleTypeKind ->
                LLVMBuildFPExt(builder, value, targetType, "fpext")

            LLVMGetTypeKind(valType) == LLVMDoubleTypeKind &&
                    LLVMGetTypeKind(targetType) == LLVMFloatTypeKind ->
                LLVMBuildFPTrunc(builder, value, targetType, "fptrunc")

            else -> value
        }
    }


    fun buildGlobalStringPtr(context: LLVMContextRef, module: LLVMModuleRef, builder: LLVMBuilderRef, str: String, name: String): LLVMValueRef {
        val strConst = LLVMConstStringInContext(context, str, str.length, 0)
        val globalVar = LLVMAddGlobal(module, LLVMTypeOf(strConst), name)
        LLVMSetInitializer(globalVar, strConst)
        LLVMSetGlobalConstant(globalVar, 1)
        LLVMSetLinkage(globalVar, LLVMPrivateLinkage)
        return LLVMBuildPointerCast(builder, globalVar, LLVMPointerType(LLVMInt8TypeInContext(context), 0), "${name}_ptr")
    }

    fun createEntryBlockAlloca(builder: LLVMBuilderRef, function: LLVMValueRef, name: String, type: LLVMTypeRef): LLVMValueRef {
        val entry = LLVMGetEntryBasicBlock(function)
        LLVMPositionBuilderAtEnd(builder, entry)
        return LLVMBuildAlloca(builder, type, BytePointer(*("$name\u0000".toByteArray())))
    }

    fun boolToInt(
        value: LLVMValueRef,
        builder: LLVMBuilderRef,
        context: LLVMContextRef
    ): LLVMValueRef {
        return LLVMBuildZExt(builder, value, LLVMInt32TypeInContext(context), "booltoint")
    }

}