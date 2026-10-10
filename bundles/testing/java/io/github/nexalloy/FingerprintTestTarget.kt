package io.github.nexalloy

/**
 * An app whose fingerprints [FingerprintsKtTest] checks against APKs in `binaries/`.
 *
 * Each bundle defines `fingerprintTestTargets`, keyed by the APK file name prefix (the package name).
 *
 * @param app App name matched by [TargetApp].
 * @param sourceDirs Directories below `src/main/java/io/github/nexalloy` whose `Fingerprints.kt` are tested.
 */
class FingerprintTestTarget(val app: String, vararg val sourceDirs: String)
