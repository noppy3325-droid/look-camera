package jp.noppy.lookcamera

enum class Category(val label: String) {
    CAMERA("Camera Looks"), FILM("Film Stocks"), CREATIVE("Creative Looks")
}

data class Grade(
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val fade: Float = 0f,
    val split: Float = 0f,
    val greenMute: Float = 0f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val bloom: Float = 0f,
    val sharpness: Float = 0f,
    val exposure: Float = 0f,
    val halation: Float = 0f,
) {
    // Work in perceptual sRGB. Recombine tone and chroma around a shared luma
    // so that pushing a warm or saturated color cannot clip one channel alone.
    fun map(r: Float, g: Float, b: Float): FloatArray {
        val luma = luminance(r, g, b)
        val exposed = ease(luma, 1f + exposure)
        val pivot = .46f
        val curved = if (exposed < pivot) {
            pivot * (1f - ease((pivot - exposed) / pivot, contrast))
        } else {
            pivot + (1f - pivot) * ease((exposed - pivot) / (1f - pivot), contrast)
        }
        val dark = 1f - curved
        val tone = curved + shadows * (4f * curved * dark * dark * dark) +
            highlights * curved * curved * curved
        val printLuma = (fade + (1f - fade) * tone).coerceIn(0f, 1f)

        val colorfulness = maxOf(r, g, b) - minOf(r, g, b)
        val foliage = smooth(.01f, .24f, g - b) * smooth(-.02f, .23f, g - r)
        val sky = smooth(.01f, .31f, b - r) * smooth(-.06f, .18f, b - g)
        val amber = smooth(.01f, .18f, r - g) * smooth(.01f, .22f, g - b)
        val skinTone = amber * smooth(.13f, .38f, luma) * (1f - smooth(.42f, .73f, colorfulness))
        // Apply extra saturation mostly to muted colors. Strong color from the camera
        // already has little headroom, especially in skin and warm highlights.
        val saturationHeadroom = 1f - smooth(.25f, .74f, colorfulness)
        val vividness = minOf(saturation, 1f) + (saturation - 1f).coerceAtLeast(0f) *
            saturationHeadroom * (1f - .65f * skinTone)
        val highlightChroma = 1f - (-highlights * 6f).coerceIn(0f, .36f) * smooth(.66f, .98f, luma)
        val chroma = vividness * highlightChroma * (1f - greenMute * (.54f * foliage + .30f * sky))
        val middle = 4f * luma * (1f - luma) * (1f - .78f * smooth(.74f, 1f, luma))
        val shadow = smooth(.005f, .07f, luma) * (1f - smooth(.06f, .46f, luma))
        val shoulder = smooth(.52f, .83f, luma) * (1f - smooth(.84f, 1f, luma))
        val warm = warmth * middle * (1f - .24f * amber - .22f * skinTone)
        val splitShadow = split * shadow
        val splitLight = split * .18f * shoulder
        val olive = saturation * greenMute * foliage * colorfulness * .13f
        val red = (r - luma) * chroma + warm - .64f * splitShadow + splitLight + olive
        val green = (g - luma) * chroma + .17f * warm + tint * middle * (1f - .55f * skinTone) + .24f * splitShadow
        val blue = (b - luma) * chroma - .77f * warm + .35f * splitShadow - splitLight - .42f * olive
        val castLuma = luminance(red, green, blue)
        val dr = red - castLuma
        val dg = green - castLuma
        val db = blue - castLuma

        val peak = maxOf(maxOf(dr, dg, db) / (1f - printLuma).coerceAtLeast(.0001f),
            -minOf(dr, dg, db) / printLuma.coerceAtLeast(.0001f))
        val gamut = if (peak <= 1f) 1f else 1f / peak
        return floatArrayOf(
            (printLuma + dr * gamut).coerceIn(0f, 1f),
            (printLuma + dg * gamut).coerceIn(0f, 1f),
            (printLuma + db * gamut).coerceIn(0f, 1f),
        )
    }

    private fun luminance(r: Float, g: Float, b: Float) = .2126f * r + .7152f * g + .0722f * b

    private fun ease(x: Float, gain: Float) = x * gain / (1f + (gain - 1f) * x)

    private fun smooth(low: Float, high: Float, value: Float): Float {
        val x = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

data class FilterPreset(
    val id: String,
    val name: String,
    val category: Category?,
    val grade: Grade,
    val defaultIntensity: Float = 1f,
)

data class LookSelection(val preset: FilterPreset, val intensity: Float) {
    val amount: Float get() = if (preset.category == null || !intensity.isFinite()) 0f else intensity.coerceIn(0f, 1f)
    val metadata: String get() = "LookCamera/v1|${preset.id}|$amount"

    companion object {
        fun fromMetadata(metadata: String?): LookSelection {
            val parts = metadata?.split('|')
            if (parts?.size == 3 && parts[0] == "LookCamera/v1") {
                val preset = Looks.byId(parts[1])
                val value = LookSelection(preset, parts[2].toFloatOrNull() ?: 0f)
                return value.copy(intensity = value.amount)
            }
            return LookSelection(Looks.off, 0f)
        }
    }
}

object Looks {
    val off = FilterPreset("off", "OFF", null, Grade(), 0f)
    val all = listOf(
        off,
        FilterPreset("camera_natural_day", "NATURAL DAY", Category.CAMERA,
            Grade(saturation = 1.06f, contrast = 1.06f, highlights = -.024f, shadows = .038f, sharpness = -.06f)),
        FilterPreset("camera_classic_glow", "CLASSIC GLOW", Category.CAMERA,
            Grade(saturation = .87f, warmth = .042f, highlights = -.045f, vignette = .09f, bloom = .16f, halation = .11f)),
        FilterPreset("camera_micro_detail", "MICRO DETAIL", Category.CAMERA,
            Grade(contrast = 1.09f, sharpness = .16f, highlights = -.045f)),
        FilterPreset("camera_film_mood", "FILM MOOD", Category.CAMERA,
            Grade(saturation = .72f, contrast = .94f, tint = -.024f, fade = .065f, grain = .028f)),
        FilterPreset("camera_amber_memory", "AMBER MEMORY", Category.CAMERA,
            Grade(saturation = .93f, warmth = .083f, greenMute = .53f, split = .036f, highlights = -.04f)),
        FilterPreset("camera_nostalgic_print", "NOSTALGIC PRINT", Category.CAMERA,
            Grade(saturation = .91f, contrast = 1.15f, split = .060f, fade = .028f, grain = .026f, halation = .09f)),
        FilterPreset("film_golden_negative", "GOLDEN NEGATIVE", Category.FILM,
            Grade(warmth = .057f, tint = .004f, contrast = 1.14f, split = .044f, fade = .016f,
                saturation = .96f, greenMute = .52f, shadows = -.045f, highlights = -.038f,
                grain = .030f, vignette = .10f, bloom = .06f, sharpness = -.12f, halation = .13f)),
        FilterPreset("film_portrait_soft", "PORTRAIT SOFT", Category.FILM,
            Grade(saturation = .93f, contrast = .94f, warmth = .022f, tint = -.007f,
                highlights = -.029f, shadows = .065f, sharpness = -.14f, grain = .017f)),
        FilterPreset("film_classic_negative", "CLASSIC NEGATIVE", Category.FILM,
            Grade(saturation = .91f, contrast = 1.26f, tint = .020f, split = .064f, greenMute = .39f, grain = .050f, halation = .15f)),
        FilterPreset("film_vivid_slide", "VIVID SLIDE", Category.FILM,
            Grade(saturation = 1.30f, contrast = 1.23f, highlights = -.055f, sharpness = .12f)),
        FilterPreset("film_tungsten_night", "TUNGSTEN NIGHT", Category.FILM,
            Grade(saturation = 1.16f, contrast = 1.12f, warmth = -.080f, split = .073f, tint = -.024f,
                bloom = .22f, grain = .039f, halation = .35f)),
        FilterPreset("film_silver_grain", "SILVER GRAIN", Category.FILM,
            Grade(saturation = 0f, contrast = 1.22f, fade = .025f, grain = .08f, vignette = .18f)),
        FilterPreset("creative_ccd_flash", "CCD FLASH", Category.CREATIVE,
            Grade(saturation = 1.28f, contrast = 1.27f, warmth = -.031f, tint = .016f,
                highlights = -.08f, shadows = -.075f, sharpness = .28f, exposure = .13f, vignette = .18f)),
        FilterPreset("creative_milk_tone", "MILK TONE", Category.CREATIVE,
            Grade(saturation = .72f, contrast = .79f, warmth = .022f, fade = .085f, exposure = .12f)),
        FilterPreset("creative_dream_bloom", "DREAM BLOOM", Category.CREATIVE,
            Grade(saturation = .94f, contrast = .91f, tint = -.034f, bloom = .40f, exposure = .10f, fade = .039f, halation = .19f)),
        FilterPreset("creative_summer_pop", "SUMMER POP", Category.CREATIVE,
            Grade(saturation = 1.36f, warmth = .04f, shadows = .11f, highlights = -.056f, exposure = .10f)),
        FilterPreset("creative_blue_hour", "BLUE HOUR", Category.CREATIVE,
            Grade(saturation = 1.12f, contrast = 1.08f, warmth = -.094f, split = .048f,
                tint = -.041f, exposure = -.13f, vignette = .22f)),
        FilterPreset("creative_soft_fade", "SOFT FADE", Category.CREATIVE,
            Grade(saturation = .72f, contrast = .90f, warmth = .03f, fade = .13f, highlights = -.088f, grain = .028f)),
    )

    private val indexed = all.associateBy(FilterPreset::id)
    fun byId(id: String?) = indexed[id] ?: off
}

