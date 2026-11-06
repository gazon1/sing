package probe

object Probe {
    fun greeting(name: String): String = "hello $name"

    fun normalized(text: String): String = normalize(text)

    private fun normalize(text: String): String {
        var result = text
        for (mark in MARKS) {
            result = result.replace(mark, "")
        }
        return result
    }

    private val MARKS = listOf("-", "_")
}
