package codegen.utils

import context.CompilerContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
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


    fun buildGlobalStringPtr(
        context: LLVMContextRef,
        module: LLVMModuleRef,
        builder: LLVMBuilderRef,
        str: String,
        name: String
    ): LLVMValueRef {
        val strConst = LLVMConstStringInContext(context, str, str.length, 0)
        val globalVar = LLVMAddGlobal(module, LLVMTypeOf(strConst), name)
        LLVMSetInitializer(globalVar, strConst)
        LLVMSetGlobalConstant(globalVar, 1)
        LLVMSetLinkage(globalVar, LLVMPrivateLinkage)
        return LLVMBuildPointerCast(
            builder,
            globalVar,
            LLVMPointerType(LLVMInt8TypeInContext(context), 0),
            "${name}_ptr"
        )
    }

    fun createEntryBlockAlloca(
        builder: LLVMBuilderRef,
        function: LLVMValueRef,
        name: String,
        type: LLVMTypeRef
    ): LLVMValueRef {
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

    fun boxInt(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"] ?: error("Any type not declared")
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "boxed_int")

        val tagPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 0, "tag_ptr")
        LLVMBuildStore(
            ctx.builder,
            LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_INT.toLong(), 0),
            tagPtr
        )

        val valPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 1, "val_ptr")
        val castedPtr = LLVMBuildBitCast(
            ctx.builder,
            valPtr,
            LLVMPointerType(LLVMInt32TypeInContext(ctx.context), 0),
            "int_val_ptr"
        )
        LLVMBuildStore(ctx.builder, value, castedPtr)

        return LLVMBuildLoad2(ctx.builder, anyInfo.type, ptr, "load_boxed_int") // ✅ возвращаем значение %Any
    }

    fun unboxInt(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"] ?: error("Any type not declared")
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "unbox_int_ptr")
        LLVMBuildStore(ctx.builder, value, ptr)

        val valPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 1, "val_ptr")
        val casted = LLVMBuildBitCast(
            ctx.builder,
            valPtr,
            LLVMPointerType(LLVMInt32TypeInContext(ctx.context), 0),
            "int_val_ptr"
        )
        return LLVMBuildLoad2(ctx.builder, LLVMInt32TypeInContext(ctx.context), casted, "unboxed_int")
    }


    fun boxFloat64(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"] ?: error("Any type not declared")
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "boxed_float")

        val tagPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 0, "tag_ptr")
        LLVMBuildStore(
            ctx.builder,
            LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_FLOAT64.toLong(), 0),
            tagPtr
        )

        val valPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 1, "val_ptr")
        val castedPtr = LLVMBuildBitCast(
            ctx.builder,
            valPtr,
            LLVMPointerType(LLVMDoubleTypeInContext(ctx.context), 0),
            "float_val_ptr"
        )
        LLVMBuildStore(ctx.builder, value, castedPtr)

        return LLVMBuildLoad2(ctx.builder, anyInfo.type, ptr, "load_boxed_float") // ✅ возвращаем значение %Any
    }

    fun unboxFloat64(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"] ?: error("Any type not declared")
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "unbox_float_ptr")
        LLVMBuildStore(ctx.builder, value, ptr)

        val valPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 1, "val_ptr")
        val casted = LLVMBuildBitCast(
            ctx.builder,
            valPtr,
            LLVMPointerType(LLVMDoubleTypeInContext(ctx.context), 0),
            "float_val_ptr"
        )
        return LLVMBuildLoad2(ctx.builder, LLVMDoubleTypeInContext(ctx.context), casted, "unboxed_float")
    }

    fun boxBool(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"] ?: error("Any type not declared")
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "boxed_bool")

        val tagPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 0, "tag_ptr")
        LLVMBuildStore(
            ctx.builder,
            LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_BOOL.toLong(), 0),
            tagPtr
        )

        val valPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 1, "val_ptr")
        val castedPtr = LLVMBuildBitCast(
            ctx.builder,
            valPtr,
            LLVMPointerType(LLVMInt1TypeInContext(ctx.context), 0),
            "bool_val_ptr"
        )
        LLVMBuildStore(ctx.builder, value, castedPtr)

        return LLVMBuildLoad2(ctx.builder, anyInfo.type, ptr, "load_boxed_bool") // ✅ возвращаем значение %Any
    }

    fun unboxBool(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"] ?: error("Any type not declared")
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "unbox_bool_ptr")
        LLVMBuildStore(ctx.builder, value, ptr)

        val valPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 1, "val_ptr")
        val casted = LLVMBuildBitCast(
            ctx.builder,
            valPtr,
            LLVMPointerType(LLVMInt1TypeInContext(ctx.context), 0),
            "bool_val_ptr"
        )
        return LLVMBuildLoad2(ctx.builder, LLVMInt1TypeInContext(ctx.context), casted, "unboxed_bool")
    }

    fun boxString(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"]!!
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "boxed_string")

        val tagPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 0, "tag_ptr")
        LLVMBuildStore(
            ctx.builder,
            LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_STRING.toLong(), 0),
            tagPtr
        )

        val valPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 1, "val_ptr")
        LLVMBuildStore(ctx.builder, value, valPtr)

        return LLVMBuildLoad2(ctx.builder, anyInfo.type, ptr, "load_boxed_string")
    }

    fun unboxString(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"]!!
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "unbox_string_ptr")
        LLVMBuildStore(ctx.builder, value, ptr)

        val valPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 1, "val_ptr")
        return LLVMBuildLoad2(
            ctx.builder,
            LLVMPointerType(LLVMInt8TypeInContext(ctx.context), 0),
            valPtr,
            "unboxed_string"
        )
    }


    fun getTag(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val anyInfo = ctx.declaredStructs["Any"] ?: error("Any type not declared")
        val ptr = LLVMBuildAlloca(ctx.builder, anyInfo.type, "tag_ptr")
        LLVMBuildStore(ctx.builder, value, ptr)

        val tagPtr = LLVMBuildStructGEP2(ctx.builder, anyInfo.type, ptr, 0, "tag_gep")
        return LLVMBuildLoad2(ctx.builder, LLVMInt32TypeInContext(ctx.context), tagPtr, "tag_val")
    }

    fun isAnyType(ctx: CompilerContext, type: LLVMTypeRef): Boolean {
        return ctx.declaredStructs["Any"]?.type == type
    }

    fun autoUnboxToDouble(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val tag = getTag(ctx, value)
        val tagInt = LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_INT.toLong(), 0)
        val tagFloat = LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_FLOAT64.toLong(), 0)

        val isInt = LLVMBuildICmp(ctx.builder, LLVMIntEQ, tag, tagInt, "is_int")
        val currentBlock = LLVMGetInsertBlock(ctx.builder)
        val parentFunction = LLVMGetBasicBlockParent(currentBlock)

        val intBlock = LLVMAppendBasicBlockInContext(ctx.context, parentFunction, "auto_unbox_int")
        val floatBlock = LLVMAppendBasicBlockInContext(ctx.context, parentFunction, "auto_unbox_float")
        val mergeBlock = LLVMAppendBasicBlockInContext(ctx.context, parentFunction, "auto_unbox_merge")

        LLVMBuildCondBr(ctx.builder, isInt, intBlock, floatBlock)

        // int path
        LLVMPositionBuilderAtEnd(ctx.builder, intBlock)
        val unboxedInt = unboxInt(ctx, value)
        val promotedInt =
            LLVMBuildSIToFP(ctx.builder, unboxedInt, LLVMDoubleTypeInContext(ctx.context), "int_to_double")
        LLVMBuildBr(ctx.builder, mergeBlock)
        val intEnd = LLVMGetInsertBlock(ctx.builder)

        // float path
        LLVMPositionBuilderAtEnd(ctx.builder, floatBlock)
        val unboxedFloat = unboxFloat64(ctx, value)
        LLVMBuildBr(ctx.builder, mergeBlock)
        val floatEnd = LLVMGetInsertBlock(ctx.builder)

        // merge + phi
        LLVMPositionBuilderAtEnd(ctx.builder, mergeBlock)
        val phi = LLVMBuildPhi(ctx.builder, LLVMDoubleTypeInContext(ctx.context), "auto_unbox_phi")
        LLVMAddIncoming(phi, PointerPointer(promotedInt, unboxedFloat), PointerPointer(intEnd, floatEnd), 2)

        return phi
    }


    fun autoUnboxToInt(ctx: CompilerContext, value: LLVMValueRef): LLVMValueRef {
        val tag = getTag(ctx, value)
        val tagBool = LLVMConstInt(LLVMInt32TypeInContext(ctx.context), TypeTags.TAG_BOOL.toLong(), 0)

        val isBool = LLVMBuildICmp(ctx.builder, LLVMIntEQ, tag, tagBool, "is_bool")
        val currentBlock = LLVMGetInsertBlock(ctx.builder)
        val parentFunction = LLVMGetBasicBlockParent(currentBlock)

        val boolBlock = LLVMAppendBasicBlockInContext(ctx.context, parentFunction, "unbox_bool")
        val intBlock = LLVMAppendBasicBlockInContext(ctx.context, parentFunction, "unbox_int")
        val mergeBlock = LLVMAppendBasicBlockInContext(ctx.context, parentFunction, "unbox_merge")

        LLVMBuildCondBr(ctx.builder, isBool, boolBlock, intBlock)

        LLVMPositionBuilderAtEnd(ctx.builder, boolBlock)
        val boolVal = unboxBool(ctx, value)
        val zext = LLVMBuildZExt(ctx.builder, boolVal, LLVMInt32TypeInContext(ctx.context), "bool_to_int")
        LLVMBuildBr(ctx.builder, mergeBlock)
        val boolEnd = LLVMGetInsertBlock(ctx.builder)

        LLVMPositionBuilderAtEnd(ctx.builder, intBlock)
        val intVal = unboxInt(ctx, value)
        LLVMBuildBr(ctx.builder, mergeBlock)
        val intEnd = LLVMGetInsertBlock(ctx.builder)

        LLVMPositionBuilderAtEnd(ctx.builder, mergeBlock)
        val phi = LLVMBuildPhi(ctx.builder, LLVMInt32TypeInContext(ctx.context), "phi_int")
        LLVMAddIncoming(phi, PointerPointer(zext, intVal), PointerPointer(boolEnd, intEnd), 2)

        return phi
    }


}