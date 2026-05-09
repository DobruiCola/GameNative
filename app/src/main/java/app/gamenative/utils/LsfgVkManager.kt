package app.gamenative.utils

import android.content.Context
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.util.Locale
import timber.log.Timber
import kotlin.jvm.JvmStatic

/**
 * Manages the lsfg-vk Vulkan implicit layer for frame generation.
 *
 * The layer hooks vkCreateSwapchainKHR / vkQueuePresentKHR inside the container's
 * Vulkan driver and runs Lossless Scaling frame generation transparently.
 *
 * Activation contract:
 *   LSFG is "armed" iff the container is Bionic, the user has selected a
 *   multiplier >= 2, AND a Lossless.dll is reachable. There is no separate
 *   master toggle — the multiplier is the on/off switch.
 *
 * DLL resolution priority:
 *   1. Imported DLL at <filesDir>/lsfg/Lossless.dll (set via app settings).
 *   2. Steam install dir for app 993090 (auto-downloaded when owned).
 */
object LsfgVkManager {
    private const val TAG = "LsfgVkManager"

    const val LOSSLESS_SCALING_APP_ID = 993090
    private const val LOSSLESS_DLL_NAME = "Lossless.dll"

    // Paths inside the container's HOME (relative to rootDir)
    private const val CONFIG_RELATIVE_PATH = ".config/lsfg-vk/conf.toml"
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val DLL_RELATIVE_DIR = ".local/share/lsfg-vk"
    private const val LIB_FILENAME = "liblsfg-vk-layer.so"
    private const val MANIFEST_FILENAME = "VkLayer_LS_frame_generation.json"
    private const val VERSION_FILENAME = ".lsfg_vk_runtime_version"

    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LIB_FILENAME"

    // Process identifier written to conf.toml [[game]] exe field.
    // Under Wine /proc/self/exe points to the Wine loader, so we use this
    // stable identifier instead. Set via LSFG_PROCESS env var.
    private const val PROCESS_EXE_IDENTIFIER = "gamenative-lsfg"

    // Container extra keys
    const val EXTRA_MULTIPLIER = "lsfgMultiplier"
    const val EXTRA_FLOW_SCALE = "lsfgFlowScale"
    const val EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode"

    private const val ENV_DISABLE = "DISABLE_LSFG"
    private const val ENV_CONFIG = "LSFG_CONFIG"
    private const val ENV_PROCESS = "LSFG_PROCESS"

    private const val RUNTIME_VERSION = "v1.0.1-android-arm64-v8a"

    private const val ASSET_DIR = "lsfg_vk/android_arm64_v8a"
    private const val ASSET_LIB = "$ASSET_DIR/$LIB_FILENAME"
    private const val ASSET_MANIFEST = "$ASSET_DIR/$MANIFEST_FILENAME"

    private const val IMPORTED_DLL_DIR = "lsfg"

    // ---- Source enum -------------------------------------------------------

    enum class DllSource { IMPORTED, STEAM, NONE }

    data class DllResolution(val source: DllSource, val file: File?)

    // ---- Public API --------------------------------------------------------

    /** Whether LSFG is supported for this container's variant. */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /** Resolve the active DLL: imported takes precedence over Steam install. */
    @JvmStatic
    fun resolveDll(context: Context): DllResolution {
        importedDllFile(context)?.let { return DllResolution(DllSource.IMPORTED, it) }
        steamDllFile()?.let { return DllResolution(DllSource.STEAM, it) }
        return DllResolution(DllSource.NONE, null)
    }

    /** Whether any Lossless.dll (imported or Steam) is reachable. */
    @JvmStatic
    fun isDllAvailable(context: Context): Boolean =
        resolveDll(context).file != null

    /** Whether the user owns Lossless Scaling in their Steam library. */
    @JvmStatic
    fun ownsLosslessScaling(): Boolean =
        SteamService.getAppInfoOf(LOSSLESS_SCALING_APP_ID) != null

    /** Multiplier (0=Off, 2-4, default 0). */
    fun multiplier(container: Container): Int {
        val raw = container.getExtra(EXTRA_MULTIPLIER, "0").toIntOrNull() ?: 0
        return if (raw < 2) 0 else raw.coerceIn(2, 4)
    }

    fun flowScale(container: Container): Float =
        container.getExtra(EXTRA_FLOW_SCALE, "0.80").toFloatOrNull()?.coerceIn(0.25f, 1.0f) ?: 0.80f

    fun performanceMode(container: Container): Boolean =
        parseBool(container.getExtra(EXTRA_PERFORMANCE_MODE, "true"))

    /** Whether LSFG should run for this container at launch. */
    @JvmStatic
    fun isArmed(context: Context, container: Container): Boolean =
        isSupported(container) && multiplier(container) >= 2 && isDllAvailable(context)

