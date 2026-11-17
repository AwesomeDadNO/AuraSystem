package no.aura.os.v2.profiles

/** Tiny dependency-free serialisation (org.json is not available in JVM tests). Corrupt data decodes to null, never to a partial state. */
object ProfileCodec {
    private const val F = '\u001F'; private const val R = '\u001E'

    fun encode(s: ProfileState): String =
        s.activeId + R + s.profiles.joinToString(R.toString()) { p ->
            listOf(p.id, p.name, p.kind.name, p.maxAge?.toString() ?: "", p.pin?.salt ?: "", p.pin?.hash ?: "", p.googleSubject ?: "", p.avatarUrl ?: "").joinToString(F.toString())
        }

    fun decode(text: String?): ProfileState? = try {
        if (text.isNullOrEmpty()) null else {
            val parts = text.split(R)
            val profiles = parts.drop(1).map { line ->
                val f = line.split(F)
                require(f.size == 6 || f.size == 8)   // 6 = F3 data, 8 = F3b (Google link + avatar)
                // F3 stored "ADMIN" as a profile kind. It maps to STANDARD: id, name and PIN are untouched; system authorization is the Administrator PIN now.
                val kind = if (f[2] == "ADMIN") ProfileKind.STANDARD else ProfileKind.valueOf(f[2])
                Profile(f[0], f[1], kind, f[3].takeIf { it.isNotEmpty() }?.toInt(),
                    if (f[4].isNotEmpty() && f[5].isNotEmpty()) PinCredential(f[4], f[5]) else null,
                    f.getOrNull(6)?.takeIf { it.isNotEmpty() }, f.getOrNull(7)?.takeIf { it.isNotEmpty() })
            }
            ProfileState(profiles, parts[0])
        }
    } catch (_: IllegalArgumentException) { null } catch (_: IndexOutOfBoundsException) { null }
}
