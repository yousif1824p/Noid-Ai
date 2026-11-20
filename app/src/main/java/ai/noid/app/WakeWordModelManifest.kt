package ai.noid.app

/**
 * Signed-by-digest metadata contract for a locally imported wake-word model.
 * This is metadata validation only: it does not parse JSON, hash files, load
 * inference runtimes, or authorize microphone capture.
 */
data class WakeWordModelManifest(
    val schemaVersion: Int,
    val modelFileName: String,
    val modelSha256: String,
    val modelBytes: Long,
    val wakePhrase: String,
    val languageTag: String,
    val sampleRateHz: Int,
    val channels: Int,
    val audioWindowSamples: Int,
    val fftSize: Int,
    val windowSamples: Int,
    val hopSamples: Int,
    val centerPaddingSamples: Int,
    val melBins: Int,
    val frameCount: Int,
    val tensorBatch: Int,
    val tensorMelBins: Int,
    val tensorFrames: Int,
    val preprocessingOperations: List<String>,
    val preprocessingProfileId: String,
    val preprocessingProfileSha256: String,
    val modelInputName: String,
    val modelOutputName: String,
    val threshold: Float,
    val consecutiveFrames: Int,
    val refractoryMillis: Int,
    val licenseId: String,
    val exporterVersion: String
)
