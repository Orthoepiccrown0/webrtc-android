import groovy.json.JsonSlurper
import java.util.Properties

// Configuration belongs to the showcase; SDK hosts supply their own configuration.
// Keep the registered Firebase Android client/application ID for installed-demo compatibility.
val samplePackage = "com.epiccrown.webrtctest"
val streamConfig =
    Properties().apply {
        rootProject
            .file("stream.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
val firebaseFile =
    listOf(
            project.file("google-services.json"),
            rootProject.file("showcase/google-services.json"),
            rootProject.file("google-services.json"),
        )
        .firstOrNull { it.exists() }
val firebaseJson =
    firebaseFile?.let { JsonSlurper().parse(it) as Map<*, *> } ?: emptyMap<Any, Any>()

fun Map<*, *>.objectValue(name: String): Map<*, *> =
    this[name] as? Map<*, *> ?: emptyMap<Any, Any>()

val projectInfo = firebaseJson.objectValue("project_info")
val androidClients = (firebaseJson["client"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()

fun packageName(client: Map<*, *>) =
    client.objectValue("client_info").objectValue("android_client_info")["package_name"]

val androidClient =
    androidClients.firstOrNull { packageName(it) == samplePackage } ?: emptyMap<Any, Any>()

if (firebaseFile != null)
    require(androidClient.isNotEmpty()) { "google-services.json has no client for $samplePackage" }

val apiKey =
    (androidClient["api_key"] as? List<*>)
        ?.filterIsInstance<Map<*, *>>()
        ?.firstOrNull()
        ?.get("current_key")
val defaults =
    mapOf(
        "PROJECT_ID" to projectInfo["project_id"],
        "APPLICATION_ID" to androidClient.objectValue("client_info")["mobilesdk_app_id"],
        "API_KEY" to apiKey,
        "DATABASE_URL" to projectInfo["firebase_url"],
        "SENDER_ID" to projectInfo["project_number"],
    )
val keys =
    listOf(
        "PROJECT_ID",
        "APPLICATION_ID",
        "API_KEY",
        "DATABASE_URL",
        "SENDER_ID",
        "WORKER_URL",
        "FORCE_RELAY",
    )

extra["directStreamSampleSettings"] =
    keys
        .associateWith { key ->
            streamConfig.getProperty(key)?.takeIf { it.isNotBlank() }
                ?: defaults[key]?.toString().orEmpty()
        }
        .mapValues { (key, value) ->
            if (key == "FORCE_RELAY" && value.isBlank()) "false" else value
        }
