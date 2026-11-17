package com.brightblu.parc.core.data.library

/** Best effort for ordinary PDF Info dictionaries; encrypted/compressed metadata uses the filename. */
object PdfMetadata {
    fun title(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val reference = Regex("/Info\\s+(\\d+)\\s+(\\d+)\\s+R").findAll(text).lastOrNull() ?: return null
        val obj = Regex("(?<![0-9])${reference.groupValues[1]}\\s+${reference.groupValues[2]}\\s+obj\\b([\\s\\S]*?)endobj")
            .findAll(text).lastOrNull()?.groupValues?.get(1) ?: return null
        val start = Regex("/Title\\s*").find(obj)?.range?.last?.plus(1) ?: return null
        val value = obj.substring(start)
        val raw = when {
            value.startsWith('(') -> literal(value)
            value.startsWith('<') && !value.startsWith("<<") -> {
                val hex = value.substringAfter('<').substringBefore('>').filterNot { it.isWhitespace() }
                if (!hex.all { it.digitToIntOrNull(16) != null }) return null
                (if (hex.length % 2 == 0) hex else hex + "0").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            else -> null
        } ?: return null
        val title = if (raw.size >= 2 && raw[0] == 0xfe.toByte() && raw[1] == 0xff.toByte())
            String(raw, 2, raw.size - 2, Charsets.UTF_16BE) else String(raw, Charsets.ISO_8859_1)
        return title.trim().takeIf { it.isNotBlank() && it.length <= 4096 }
    }

    private fun literal(value: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        var i = 1
        while (i < value.length && out.size() <= 8192) {
            var c = value[i++]
            if (c == '\\') {
                if (i == value.length) return null
                c = value[i++]
                if (c in '0'..'7') {
                    var octal = "$c"
                    repeat(2) { if (i < value.length && value[i] in '0'..'7') octal += value[i++] }
                    out.write(octal.toInt(8)); continue
                }
                if (c == '\n') continue
                if (c == '\r') { if (i < value.length && value[i] == '\n') i++; continue }
                c = when (c) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'; else -> c }
            } else if (c == '(') depth++
            else if (c == ')' && --depth == 0) return out.toByteArray()
            out.write(c.code)
        }
        return null
    }
}
