import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
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

    val tree = parser.program()

    // Save AST to a file
    Files.writeString(Paths.get("ast.txt"), tree.toStringTree(parser))

    val compiler = ShchLLVMCompiler()
    compiler.compile(tree)
    compiler.saveToFile("output.ll")
    compiler.dispose()
}
