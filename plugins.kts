import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import liveplugin.registerAction
import liveplugin.show
import java.io.File

object OpenAI {
    private const val URL = "URL"
    private const val API_KEY = "API_KEY"
    private val prompt =
        """
        The provided input is Kotlin Spring Boot class code.

        Analyze this code and generate a test code structure based on Kotest's `DescribeSpec` following the rules below.
        
        #### Rules
        1. **Test Class Name**:
           - Use the input class name + `Test`.
           - The class should inherit from `DescribeSpec`.
        
        2. **`describe` Block**:
           - Create a `describe` block for each **public method** in the class.
           - Use the method name as is and describe its behavior.
        
        3. **`context` Block**:
           - Create `context` blocks for conditions explicitly stated in the method's logic (e.g., `if`, `when`, exception handling).
           - Clearly express these conditions and their details in Korean.
           - Use nested `context` blocks if necessary to represent hierarchical conditions.
        
        4. **`it` Block**:
           - Inside each `context`, create `it` blocks to describe test cases for each condition's outcome.
           - Write descriptions in Korean, detailing the condition and expected behavior.
        
        5. **Essential Blocks Only**:
           - Include only the package declaration, imports, class declaration, `describe`, `context`, and `it` blocks.
           - Do not include test logic, method implementations, comments, or unrelated text.
        
        6. **Response Format**:
           - Provide the response as plain Kotlin code.
           - Do not wrap the response in backticks (```) or any other formatting characters.
           - Ensure the response is well-structured, readable, and directly usable in a Kotlin environment.
        
        Focus solely on the conditions explicitly stated in the input code and return the result as plain Kotlin code.
        """.trimIndent()

    data class Request(
        val messages: List<Message>,
        val temperature: Double = 0.7,
        val topP: Double = 0.95,
        val maxTokens: Int = 10_000,
    ) {
        data class Message(
            val role: String,
            val content: Content,
        ) {
            data class Content(
                val type: String = "text",
                val text: String,
            )

            companion object {
                fun system(text: String) = Message("system", Content(text = text))

                fun user(text: String) = Message("user", Content(text = text))
            }
        }
    }

    fun converse(text: String): String? {
        val messages = listOf(Request.Message.system(prompt), Request.Message.user(text))
        val request = Request(messages)

        return extractContent(sendRequest(serializeToJson(request)))
    }

    private fun sendRequest(jsonData: String): String =
        try {
            HttpRequests
                .post(URL, "application/json")
                .connect { request ->
                    request.connection.setRequestProperty("api-key", API_KEY)
                    request.write(jsonData)
                    request.readString()
                }
        } catch (e: Exception) {
            "POST 요청 실패: ${e.message}"
        }

    private fun serializeToJson(request: Request): String {
        val messagesJson =
            request.messages.joinToString(prefix = "[", postfix = "]") { message ->
                val contentJson =
                    """
                    {
                        "type": "${message.content.type}",
                        "text": "${message.content.text.replace("\n", "\\n").replace("\"", "\\\"")}"
                    }
                    """.trimIndent()
                """
                {
                    "role": "${message.role}",
                    "content": [$contentJson]
                }
                """.trimIndent()
            }

        return """
            {
                "messages": $messagesJson,
                "temperature": ${request.temperature},
                "top_p": ${request.topP},
                "max_tokens": ${request.maxTokens}
            }
            """.trimIndent()
    }

    private fun extractContent(jsonString: String): String? {
        val json = Json { ignoreUnknownKeys = true }
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        val choice = jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject
        val message = choice?.get("message")?.jsonObject
        val content = message?.get("content")

        return content?.jsonPrimitive?.content
    }
}

registerAction(id = "CREATE TEST FILE WITH AI", keyStroke = "ctrl shift T") { event: AnActionEvent ->
    val project = event.project ?: return@registerAction
    val editor = event.getData(EDITOR) ?: return@registerAction
    val code = editor.document.text
    val testCode = OpenAI.converse(code) ?: return@registerAction

    val originFile =
        FileDocumentManager.getInstance().getFile(editor.document)?.let { File(it.path) } ?: return@registerAction

    val fileName = "${originFile.nameWithoutExtension}Test.${originFile.extension}"
    val filePath = "${originFile.parent.replace("/src/main/kotlin", "/src/test/kotlin")}/$fileName"

    ApplicationManager.getApplication().runWriteAction {
        val file = File(filePath)
        if (!file.exists()) {
            file.createNewFile()
        }

        val virtualFile: VirtualFile =
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@runWriteAction

        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@runWriteAction

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(testCode)
        }

        show("Test file created with content: ${document.text}")
    }
}

if (!isIdeStartup) show("Loaded 'Create Test File' action<br/>Use 'ctrl+shift+T' to run it")
