package no.aura.os.v2.tmdb

/**
 * Minimal strict JSON reader (org.json is an Android stub in JVM tests, and a hand-rolled reader keeps parsing identical everywhere).
 * Values: Map<String, Any?>, List<Any?>, String, Double, Boolean, null. Depth and size are bounded; malformed input returns null, never throws.
 */
object Json {
    private const val MAX_DEPTH = 24

    fun parse(text: String?): Any? {
        if (text == null) return null
        return try { Reader(text).run { val v = value(0); ws(); if (pos != s.length) throw Bad(); v } } catch (_: Bad) { null } catch (_: IndexOutOfBoundsException) { null }
    }

    private class Bad : RuntimeException()

    private class Reader(val s: String) {
        var pos = 0
        fun ws() { while (pos < s.length && s[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++ }
        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw Bad()
            ws()
            return when (val c = s[pos]) {
                '{' -> obj(depth); '[' -> arr(depth); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw Bad()
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, pos)) throw Bad(); pos += w.length; return v }
        fun num(): Double {
            val st = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            return s.substring(st, pos).toDoubleOrNull() ?: throw Bad()
        }
        fun str(): String {
            pos++; val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw Bad()
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> throw Bad()
                        }
                    }
                    c < ' ' -> throw Bad()
                    else -> sb.append(c)
                }
            }
        }
        fun arr(depth: Int): List<Any?> {
            pos++; val out = ArrayList<Any?>(); ws()
            if (s[pos] == ']') { pos++; return out }
            while (true) { out.add(value(depth + 1)); ws(); when (s[pos++]) { ',' -> {}; ']' -> return out; else -> throw Bad() } }
        }
        fun obj(depth: Int): Map<String, Any?> {
            pos++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[pos] == '}') { pos++; return out }
            while (true) {
                ws(); if (s[pos] != '"') throw Bad()
                val k = str(); ws(); if (s[pos++] != ':') throw Bad()
                out[k] = value(depth + 1); ws()
                when (s[pos++]) { ',' -> {}; '}' -> return out; else -> throw Bad() }
            }
        }
    }
}

/** Safe accessors over parsed JSON. Missing or mistyped fields read as null / empty, never throw. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Any?.list(): List<Any?> = (this as? List<Any?>) ?: emptyList()
internal fun Map<String, Any?>.str(k: String): String? = (this[k] as? String)?.takeIf { it.isNotBlank() }
internal fun Map<String, Any?>.int(k: String): Int? = (this[k] as? Double)?.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
internal fun Map<String, Any?>.dbl(k: String): Double? = (this[k] as? Double)?.takeIf { it.isFinite() }
internal fun Map<String, Any?>.bool(k: String): Boolean = this[k] as? Boolean ?: false
