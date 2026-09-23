package dev.creds.vault.core.domain.breach

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Builds the bundled breached-password Bloom filter from a plain password list.
 *
 * Run through `./gradlew :core:domain:buildBreachFilter -Pinput=<list> -Psha256=<hex>`.
 * The list is checked against the expected SHA-256 before anything is built, so the asset
 * can only ever come from the reviewed source named in [OfflineBreachList]. Output is
 * deterministic: the same list always produces the same bytes.
 *
 * Lives in its own source set: it is a build step, never shipped in the app.
 */
fun main(args: Array<String>) {
    if (args.size != 3) {
        System.err.println("usage: BuildBreachFilter <input list> <expected sha256> <output file>")
        exitProcess(2)
    }
    val (inputPath, expectedSha256, outputPath) = args
    val input = File(inputPath)

    val actual = MessageDigest.getInstance("SHA-256").digest(input.readBytes())
        .joinToString("") { "%02x".format(it) }
    check(actual.equals(expectedSha256, ignoreCase = true)) {
        "SHA-256 mismatch for $inputPath: expected $expectedSha256, got $actual"
    }

    // Lines exactly as listed: breached passwords are case- and whitespace-sensitive, and
    // trimming would add passwords nobody ever used. Only line endings are stripped.
    val passwords = input.readLines(Charsets.UTF_8)
        .map { it.removeSuffix("\r") }
        .filter { it.isNotEmpty() }
        .toSortedSet()

    val filter = BloomFilter.create(expectedEntries = passwords.size, falsePositiveRate = FALSE_POSITIVE_RATE)
    passwords.forEach(filter::put)

    File(outputPath).apply { parentFile?.mkdirs() }.outputStream().buffered().use(filter::writeTo)
    println(
        "Wrote ${filter.entries} passwords to $outputPath: ${filter.bitCount} bits, " +
            "${filter.hashCount} hashes, ${File(outputPath).length()} bytes",
    )
}

private const val FALSE_POSITIVE_RATE = 0.001
