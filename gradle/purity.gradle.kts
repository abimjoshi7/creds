// KMP portability guard.
//
// :core:model and :core:domain are the modules that would become commonMain if iOS
// ever happens. The instant one of them imports android.* or androidx.*, that port
// stops being a sourceSet change and becomes a rewrite. This task makes the boundary
// mechanical instead of a thing we remember.

val purityMarker = layout.buildDirectory.file("reports/purity.ok")

val checkPurity = tasks.register("checkPurity") {
    group = "verification"
    description = "Fails if this pure-Kotlin module imports android.* or androidx.*"

    val sources = fileTree("src") { include("**/*.kt") }
    val marker = purityMarker
    val moduleName = path

    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(marker)

    doLast {
        val forbidden = Regex("""^\s*import\s+(android|androidx)\.""")
        val offenders = buildList {
            sources.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (forbidden.containsMatchIn(line)) {
                        add("  ${file.path}:${index + 1}  ${line.trim()}")
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "$moduleName must stay platform-independent but imports Android APIs:\n" +
                    offenders.joinToString("\n") +
                    "\n\nMove the Android-specific part behind an interface in this module " +
                    "and implement it in :core:crypto, :core:data or :app."
            )
        }
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkPurity) }
