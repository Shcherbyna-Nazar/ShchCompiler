import org.antlr.v4.runtime.*
import shch.ShchLexer
import shch.ShchParser
import shch.codegen.ShchLLVMCompiler
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val inputFile = args.getOrNull(0) ?: "src/main/resources/tests/short_circuit2.shch"
    val outputLL = args.getOrNull(1) ?: "output.ll"
    val outputAST = args.getOrNull(2) ?: "ast.txt"

    try {
        compileShchFile(inputFile, outputLL, outputAST)
        println("✅ Compilation successful")
    } catch (e: Exception) {
        System.err.println("❌ Error: ${e.message}")
        exitProcess(1)
    }
}

fun compileShchFile(inputPath: String, llvmOutputPath: String, astOutputPath: String) {
    val path = Paths.get(inputPath)
    require(Files.exists(path)) { "File not found: $inputPath" }

    val input = Files.readString(path)

    val lexer = ShchLexer(CharStreams.fromString(input))
    val tokens = CommonTokenStream(lexer)
    val parser = ShchParser(tokens)

    parser.removeErrorListeners()
    parser.addErrorListener(object : BaseErrorListener() {
        override fun syntaxError(
            recognizer: Recognizer<*, *>?,
            offendingSymbol: Any?,
            line: Int,
            charPositionInLine: Int,
            msg: String?,
            e: RecognitionException?
        ) {
            throw RuntimeException("Syntax error at $line:$charPositionInLine — $msg")
        }
    })

    val tree = parser.program()

    // Сохраняем AST
    Files.writeString(Paths.get(astOutputPath), tree.toStringTree(parser))

    val compiler = ShchLLVMCompiler()
    compiler.compile(tree)
    compiler.saveToFile(llvmOutputPath)
    compiler.dispose()
}
