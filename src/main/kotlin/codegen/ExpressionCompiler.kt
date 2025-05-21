package codegen

import codegen.data.ValueWithBlock
import codegen.utils.LLVMUtils
import codegen.utils.LLVMUtils.boolToInt
import codegen.utils.LLVMUtils.buildGlobalStringPtr
import codegen.utils.LLVMUtils.promoteToFloat
import context.CompilerContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import shch.ShchParser

class ExpressionCompiler(private val ctx: CompilerContext) {

    fun compileExpr(expr: ShchParser.ExprContext): LLVMValueRef {
        println("📦 Expression: ${expr.text}")

        if (expr.expr()?.size == 1 && expr.ID() != null && expr.getChildCount() == 3 && expr.getChild(1).text == ".") {
            val baseExpr = expr.expr(0)
            val baseValue = compileExpr(baseExpr)  // This LOADS the struct
            val baseType = LLVMTypeOf(baseValue)

            // You must handle the struct VALUE type here (not a pointer!)
            val structInfo = ctx.declaredStructs.values.find { it.type == baseType }
                ?: error("Unknown struct for field access: ${baseType.address()}")

            val fieldName = expr.ID().text
            val fieldIndex = structInfo.fields[fieldName]
                ?: error("Field '$fieldName' not found in struct '${structInfo.name}'")

            // To access a field from a value, store it in a temporary alloca first
            val tmpPtr = LLVMBuildAlloca(ctx.builder, structInfo.type, "tmp_struct")
            LLVMBuildStore(ctx.builder, baseValue, tmpPtr)

            val gep = LLVMBuildStructGEP2(ctx.builder, structInfo.type, tmpPtr, fieldIndex, "get_$fieldName")
            return LLVMBuildLoad2(ctx.builder, structInfo.fieldTypes[fieldIndex], gep, "load_$fieldName")
        }

        if (expr.ID() != null && expr.expr().isNotEmpty()) {
            val structInfo = ctx.declaredStructs[expr.ID().text]
            if (structInfo != null) {
                val args = expr.expr().map { compileExpr(it) }
                if (args.size != structInfo.fieldTypes.size) {
                    error("Struct '${structInfo.name}' expects ${structInfo.fieldTypes.size} fields, got ${args.size}")
                }

                val ptr = LLVMBuildAlloca(ctx.builder, structInfo.type, "tmp_${structInfo.name}")
                for ((i, arg) in args.withIndex()) {
                    val fieldPtr = LLVMBuildStructGEP2(ctx.builder, structInfo.type, ptr, i, "fld$i")
                    LLVMBuildStore(ctx.builder, arg, fieldPtr)
                }

                // ❗ Return the VALUE, not the pointer
                return LLVMBuildLoad2(ctx.builder, structInfo.type, ptr, "load_struct")
            }
        }


        if (expr.ID() != null && expr.assign == null && expr.getChildCount() >= 3 && expr.getChild(1).text == "(") {
            val funcName = expr.ID().text
            val signature = ctx.declaredFunctions[funcName]
                ?: error("Function '$funcName' not declared")

            val args = expr.expr().map { compileExpr(it) }
            if (args.size != signature.paramTypes.size) {
                error("Function '$funcName' expects ${signature.paramTypes.size} arguments, got ${args.size}")
            }

            val argArray = PointerPointer(*args.toTypedArray())

            val funcType = LLVMFunctionType(
                signature.returnType,
                PointerPointer(*signature.paramTypes.toTypedArray()),
                signature.paramTypes.size,
                0
            )

            val result = LLVMBuildCall2(
                ctx.builder,
                funcType,
                signature.function,
                argArray,
                args.size,
                if (LLVMGetTypeKind(signature.returnType) == LLVMVoidTypeKind) "" else "call_$funcName"
            )

            println("📞 Called function '$funcName' with ${args.size} argument(s)")
            return result
        }


        if (expr.assign != null) {
            val name = expr.ID().text
            val varInfo = ctx.lookup(name) ?: error("Variable '${expr.ID().text}' not declared")

            val value = compileExpr(expr.expr(0))
            val finalValue = if (LLVMUtils.isAnyType(ctx, varInfo.type)) {
                // 🔁 Box value
                val valType = LLVMTypeOf(value)
                when {
                    LLVMGetTypeKind(valType) == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(valType) == 1 ->
                        LLVMUtils.boxBool(ctx, value)

                    LLVMGetTypeKind(valType) == LLVMIntegerTypeKind ->
                        LLVMUtils.boxInt(ctx, value)

                    LLVMGetTypeKind(valType) == LLVMDoubleTypeKind ->
                        LLVMUtils.boxFloat64(ctx, value)

                    else -> error("Cannot box unsupported type into Any: ${LLVMPrintTypeToString(valType).string}")
                }

            } else value

            LLVMBuildStore(ctx.builder, finalValue, varInfo.ptr)
            return finalValue
        }



        if (expr.op?.text == "&&" || expr.op?.text == "||") {
            return compileCondExpr(expr)
        }
        if (expr.sign?.text == "-") {
            val value = compileExpr(expr.expr(0))
            val type = LLVMTypeOf(value)
            return when (LLVMGetTypeKind(type)) {
                LLVMDoubleTypeKind -> LLVMBuildFNeg(ctx.builder, value, "fnegtmp")
                LLVMIntegerTypeKind -> LLVMBuildNeg(ctx.builder, value, "inegtmp")
                else -> error("Unsupported type for unary minus")
            }
        }

        return when {
            expr.TRUE() != null -> LLVMConstInt(LLVMInt1TypeInContext(ctx.context), 1, 0)

            expr.FALSE() != null -> LLVMConstInt(LLVMInt1TypeInContext(ctx.context), 0, 0)

            expr.not != null && expr.not.text == "!" -> {
                val value = compileCondExpr(expr.expr(0))
                val valueType = LLVMTypeOf(value)


                val i1Value = when {
                    LLVMGetTypeKind(valueType) == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(valueType) == 1 -> value
                    LLVMGetTypeKind(valueType) == LLVMIntegerTypeKind ->
                        LLVMBuildICmp(ctx.builder, LLVMIntNE, value, LLVMConstInt(valueType, 0, 0), "boolify")

                    LLVMGetTypeKind(valueType) == LLVMDoubleTypeKind ->
                        LLVMBuildFCmp(ctx.builder, LLVMRealUNE, value, LLVMConstReal(valueType, 0.0), "boolify")

                    else -> error("Unsupported type for '!' operator: $valueType")
                }

                val result =
                    LLVMBuildXor(ctx.builder, i1Value, LLVMConstInt(LLVMInt1TypeInContext(ctx.context), 1, 0), "nottmp")
                result
            }


            expr.op != null && expr.op.text in setOf("&", "|", "^") -> {
                val left = compileCondExpr(expr.expr(0))
                val right = compileCondExpr(expr.expr(1))


                val result = when (expr.op.text) {
                    "&" -> LLVMBuildAnd(ctx.builder, left, right, "andtmp")
                    "|" -> LLVMBuildOr(ctx.builder, left, right, "ortmp")
                    "^" -> LLVMBuildXor(ctx.builder, left, right, "xortmp")
                    else -> error("Unknown boolean operator: ${expr.op.text}")
                }

                result
            }

            expr.NUMBER() != null -> {
                val text = expr.NUMBER().text
                return when {
                    text.endsWith("f", true) -> LLVMConstReal(
                        LLVMFloatTypeInContext(ctx.context),
                        text.dropLast(1).toFloat().toDouble()
                    )

                    text.contains('.') -> LLVMConstReal(LLVMDoubleTypeInContext(ctx.context), text.toDouble())
                    else -> LLVMConstInt(LLVMInt32TypeInContext(ctx.context), text.toLong(), 0)
                }
            }

            expr.ID() != null -> {
                val varInfo = ctx.lookup(expr.ID().text) ?: error("Variable '${expr.ID().text}' not declared")
                LLVMBuildLoad2(
                    ctx.builder,
                    varInfo.type,
                    varInfo.ptr,
                    BytePointer(*("${expr.ID().text}\u0000".toByteArray()))
                )
            }

            expr.STRING() != null -> {
                val raw = expr.STRING().text
                val text = raw.substring(1, raw.length - 1)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"") + "\u0000"  // ⬅️ explicitly add null terminator
                buildGlobalStringPtr(ctx.context, ctx.module, ctx.builder, text, "strtmp")
            }

            expr.op != null -> {
                var left = compileExpr(expr.expr(0))
                var right = compileExpr(expr.expr(1))

                if (LLVMUtils.isAnyType(ctx, LLVMTypeOf(left))) {
                    left = LLVMUtils.autoUnboxToDouble(ctx, left)
                }
                if (LLVMUtils.isAnyType(ctx, LLVMTypeOf(right))) {
                    right = LLVMUtils.autoUnboxToDouble(ctx, right)
                }


                val lType = LLVMTypeOf(left)
                val rType = LLVMTypeOf(right)

                val lKind = LLVMGetTypeKind(lType)
                val rKind = LLVMGetTypeKind(rType)

                val isFloatOp = lKind in setOf(LLVMFloatTypeKind, LLVMDoubleTypeKind) ||
                        rKind in setOf(LLVMFloatTypeKind, LLVMDoubleTypeKind)

                if (isFloatOp) {
                    // Determine common float type
                    val targetType = if (lKind == LLVMDoubleTypeKind || rKind == LLVMDoubleTypeKind)
                        LLVMDoubleTypeInContext(ctx.context)
                    else
                        LLVMFloatTypeInContext(ctx.context)

                    val l = promoteToFloat(targetType, left, ctx.builder)
                    val r = promoteToFloat(targetType, right, ctx.builder)

                    return when (expr.op.text) {
                        "+" -> LLVMBuildFAdd(ctx.builder, l, r, "faddtmp")
                        "-" -> LLVMBuildFSub(ctx.builder, l, r, "fsubtmp")
                        "*" -> LLVMBuildFMul(ctx.builder, l, r, "fmultmp")
                        "/" -> LLVMBuildFDiv(ctx.builder, l, r, "fdivtmp")
                        "==" -> LLVMBuildFCmp(ctx.builder, LLVMRealOEQ, l, r, "cmptmp")
                        "!=" -> LLVMBuildFCmp(ctx.builder, LLVMRealUNE, l, r, "cmptmp")
                        "<" -> LLVMBuildFCmp(ctx.builder, LLVMRealOLT, l, r, "cmptmp")
                        "<=" -> LLVMBuildFCmp(ctx.builder, LLVMRealOLE, l, r, "cmptmp")
                        ">" -> LLVMBuildFCmp(ctx.builder, LLVMRealOGT, l, r, "cmptmp")
                        ">=" -> LLVMBuildFCmp(ctx.builder, LLVMRealOGE, l, r, "cmptmp")
                        else -> error("Unsupported float operator: ${expr.op.text}")
                    }
                } else {
                    // Possibly bool to int promotion
                    val l = if (lKind == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(lType) == 1)
                        boolToInt(left, ctx.builder, ctx.context) else left
                    val r = if (rKind == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(rType) == 1)
                        boolToInt(right, ctx.builder, ctx.context) else right

                    return when (expr.op.text) {
                        "+" -> LLVMBuildAdd(ctx.builder, l, r, "addtmp")
                        "-" -> LLVMBuildSub(ctx.builder, l, r, "subtmp")
                        "*" -> LLVMBuildMul(ctx.builder, l, r, "multmp")
                        "/" -> LLVMBuildSDiv(ctx.builder, l, r, "divtmp")
                        "%" -> LLVMBuildSRem(ctx.builder, l, r, "modtmp")
                        "==" -> LLVMBuildICmp(ctx.builder, LLVMIntEQ, l, r, "cmptmp")
                        "!=" -> LLVMBuildICmp(ctx.builder, LLVMIntNE, l, r, "cmptmp")
                        "<" -> LLVMBuildICmp(ctx.builder, LLVMIntSLT, l, r, "cmptmp")
                        "<=" -> LLVMBuildICmp(ctx.builder, LLVMIntSLE, l, r, "cmptmp")
                        ">" -> LLVMBuildICmp(ctx.builder, LLVMIntSGT, l, r, "cmptmp")
                        ">=" -> LLVMBuildICmp(ctx.builder, LLVMIntSGE, l, r, "cmptmp")
                        else -> error("Unsupported integer operator: ${expr.op.text}")
                    }
                }
            }

            else -> {
                if (expr.expr().size == 1) {
                    val subExpr = expr.expr(0)
                    if (subExpr.op?.text == "&&" || subExpr.op?.text == "||") {
                        return compileCondExpr(subExpr)
                    }
                    return compileExpr(subExpr)
                }
                error("Unhandled expression: ${expr.text}")
            }

        }
    }

    fun compileCondExpr(expr: ShchParser.ExprContext): LLVMValueRef {
        return compileCondExprWithBlock(expr).value
    }

    fun compileCondExprWithBlock(expr: ShchParser.ExprContext): ValueWithBlock {
        if (expr.op?.text == "&&" || expr.op?.text == "||") {
            return compileShortCircuit(expr)
        }

        val value = compileExpr(expr)
        val type = LLVMTypeOf(value)

        val unboxed = if (LLVMUtils.isAnyType(ctx, type)) {
            LLVMUtils.unboxBool(ctx, value) // ← TEMPORARY
        } else value

        val unboxedType = LLVMTypeOf(unboxed)

        val result = when {
            LLVMGetTypeKind(unboxedType) == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(unboxedType) == 1 -> unboxed
            LLVMGetTypeKind(unboxedType) == LLVMIntegerTypeKind -> LLVMBuildICmp(
                ctx.builder,
                LLVMIntNE,
                unboxed,
                LLVMConstInt(unboxedType, 0, 0),
                "boolify"
            )

            LLVMGetTypeKind(unboxedType) == LLVMFloatTypeKind || LLVMGetTypeKind(unboxedType) == LLVMDoubleTypeKind  -> {
                val zero = if (LLVMGetTypeKind(unboxedType) == LLVMFloatTypeKind)
                    LLVMConstReal(LLVMFloatTypeInContext(ctx.context), 0.0f.toDouble())
                else
                    LLVMConstReal(LLVMDoubleTypeInContext(ctx.context), 0.0)

                LLVMBuildFCmp(ctx.builder, LLVMRealUNE, unboxed, zero, "boolify")
            }

            else -> error("Unsupported type for condition")
        }

        return ValueWithBlock(result, LLVMGetInsertBlock(ctx.builder))

    }

    private fun compileShortCircuit(expr: ShchParser.ExprContext): ValueWithBlock {
        val lhsResult = compileCondExprWithBlock(expr.expr(0))
        val lhsValue = lhsResult.value
        val lhsBlock = lhsResult.block

        val function = LLVMGetBasicBlockParent(lhsBlock)
        val rhsBB = LLVMAppendBasicBlockInContext(ctx.context, function, "sc.rhs")
        val endBB = LLVMAppendBasicBlockInContext(ctx.context, function, "sc.end")

        // Переход из lhs в зависимости от оператора
        LLVMPositionBuilderAtEnd(ctx.builder, lhsBlock)
        if (expr.op.text == "&&") {
            LLVMBuildCondBr(ctx.builder, lhsValue, rhsBB, endBB)
        } else {
            LLVMBuildCondBr(ctx.builder, lhsValue, endBB, rhsBB)
        }

        // RHS
        LLVMPositionBuilderAtEnd(ctx.builder, rhsBB)
        val rhsResult = compileCondExprWithBlock(expr.expr(1))
        val rhsValue = rhsResult.value
        val rhsBlock = rhsResult.block
        LLVMBuildBr(ctx.builder, endBB)

        // PHI
        LLVMPositionBuilderAtEnd(ctx.builder, endBB)
        val phi = LLVMBuildPhi(ctx.builder, LLVMInt1TypeInContext(ctx.context), "scphi")

        val values = PointerPointer<LLVMValueRef>(2)
        val blocks = PointerPointer<LLVMBasicBlockRef>(2)

        // ✅ Точное соответствие переходов
        if (expr.op.text == "&&") {
            values.put(0, LLVMConstInt(LLVMInt1TypeInContext(ctx.context), 0, 0)) // short-circuit
            blocks.put(0, lhsBlock)

            values.put(1, rhsValue)
            blocks.put(1, rhsBlock)
        } else {
            values.put(0, LLVMConstInt(LLVMInt1TypeInContext(ctx.context), 1, 0)) // short-circuit
            blocks.put(0, lhsBlock)

            values.put(1, rhsValue)
            blocks.put(1, rhsBlock)
        }

        LLVMAddIncoming(phi, values, blocks, 2)

        return ValueWithBlock(phi, endBB)
    }
}
