package codegen

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

object LLVMUtils {
    fun getLLVMType(context: LLVMContextRef, type: String): LLVMTypeRef = when (type) {
        "Int" -> LLVMInt32TypeInContext(context)
        "Float" -> LLVMDoubleTypeInContext(context)
        "String" -> LLVMPointerType(LLVMInt8TypeInContext(context), 0)
        else -> error("Unsupported type: $type")
    }

    fun isFloat(a: LLVMValueRef, b: LLVMValueRef): Boolean {
        val t1 = LLVMTypeOf(a)
        val t2 = LLVMTypeOf(b)
        return LLVMGetTypeKind(t1) == LLVMDoubleTypeKind || LLVMGetTypeKind(t2) == LLVMDoubleTypeKind
    }

    fun promoteToFloat(builder: LLVMBuilderRef, value: LLVMValueRef, context: LLVMContextRef): LLVMValueRef {
        val type = LLVMTypeOf(value)
        return if (LLVMGetTypeKind(type) == LLVMIntegerTypeKind)
            LLVMBuildSIToFP(builder, value, LLVMDoubleTypeInContext(context), "intToFloat")
        else
            value
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

    fun asBoolean(builder: LLVMBuilderRef, value: LLVMValueRef): LLVMValueRef {
        val kind = LLVMGetTypeKind(LLVMTypeOf(value))
        return when (kind) {
            LLVMIntegerTypeKind -> {
                // Compare the integer to 0 => yields an i1
                LLVMBuildICmp(builder, LLVMIntNE,
                    value,
                    LLVMConstInt(LLVMTypeOf(value), 0, 0),
                    "tobool")
            }
            LLVMDoubleTypeKind -> {
                // Compare the double to 0.0 => yields an i1
                LLVMBuildFCmp(builder, LLVMRealUNE,
                    value,
                    LLVMConstReal(LLVMTypeOf(value), 0.0),
                    "tobool")
            }
            // If it's already i1, you could just return it
            // If it's a pointer/string, you might do NE against null
            else -> error("Unsupported type for boolean conversion")
        }
    }
    fun boolToInt(
        value: LLVMValueRef,
        builder: LLVMBuilderRef,
        context: LLVMContextRef
    ): LLVMValueRef {
        return LLVMBuildZExt(builder, value, LLVMInt32TypeInContext(context), "booltoint")
    }

}