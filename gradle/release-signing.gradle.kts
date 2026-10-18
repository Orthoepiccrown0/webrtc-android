import java.util.Properties

// Host-only release signing. The SDK never owns an application's signing identity.
val localSigning =
    Properties().apply {
        rootProject.file("signing.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
val signingEnvironment =
    mapOf(
        "storeFile" to "DIRECTSTREAM_KEYSTORE_FILE",
        "storePassword" to "DIRECTSTREAM_KEYSTORE_PASSWORD",
        "keyAlias" to "DIRECTSTREAM_KEY_ALIAS",
        "keyPassword" to "DIRECTSTREAM_KEY_PASSWORD",
    )
val releaseCredentials = signingEnvironment.mapValues { (name, variable) ->
    providers.environmentVariable(variable).orNull ?: localSigning.getProperty(name).orEmpty()
}
val configured = releaseCredentials.values.any { it.isNotBlank() }

if (configured) {
    require(releaseCredentials.values.all { it.isNotBlank() }) {
        "Release signing is incomplete. Set all four entries in signing.properties or DIRECTSTREAM_* signing variables."
    }
    val releaseStore = rootProject.file(releaseCredentials.getValue("storeFile"))
    require(releaseStore.isFile) { "Configured release keystore file does not exist." }
}

extra["directStreamReleaseSigning"] =
    if (configured) releaseCredentials else emptyMap<String, String>()
