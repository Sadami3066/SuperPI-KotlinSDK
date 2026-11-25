package io.github.whywood.superpi.sdk.coding.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path

private val writeParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("path") {
            put("type", "string")
            put("description", "Path to the file to write (relative or absolute)")
        }
        putJsonObject("content") {
            put("type", "string")
            put("description", "Content to write to the file")
        }
    }
    put("required", buildJsonArray { add("path"); add("content") })
}

fun writeTool(cwd: Path, fs: FileSystem, mutationQueue: FileMutationQueue): CodingTool = CodingTool(
    name = "write",
    description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories.",
    parameters = writeParameters,
    label = "write",
    promptSnippet = "Create or overwrite files",
    promptGuidelines = listOf("Use write only for new files or complete rewrites."),
    execute = { _toolCallId, args, signal, _onUpdate ->
        val pathInput = args.argString("path")
        val content = args.argString("content")
        val absolutePath = resolveToolPath(cwd, pathInput)
        val directory = absolutePath.parent
        mutationQueue.withFile(absolutePath) {
            signal?.throwIfAborted()
            val existing = fs.metadataOrNull(absolutePath)
            if (existing?.isDirectory == true) {
                throw IllegalArgumentException("Path is a directory, not a file: $pathInput")
            }
            val existed = existing != null
            if (directory != null) {
                fs.createDirectories(directory)
            }
            signal?.throwIfAborted()
            fs.write(absolutePath) { writeUtf8(content) }
            signal?.throwIfAborted()
            val byteCount = content.encodeToByteArray().size
            val lineCount = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
            val status = if (existed) "overwritten" else "created"
            textResult("Successfully wrote $byteCount bytes ($lineCount lines) to $pathInput ($status)")
        }
    },
)
