package dev.creds.vault.core.domain.tag

/** The outcome of checking a tag name someone typed. */
sealed interface TagNameCheck {

    /** Usable, in its normalised form. */
    data class Valid(val name: String) : TagNameCheck

    data object Blank : TagNameCheck

    data class TooLong(val max: Int) : TagNameCheck
}

/**
 * Rules for tag names.
 *
 * Normalising before storing is what makes duplicate detection meaningful: `Work`,
 * ` work ` and `#work` are the same tag to a person, so they must be the same tag to the
 * database too — otherwise filtering by one silently misses items filed under another.
 */
object TagNames {

    const val MAX_LENGTH: Int = 32

    private val whitespace = Regex("\\s+")

    /**
     * Trims, collapses internal whitespace, and drops leading `#`s — people type the
     * hash because the UI shows tags with one.
     */
    fun normalize(raw: String): String =
        raw.trim().trimStart('#').trim().replace(whitespace, " ")

    fun check(raw: String): TagNameCheck {
        val name = normalize(raw)
        return when {
            name.isEmpty() -> TagNameCheck.Blank
            name.codePointCount(0, name.length) > MAX_LENGTH -> TagNameCheck.TooLong(MAX_LENGTH)
            else -> TagNameCheck.Valid(name)
        }
    }

    /**
     * Case-insensitive identity.
     *
     * Compared here rather than with SQLite's `COLLATE NOCASE`, which folds ASCII only:
     * it would treat `Über` and `über` as two different tags.
     */
    fun sameName(a: String, b: String): Boolean =
        normalize(a).equals(normalize(b), ignoreCase = true)

    /** A map key that agrees with [sameName]: two names share it exactly when they are the same tag. */
    fun foldCase(name: String): String = normalize(name).uppercase().lowercase()

    /** [raw] as a valid name, shortened to [MAX_LENGTH] code points if needed; null if blank. */
    fun coerce(raw: String): String? {
        val name = normalize(raw)
        if (name.isEmpty()) return null
        val count = name.codePointCount(0, name.length)
        return if (count <= MAX_LENGTH) name else name.substring(0, name.offsetByCodePoints(0, MAX_LENGTH)).trimEnd()
    }
}
