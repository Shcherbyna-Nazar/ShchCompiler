#!/bin/bash

SRC_PATH="src/main/resources/program.shch"
LL_PATH="output.ll"
OUT_BIN="program.out"

echo "🛠 Running Kotlin compiler..."
./gradlew build || { echo "❌ Kotlin build failed"; exit 1; }

# 2. Запуск компилятора языка shch
echo "📦 Compiling .shch to LLVM IR..."
java -cp build/libs/Shch.jar MainKt || { echo "❌ Compilation failed"; exit 1; }

echo "🏗 Compiling LLVM IR to binary..."
clang "$LL_PATH" -o "$OUT_BIN" || { echo "❌ LLVM to binary failed"; exit 1; }

# 4. Запуск скомпилированной программы
echo "🚀 Running compiled program:"
./"$OUT_BIN"
