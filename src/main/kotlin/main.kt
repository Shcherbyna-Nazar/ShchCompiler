import org.antlr.v4.runtime.*
import shch.ShchLexer
import shch.ShchParser
import shch.codegen.ShchLLVMCompiler
import java.nio.file.Files
import java.nio.file.Paths

fun main() {
    val path = Paths.get("src/main/resources/program.shch")
    val input = Files.readString(path)

    val lexer = ShchLexer(CharStreams.fromString(input))
    val tokens = CommonTokenStream(lexer)
    val parser = ShchParser(tokens)

    // Obsługa błędów składniowych i leksykalnych
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
            System.err.println("❌ Syntax error at $line:$charPositionInLine — $msg")
            System.exit(1)
        }
    })

    val tree = parser.program()

    // Zapis AST do pliku
    Files.writeString(Paths.get("ast.txt"), tree.toStringTree(parser))

    val compiler = ShchLLVMCompiler()
    compiler.compile(tree)
    compiler.saveToFile("output.ll")
    compiler.dispose()
}