    /** Path of the DLL inside the container, or null if not present. */
    @JvmStatic
    fun containerDllPath(container: Container): String? {
        val dllFile = File(container.rootDir, "$DLL_RELATIVE_DIR/$LOSSLESS_DLL_NAME")
        return dllFile.absolutePath.takeIf { dllFile.isFile }
    }

    // ---- Imported DLL management ------------------------------------------

    private fun importedDllDir(context: Context): File =
        File(context.filesDir, IMPORTED_DLL_DIR)

    /** File handle for the imported DLL, or null if not present. */
    @JvmStatic
    fun importedDllFile(context: Context): File? {
        val f = File(importedDllDir(context), LOSSLESS_DLL_NAME)
        return f.takeIf { it.isFile && it.length() > 0L }
    }

    /** Copy bytes from [source] into the imported DLL location, replacing any existing. */
    @JvmStatic
    fun importDll(context: Context, source: java.io.InputStream): Boolean = try {
        val dir = importedDllDir(context).apply { mkdirs() }
        val target = File(dir, LOSSLESS_DLL_NAME)
        target.outputStream().use { out -> source.copyTo(out) }
        FileUtils.chmod(target, 0b110100100)
        Timber.tag(TAG).i("Imported Lossless.dll (%d bytes)", target.length())
        true
    } catch (t: Throwable) {
        Timber.tag(TAG).e(t, "Failed to import Lossless.dll")
        false
    }

    /** Delete the imported DLL, returning true if it existed. */
    @JvmStatic
    fun removeImportedDll(context: Context): Boolean {
        val f = File(importedDllDir(context), LOSSLESS_DLL_NAME)
        return f.exists() && f.delete()
    }

    // ---- Launch-time installation -----------------------------------------

    /**
     * Install the layer runtime + DLL into the container's filesystem.
     * Called during container startup. Skips if LSFG is not armed.
     */
    @JvmStatic
    fun ensureRuntimeInstalled(context: Context, container: Container): Boolean {
        if (!isSupported(container)) return false

        val rootDir = container.rootDir
        val localLibDir = File(rootDir, LIB_RELATIVE_DIR)
        val layerDir = File(rootDir, LAYER_RELATIVE_DIR)
        val dllDir = File(rootDir, DLL_RELATIVE_DIR)
        val libFile = File(localLibDir, LIB_FILENAME)
        val manifestFile = File(layerDir, MANIFEST_FILENAME)
        val versionFile = File(layerDir, VERSION_FILENAME)

        val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val needsInstall = installedVersion != RUNTIME_VERSION ||
            !libFile.isFile || !manifestFile.isFile

        var success = true

        if (needsInstall) {
            try {
                localLibDir.mkdirs()
                layerDir.mkdirs()

                FileUtils.copy(context, ASSET_LIB, libFile)
                val manifestText = context.assets.open(ASSET_MANIFEST)
                    .bufferedReader().use { it.readText() }
                    .replace(
                        "\"library_path\": \"$LIB_FILENAME\"",
                        "\"library_path\": \"$MANIFEST_LIBRARY_PATH\"",
                    )
                FileUtils.writeString(manifestFile, manifestText)
                FileUtils.writeString(versionFile, RUNTIME_VERSION)

                if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101)
                if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100)
                if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100)

