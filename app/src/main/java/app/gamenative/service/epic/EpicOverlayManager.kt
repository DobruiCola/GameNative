package app.gamenative.service.epic

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages EOS (Epic Online Services) Overlay installation and configuration inside Wine containers.
 *
 * The overlay provides in-game Epic notifications, friend activity, and purchasing UI.
 * Implementation follows Legendary's overlay flow (legendary/lfs/eos.py, legendary/core.py).
 *
 * Install flow:
 *  1. Fetch the latest overlay manifest from Epic's CDN.
 *  2. Download and install overlay files into the Wine prefix.
 *  3. Replace upstream overlay DLLs with Wine-compatible stubs so games do not crash.
 *  4. Write the overlay install path to the Wine registry (HKCU\SOFTWARE\Epic Games\EOS\OverlayPath).
 */
@Singleton
class EpicOverlayManager @Inject constructor(
    private val epicManager: EpicManager,
    private val epicDownloadManager: EpicDownloadManager,
) {

    companion object {
        // ── EOS Overlay Epic app identifiers ─────────────────────────────────────
        // Source: legendary/lfs/eos.py  EOSOverlayApp
        const val OVERLAY_APP_NAME = "98bc04bc842e4906993fd6d6644ffb8d"
        const val OVERLAY_NAMESPACE = "302e5ede476149b1bc3e4fe6ae45e50e"
        const val OVERLAY_CATALOG_ITEM_ID = "cc15684f44d849e89e9bf4cec0508b68"

        // ── Wine prefix path (mirrors the standard Epic launcher install location) ─
        // Legendary searches for the overlay at:
        //   {prefix}/drive_c/Program Files (x86)/Epic Games/Launcher/Portal/Extras/Overlay
        const val OVERLAY_WINE_RELATIVE_PATH =
            "drive_c/Program Files (x86)/Epic Games/Launcher/Portal/Extras/Overlay"

        // Windows-style path used in the registry value
        const val OVERLAY_WIN_PATH =
            "C:\\Program Files (x86)\\Epic Games\\Launcher\\Portal\\Extras\\Overlay"

        // ── Registry keys ─────────────────────────────────────────────────────────
        // Source: legendary/lfs/eos.py  EOS_OVERLAY_KEY / EOS_OVERLAY_VALUE
        const val EOS_OVERLAY_REG_KEY = "SOFTWARE\\Epic Games\\EOS"
        const val EOS_OVERLAY_REG_VALUE = "OverlayPath"

        // ── Identification ────────────────────────────────────────────────────────
        // Presence of this file signals that the overlay is installed.
        const val OVERLAY_MARKER_FILE = "EOSOVH-Win64-Shipping.dll"

        // These upstream Windows DLLs are replaced with Wine-compatible stubs so that
        // the graphical overlay layer does not cause crashes inside Wine.
        // The EOS SDK authentication path does not depend on the graphical renderer,
        // so replacing these stubs still allows online play.
        val OVERLAY_DLLS_TO_STUB = listOf(
            "EOSOVH-Win64-Shipping.dll",
            "EOSOVH-Win32-Shipping.dll",
        )

        // Backup suffix – originals are saved as <name>.orig so they can be restored.
        const val BACKUP_SUFFIX = ".orig"
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Install the EOS overlay into [container]'s Wine prefix.
     *
     * Idempotent: if the overlay is already up-to-date, the function returns
     * success without re-downloading unless [forceReinstall] is true.
     */
    suspend fun installOverlay(
        context: Context,
        container: Container,
        forceReinstall: Boolean = false,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val overlayDir = overlayDir(container)

            if (!forceReinstall && isOverlayInstalled(container)) {
                Timber.tag("EOSOverlay").i("Overlay already installed at ${overlayDir.absolutePath}, skipping")
                return@withContext Result.success(Unit)
            }

            Timber.tag("EOSOverlay").i("Starting EOS overlay install into container ${container.id}")

            // Get the Overlay manifest
            val manifestResult = epicManager.fetchManifestFromEpic(
                context = context,
                namespace = OVERLAY_NAMESPACE,
                catalogItemId = OVERLAY_CATALOG_ITEM_ID,
                appName = OVERLAY_APP_NAME,
            )
            if (manifestResult.isFailure) {
                return@withContext Result.failure(
                    manifestResult.exceptionOrNull()
                        ?: Exception("Failed to fetch EOS overlay manifest"),
                )
            }

            val manifest = manifestResult.getOrNull()!!
            overlayDir.mkdirs()

            // Download overlay files to the install directory of the container
            Timber.tag("EOSOverlay").i("Downloading overlay files to ${overlayDir.absolutePath}")
            val downloadResult = epicDownloadManager.downloadOverlay(
                manifestResult = manifest,
                installPath = overlayDir.absolutePath,
                onProgress = onProgress,
            )
            if (downloadResult.isFailure) {
                return@withContext Result.failure(
                    downloadResult.exceptionOrNull()
                        ?: Exception("Failed to download EOS overlay files"),
                )
            }

            // Replace incompatible DLLs with stubs so the overlay does not crash in Wine
            replaceIncompatibleDlls(overlayDir)

            // Update registry to point to the overlay path
            writeRegistryPath(container, OVERLAY_WIN_PATH)

            Timber.tag("EOSOverlay").i("EOS overlay installation complete")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("EOSOverlay").e(e, "EOS overlay installation failed")
            Result.failure(e)
        }
    }

    /**
     * Returns true if the overlay marker file exists in the container's Wine prefix.
     */
    fun isOverlayInstalled(container: Container): Boolean =
        File(overlayDir(container), OVERLAY_MARKER_FILE).exists()

    /**
     * Remove all overlay files from [container] and clear the registry path.
     */
    suspend fun removeOverlay(context: Context, container: Container): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val dir = overlayDir(container)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    Timber.tag("EOSOverlay").i("Removed overlay directory: ${dir.absolutePath}")
                }
                removeRegistryPath(container)
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.tag("EOSOverlay").e(e, "Failed to remove EOS overlay")
                Result.failure(e)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the overlay install directory inside [container]'s Wine prefix.
     */
    private fun overlayDir(container: Container): File =
        File(container.rootDir, ".wine/$OVERLAY_WINE_RELATIVE_PATH")

    /**
     * Replace upstream Windows overlay DLLs with Wine-compatible stubs.
     *
     * Originals are backed up as `<filename>.orig`.  If a compatible DLL asset
     * is bundled into the app it should be copied here instead of writing an
     * empty stub – see the TODO below.
     *
     * Background: the graphical EOS overlay renderer DLLs are x86/x64 PE images
     * that may crash or cause hangs when Wine tries to initialise them on an ARM
     * host.  Replacing them with minimal stub DLLs prevents the crash while
     * still allowing the EOS SDK to authenticate and provide online features.
     *
     * Source: inspired by Legendary's eos.py and Heroic's overlay handling.
     */
    private fun replaceIncompatibleDlls(overlayDir: File) {
        for (dllName in OVERLAY_DLLS_TO_STUB) {
            val original = File(overlayDir, dllName)
            if (!original.exists()) continue

            // Back up the original before replacing
            val backup = File(overlayDir, "$dllName$BACKUP_SUFFIX")
            if (!backup.exists()) {
                Files.copy(original.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Timber.tag("EOSOverlay").d("Backed up $dllName → $dllName$BACKUP_SUFFIX")
            }

            // TODO: Copy a compatible ARM64/Wine-compatible stub from bundled assets:
            //   context.assets.open("eos_overlay_stubs/$dllName").use { input ->
            //       Files.copy(input, original.toPath(), StandardCopyOption.REPLACE_EXISTING)
            //   }
            // Until compatible stubs are available, write a minimal valid PE stub
            // (the MZ header only) so the DLL is present but does nothing.
            original.writeBytes(MINIMAL_PE_STUB)
            Timber.tag("EOSOverlay").d("Replaced $dllName with minimal stub")
        }
    }

    /**
     * Write the EOS overlay path to HKCU\SOFTWARE\Epic Games\EOS\OverlayPath in
     * [container]'s Wine user.reg.
     *
     * Mirrors `add_registry_entries` in legendary/lfs/eos.py for the Wine/prefix
     * code path (HKCU only; Vulkan implicit layers are not set because they do
     * not work in Wine).
     */
    private fun writeRegistryPath(container: Container, winPath: String) {
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        WineRegistryEditor(userRegFile).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            editor.setStringValue(EOS_OVERLAY_REG_KEY, EOS_OVERLAY_REG_VALUE, winPath)
        }
        Timber.tag("EOSOverlay").d(
            "Registry updated: HKCU\\$EOS_OVERLAY_REG_KEY\\$EOS_OVERLAY_REG_VALUE = $winPath",
        )
    }

    /**
     * Clear the EOS overlay path from the Wine user.reg.
     *
     * Mirrors `remove_registry_entries` in legendary/lfs/eos.py.
     */
    private fun removeRegistryPath(container: Container) {
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        if (!userRegFile.exists()) return
        WineRegistryEditor(userRegFile).use { editor ->
            editor.setStringValue(EOS_OVERLAY_REG_KEY, EOS_OVERLAY_REG_VALUE, "")
        }
        Timber.tag("EOSOverlay").d("Removed HKCU\\$EOS_OVERLAY_REG_KEY\\$EOS_OVERLAY_REG_VALUE")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Minimal valid MZ/PE stub – 64 bytes (MZ header only with a self-referencing
     * PE offset that points past the end of the file, causing Windows/Wine to
     * reject the DLL gracefully rather than crashing on a missing export).
     */
    private val MINIMAL_PE_STUB: ByteArray by lazy {
        byteArrayOf(
            // MZ header signature
            0x4D, 0x5A,
            // Bytes on last page, pages in file (zero for stub)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // Relocations, header paragraphs, min/max alloc (all zero)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // e_lfanew: PE offset beyond file length → load fails gracefully
            0x40, 0x00, 0x00, 0x00,
        ).map { it.toByte() }.toByteArray()
    }
}
