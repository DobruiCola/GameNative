package app.gamenative.ui.util

import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import com.winlator.renderer.effects.ColorEffect
import com.winlator.renderer.effects.CRTEffect
import com.winlator.renderer.effects.Effect
import com.winlator.renderer.effects.FSR1EasuEffect
import com.winlator.renderer.effects.FSR1RcasEffect
import com.winlator.renderer.effects.FXAAEffect
import com.winlator.renderer.effects.NTSCCombinedEffect
import com.winlator.renderer.effects.ScalingModeEffect
import com.winlator.renderer.effects.ToonEffect
import com.winlator.renderer.effects.VividEffect
import kotlin.math.abs

data class ScreenEffectsConfig(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1.0f,
    val scalingMode: Int = SCALING_MODE_NONE,
    val fsrSharpnessLevel: Int = FSR_DEFAULT_LEVEL,
    val enableToon: Boolean = false,
    val enableFXAA: Boolean = false,
    val enableVivid: Boolean = false,
    val enableCRT: Boolean = false,
    val enableNTSC: Boolean = false,
) {
    companion object {
        const val SCALING_MODE_NONE = 0
        const val SCALING_MODE_NEAREST = 1
        const val SCALING_MODE_LINEAR = 2
        const val SCALING_MODE_FILL = 3
        const val SCALING_MODE_STRETCH = 4
        const val SCALING_MODE_FSR = 5
        const val SCALING_MODE_FSR_ASPECT = 6
        const val SCALING_MODE_DLS = 7
        const val SCALING_MODE_NATURAL = 8
        const val FSR_MIN_LEVEL = 1
        const val FSR_MAX_LEVEL = 5
        const val FSR_DEFAULT_LEVEL = 3

        // Container extra keys
        const val KEY_BRIGHTNESS = "screenEffectsBrightness"
        const val KEY_CONTRAST = "screenEffectsContrast"
        const val KEY_GAMMA = "screenEffectsGamma"
        const val KEY_SCALING_MODE = "screenEffectsScalingMode"
        const val KEY_FSR_SHARPNESS = "screenEffectsFsrSharpness"
        const val KEY_ENABLE_TOON = "screenEffectsEnableToon"
        const val KEY_ENABLE_FXAA = "screenEffectsEnableFXAA"
        const val KEY_ENABLE_VIVID = "screenEffectsEnableVivid"
        const val KEY_ENABLE_CRT = "screenEffectsEnableCRT"
        const val KEY_ENABLE_NTSC = "screenEffectsEnableNTSC"
    }
}

/** Load config from container extras, falling back to hardcoded defaults. */
fun loadScreenEffectsConfig(container: Container?): ScreenEffectsConfig {
    if (container == null) return ScreenEffectsConfig()
    return ScreenEffectsConfig(
        brightness = container.getExtra(ScreenEffectsConfig.KEY_BRIGHTNESS)?.toFloatOrNull() ?: 0f,
        contrast = container.getExtra(ScreenEffectsConfig.KEY_CONTRAST)?.toFloatOrNull() ?: 0f,
        gamma = container.getExtra(ScreenEffectsConfig.KEY_GAMMA)?.toFloatOrNull() ?: 1f,
        scalingMode = container.getExtra(ScreenEffectsConfig.KEY_SCALING_MODE)?.toIntOrNull() ?: ScreenEffectsConfig.SCALING_MODE_NONE,
        fsrSharpnessLevel = container.getExtra(ScreenEffectsConfig.KEY_FSR_SHARPNESS)?.toIntOrNull() ?: ScreenEffectsConfig.FSR_DEFAULT_LEVEL,
        enableToon = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_TOON)?.toBooleanStrictOrNull() ?: false,
        enableFXAA = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_FXAA)?.toBooleanStrictOrNull() ?: false,
        enableVivid = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_VIVID)?.toBooleanStrictOrNull() ?: false,
        enableCRT = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_CRT)?.toBooleanStrictOrNull() ?: false,
        enableNTSC = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_NTSC)?.toBooleanStrictOrNull() ?: false,
    )
}

/**
 * Persist config to container extras.
 * Callers should debounce [container.saveData] calls.
 */
fun persistScreenEffectsConfig(container: Container?, config: ScreenEffectsConfig) {
    if (container == null) return
    container.putExtra(ScreenEffectsConfig.KEY_BRIGHTNESS, config.brightness)
    container.putExtra(ScreenEffectsConfig.KEY_CONTRAST, config.contrast)
    container.putExtra(ScreenEffectsConfig.KEY_GAMMA, config.gamma)
    container.putExtra(ScreenEffectsConfig.KEY_SCALING_MODE, config.scalingMode)
    container.putExtra(ScreenEffectsConfig.KEY_FSR_SHARPNESS, config.fsrSharpnessLevel)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_TOON, config.enableToon)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_FXAA, config.enableFXAA)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_VIVID, config.enableVivid)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_CRT, config.enableCRT)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_NTSC, config.enableNTSC)
}

fun fsrQuickMenuLevelToStops(level: Int): Float {
    val clamped = level.coerceIn(ScreenEffectsConfig.FSR_MIN_LEVEL, ScreenEffectsConfig.FSR_MAX_LEVEL)
    return when (clamped) {
        1 -> 2.0f
        2 -> 1.5f
        3 -> 1.0f
        4 -> 0.5f
        else -> 0.0f
    }
}

/**
 * Maps Pluvia's screen-effects config onto VulkanRenderer's compact effect set.
 *
 * The Vulkan compositor runs one effect at a time (FSR / DLS / CRT / HDR /
 * NATURAL). We resolve user selections by priority: Vivid (HDR) > CRT >
 * slider effect (DLS / Natural / FSR scaling) > None. Sharpness applies to FSR
 * and DLS only.
 *
 * Pluvia's older GL effects (Toon, FXAA, NTSC, brightness/contrast/gamma) have
 * no Vulkan equivalent and are silently ignored.
 */
fun applyScreenEffectsConfig(renderer: VulkanRenderer, config: ScreenEffectsConfig) {
    val effectId = when {
        config.enableVivid -> VulkanRenderer.EFFECT_HDR
        config.enableCRT -> VulkanRenderer.EFFECT_CRT
        config.scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR ||
            config.scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT -> VulkanRenderer.EFFECT_FSR
        config.scalingMode == ScreenEffectsConfig.SCALING_MODE_DLS -> VulkanRenderer.EFFECT_DLS
        config.scalingMode == ScreenEffectsConfig.SCALING_MODE_NATURAL -> VulkanRenderer.EFFECT_NATURAL
        else -> VulkanRenderer.EFFECT_NONE
    }
    val sharpness = (config.fsrSharpnessLevel.coerceIn(0, 4)) / 4.0f
    renderer.setEffect(effectId, sharpness)
}