                if (libFile.isFile && manifestFile.isFile) {
                    Timber.tag(TAG).i("Installed LSFG runtime %s into %s", RUNTIME_VERSION, rootDir)
                } else {
                    Timber.tag(TAG).e("Runtime installation verification failed")
                    success = false
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to install LSFG runtime")
                success = false
            }
        }

        // Copy the resolved DLL into the container so the layer can find it.
        val resolvedDll = resolveDll(context).file
        val targetDll = File(dllDir, LOSSLESS_DLL_NAME)
        if (resolvedDll != null) {
            try {
                if (!targetDll.isFile || targetDll.length() != resolvedDll.length()) {
                    dllDir.mkdirs()
                    resolvedDll.inputStream().use { input ->
                        targetDll.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (targetDll.exists()) FileUtils.chmod(targetDll, 0b110100100)
                    Timber.tag(TAG).i("Copied %s (%d bytes) into %s",
                        resolvedDll.name, targetDll.length(), dllDir)
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to copy Lossless.dll into container")
                success = false
            }
        }

        return success
    }

    /** Write conf.toml at launch — driven entirely by current container state. */
    @JvmStatic
    fun writeConfig(context: Context, container: Container): Boolean {
        if (!isSupported(container)) return false

        return try {
            val dllPath = containerDllPath(container)
            val mult = multiplier(container)
            val armed = mult >= 2 && dllPath != null
            val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)
            val configText = buildConfigToml(
                dllPath = dllPath,
                enabled = armed,
                multiplier = if (armed) mult else 1,
                flowScale = flowScale(container),
                performanceMode = performanceMode(container) && armed,
            )
            val ok = FileUtils.writeString(configFile, configText)
            if (ok && configFile.exists()) {
                FileUtils.chmod(configFile, 0b110100100)
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to write LSFG conf.toml")
            false
        }
    }

    /**
     * Apply launch-time env vars. If LSFG is not armed the layer manifest is
     * removed so the Vulkan loader cannot discover it.
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, container: Container, envVars: EnvVars): Boolean {
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_CONFIG)
        envVars.remove(ENV_PROCESS)

        if (!isArmed(context, container)) {
            disableLayerInContainer(container)
            Timber.tag(TAG).i(
                "LSFG disabled (multiplier=%d, dll=%s)",
                multiplier(container),
                containerDllPath(container) ?: "null",
            )
            return false
        }

        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
        envVars.put(ENV_PROCESS, PROCESS_EXE_IDENTIFIER)

        val containerLayerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val existingLayerPath = envVars["VK_LAYER_PATH"] ?: ""
        if (existingLayerPath.isNotEmpty()) {
            envVars.put("VK_LAYER_PATH", "$existingLayerPath:${containerLayerDir.absolutePath}")
        } else {
            envVars.put("VK_LAYER_PATH", containerLayerDir.absolutePath)
        }

        Timber.tag(TAG).i(
            "LSFG armed: multiplier=%d, flowScale=%.2f, perf=%s",
            multiplier(container), flowScale(container),
            if (performanceMode(container)) "on" else "off",
        )
        return true
    }

    private fun disableLayerInContainer(container: Container) {
        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val manifest = File(layerDir, MANIFEST_FILENAME)
        if (manifest.exists()) {
            manifest.delete()
            Timber.tag(TAG).d("Removed LSFG manifest to disable layer")
        }
    }

    // ---- DLL discovery (sources) ------------------------------------------

    private fun steamDllFile(): File? {
        val appDir = SteamService.getAppDirPath(LOSSLESS_SCALING_APP_ID)
        val dll = File(appDir, LOSSLESS_DLL_NAME)
        return dll.takeIf { it.isFile }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun configFile(container: Container): File =
        File(container.rootDir, CONFIG_RELATIVE_PATH)

    private fun buildConfigToml(
        dllPath: String?,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
    ): String = buildString {
        appendLine("version = 1")
        appendLine()
        appendLine("[global]")
        if (!dllPath.isNullOrBlank()) {
            appendLine("dll = ${tomlString(dllPath)}")
        }
        appendLine("no_fp16 = false")
        appendLine()

        if (!dllPath.isNullOrBlank()) {
            appendLine("[[game]]")
            appendLine("exe = ${tomlString(PROCESS_EXE_IDENTIFIER)}")
            // multiplier <= 1 means pass-through (no framegen)
            appendLine("multiplier = ${if (enabled) multiplier.coerceIn(2, 4) else 1}")
            appendLine("flow_scale = ${formatFlowScale(flowScale)}")
            appendLine("performance_mode = ${if (performanceMode) "true" else "false"}")
            appendLine("hdr_mode = false")
            // FIFO so the layer's tight present loop is naturally paced by vsync —
            // each generated vkQueuePresentKHR blocks until the next refresh.
            appendLine("experimental_present_mode = ${tomlString("fifo")}")
        }
    }

    private fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun parseBool(value: String): Boolean =
        value.equals("true", ignoreCase = true) || value == "1"

    private fun formatFlowScale(value: Float): String =
        String.format(Locale.US, "%.2f", value.coerceIn(0.25f, 1.0f))

    // ---- Runtime hot-reload -----------------------------------------------

    /**
     * Update conf.toml while the container is running. The layer detects the
     * file timestamp change on the next present and recreates the swapchain.
     */
    @JvmStatic
    fun updateConfigAtRuntime(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
    ): Boolean {
        if (!isSupported(container)) return false

        val dllPath = containerDllPath(container)
        val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)

        if (!configFile.exists()) {
            Timber.tag(TAG).w("conf.toml not found, cannot hot-reload")
            return false
        }

        return try {
            val effectiveEnabled = enabled && dllPath != null
            val effectiveMultiplier = if (effectiveEnabled) multiplier.coerceIn(2, 4) else 1
            val configText = buildConfigToml(
                dllPath = dllPath,
                enabled = effectiveEnabled,
                multiplier = effectiveMultiplier,
                flowScale = flowScale,
                performanceMode = performanceMode && effectiveEnabled,
            )

            val ok = FileUtils.writeString(configFile, configText)
            if (ok && configFile.exists()) {
                FileUtils.chmod(configFile, 0b110100100)
            }
            if (ok) {
                Timber.tag(TAG).i(
                    "Hot-reloaded conf.toml: enabled=%s, multiplier=%d, flowScale=%.2f, perf=%s",
                    effectiveEnabled, effectiveMultiplier, flowScale, performanceMode && effectiveEnabled,
                )
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to hot-reload conf.toml")
            false
        }
    }
}
