import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.RecognitionException
import shch.ShchInterpreter
import java.nio.file.Files
import java.nio.file.Paths
import shch.ShchLexer
import shch.ShchParser


fun main() {
    val path = Paths.get("src/main/resources/program.shch")
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
            println("Błąd składniowy w linii $line:$charPositionInLine: $msg")
        }
    })

    val tree = parser.program()
    val interpreter = ShchInterpreter()
    interpreter.visit(tree)
}
