package com.zaralynchisel.renderengine

/**
 * Biome tint colors for grass blocks and leaves, matching vanilla 1.21
 * biome definitions (effects.grass_color / effects.foliage_color in
 * data/minecraft/worldgen/biome/ 的 json 定义).
 *
 * Unknown biomes (modded) fall back to plains colors.
 */
object BiomeColors {

    // 0xRRGGBB
    private val GRASS = mapOf(
        "minecraft:plains" to 0x91BD59, "minecraft:sunflower_plains" to 0x91BD59,
        "minecraft:forest" to 0x79C05A, "minecraft:flower_forest" to 0x79C05A,
        "minecraft:birch_forest" to 0x88BB67, "minecraft:old_growth_birch_forest" to 0x88BB67,
        "minecraft:dark_forest" to 0x59AE30, // + dark_forest modifier
        "minecraft:swamp" to 0x6A7039, "minecraft:mangrove_swamp" to 0x6A7039,
        "minecraft:jungle" to 0x59C93C, "minecraft:bamboo_jungle" to 0x59C93C,
        "minecraft:sparse_jungle" to 0x59C93C,
        "minecraft:taiga" to 0x86B783, "minecraft:snowy_taiga" to 0x80B497,
        "minecraft:old_growth_pine_taiga" to 0x86B783, "minecraft:old_growth_spruce_taiga" to 0x86B783,
        "minecraft:desert" to 0xBFB755,
        "minecraft:savanna" to 0xBFB755, "minecraft:savanna_plateau" to 0xBFB755,
        "minecraft:windswept_savanna" to 0xBFB755,
        "minecraft:badlands" to 0x90814D, "minecraft:wooded_badlands" to 0x90814D,
        "minecraft:eroded_badlands" to 0x90814D,
        "minecraft:meadow" to 0x83BB6D,
        "minecraft:cherry_grove" to 0xB6DB61,
        "minecraft:grove" to 0x8DB360,
        "minecraft:snowy_plains" to 0x80B497, "minecraft:ice_spikes" to 0x80B497,
        "minecraft:snowy_slopes" to 0x80B497, "minecraft:frozen_peaks" to 0x80B497,
        "minecraft:jagged_peaks" to 0x80B497, "minecraft:stony_peaks" to 0x80B497,
        "minecraft:stony_shore" to 0x8AB689,
        "minecraft:windswept_hills" to 0x8AB689, "minecraft:windswept_forest" to 0x8AB689,
        "minecraft:windswept_gravelly_hills" to 0x8AB689,
        "minecraft:river" to 0x91BD59, "minecraft:frozen_river" to 0x80B497,
        "minecraft:beach" to 0x91BD59, "minecraft:snowy_beach" to 0x80B497,
        "minecraft:ocean" to 0x8EB971, "minecraft:deep_ocean" to 0x8EB971,
        "minecraft:cold_ocean" to 0x80B497, "minecraft:deep_cold_ocean" to 0x80B497,
        "minecraft:frozen_ocean" to 0x80B497, "minecraft:deep_frozen_ocean" to 0x80B497,
        "minecraft:lukewarm_ocean" to 0x8EB971, "minecraft:deep_lukewarm_ocean" to 0x8EB971,
        "minecraft:warm_ocean" to 0x8EB971,
        "minecraft:mushroom_fields" to 0x55C93F,
        "minecraft:dripstone_caves" to 0x8DB360, "minecraft:lush_caves" to 0x8DB360,
        "minecraft:deep_dark" to 0x8DB360,
        "minecraft:the_end" to 0x8EB971, "minecraft:end_barrens" to 0x8EB971,
        "minecraft:end_highlands" to 0x8EB971, "minecraft:end_midlands" to 0x8EB971,
        "minecraft:small_end_islands" to 0x8EB971,
        "minecraft:nether_wastes" to 0xBFB755, "minecraft:soul_sand_valley" to 0xBFB755,
        "minecraft:crimson_forest" to 0xBFB755, "minecraft:warped_forest" to 0xBFB755,
        "minecraft:basalt_deltas" to 0xBFB755
    )

    private val FOLIAGE = mapOf(
        "minecraft:plains" to 0x77AB2F, "minecraft:sunflower_plains" to 0x77AB2F,
        "minecraft:forest" to 0x59AE30, "minecraft:flower_forest" to 0x59AE30,
        "minecraft:birch_forest" to 0x6BA941, "minecraft:old_growth_birch_forest" to 0x6BA941,
        "minecraft:dark_forest" to 0x59AE30,
        "minecraft:swamp" to 0x6A7039, "minecraft:mangrove_swamp" to 0x8A9A45,
        "minecraft:jungle" to 0x30BB0B, "minecraft:bamboo_jungle" to 0x30BB0B,
        "minecraft:sparse_jungle" to 0x30BB0B,
        "minecraft:taiga" to 0x68A464, "minecraft:snowy_taiga" to 0x60A17B,
        "minecraft:old_growth_pine_taiga" to 0x68A464, "minecraft:old_growth_spruce_taiga" to 0x68A464,
        "minecraft:desert" to 0xAEAA2A,
        "minecraft:savanna" to 0xAEAA2A, "minecraft:savanna_plateau" to 0xAEAA2A,
        "minecraft:windswept_savanna" to 0xAEAA2A,
        "minecraft:badlands" to 0x9E814D, "minecraft:wooded_badlands" to 0x9E814D,
        "minecraft:eroded_badlands" to 0x9E814D,
        "minecraft:meadow" to 0x63A948,
        "minecraft:cherry_grove" to 0xF0FFAE,
        "minecraft:grove" to 0x63A948,
        "minecraft:snowy_plains" to 0x60A17B, "minecraft:ice_spikes" to 0x60A17B,
        "minecraft:snowy_slopes" to 0x60A17B, "minecraft:frozen_peaks" to 0x60A17B,
        "minecraft:jagged_peaks" to 0x60A17B, "minecraft:stony_peaks" to 0x60A17B,
        "minecraft:stony_shore" to 0x63A948,
        "minecraft:windswept_hills" to 0x63A948, "minecraft:windswept_forest" to 0x63A948,
        "minecraft:windswept_gravelly_hills" to 0x63A948,
        "minecraft:river" to 0x77AB2F, "minecraft:frozen_river" to 0x60A17B,
        "minecraft:beach" to 0x77AB2F, "minecraft:snowy_beach" to 0x60A17B,
        "minecraft:ocean" to 0x77AB2F, "minecraft:deep_ocean" to 0x77AB2F,
        "minecraft:cold_ocean" to 0x60A17B, "minecraft:deep_cold_ocean" to 0x60A17B,
        "minecraft:frozen_ocean" to 0x60A17B, "minecraft:deep_frozen_ocean" to 0x60A17B,
        "minecraft:lukewarm_ocean" to 0x77AB2F, "minecraft:deep_lukewarm_ocean" to 0x77AB2F,
        "minecraft:warm_ocean" to 0x77AB2F,
        "minecraft:mushroom_fields" to 0x2BBB0F,
        "minecraft:dripstone_caves" to 0x63A948, "minecraft:lush_caves" to 0x63A948,
        "minecraft:deep_dark" to 0x63A948,
        "minecraft:the_end" to 0x77AB2F, "minecraft:end_barrens" to 0x77AB2F,
        "minecraft:end_highlands" to 0x77AB2F, "minecraft:end_midlands" to 0x77AB2F,
        "minecraft:small_end_islands" to 0x77AB2F,
        "minecraft:nether_wastes" to 0xAEAA2A, "minecraft:soul_sand_valley" to 0xAEAA2A,
        "minecraft:crimson_forest" to 0xAEAA2A, "minecraft:warped_forest" to 0xAEAA2A,
        "minecraft:basalt_deltas" to 0xAEAA2A
    )

    private val WATER = mapOf(
        "minecraft:ocean" to 0x1787D4, "minecraft:deep_ocean" to 0x1787D4,
        "minecraft:lukewarm_ocean" to 0x1787D4, "minecraft:deep_lukewarm_ocean" to 0x1787D4,
        "minecraft:cold_ocean" to 0x2570B5, "minecraft:deep_cold_ocean" to 0x2570B5,
        "minecraft:frozen_ocean" to 0x2570B5, "minecraft:deep_frozen_ocean" to 0x2570B5,
        "minecraft:warm_ocean" to 0x43D5EE,
        "minecraft:river" to 0x1787D4, "minecraft:frozen_river" to 0x2570B5,
        "minecraft:swamp" to 0x617B64, "minecraft:mangrove_swamp" to 0x617B64,
        "minecraft:badlands" to 0xA9B09C, "minecraft:wooded_badlands" to 0xA9B09C,
        "minecraft:eroded_badlands" to 0xA9B09C,
        "minecraft:savanna" to 0xA9B09C, "minecraft:savanna_plateau" to 0xA9B09C,
        "minecraft:windswept_savanna" to 0xA9B09C,
        "minecraft:desert" to 0x32A598,
        "minecraft:jungle" to 0x14A085, "minecraft:bamboo_jungle" to 0x14A085,
        "minecraft:sparse_jungle" to 0x14A085,
        "minecraft:beach" to 0x157CBD, "minecraft:snowy_beach" to 0x2570B5,
        "minecraft:stony_shore" to 0x007BF7,
        "minecraft:mushroom_fields" to 0x8A8997,
        "minecraft:the_end" to 0x3F76E4, "minecraft:end_barrens" to 0x3F76E4,
        "minecraft:end_highlands" to 0x3F76E4, "minecraft:end_midlands" to 0x3F76E4,
        "minecraft:small_end_islands" to 0x3F76E4
    )

    private const val DEFAULT_GRASS = 0x91BD59
    private const val DEFAULT_FOLIAGE = 0x77AB2F
    private const val DEFAULT_WATER = 0x3F76E4

    /** Grass tint (0xRRGGBB) for a biome id; plains color for unknown biomes. */
    fun grassColor(biome: String?): Int {
        biome ?: return DEFAULT_GRASS
        return GRASS[biome] ?: DEFAULT_GRASS
    }

    /** Foliage tint (0xRRGGBB) for a biome id; plains color for unknown biomes. */
    fun foliageColor(biome: String?): Int {
        biome ?: return DEFAULT_FOLIAGE
        return FOLIAGE[biome] ?: DEFAULT_FOLIAGE
    }

    /** Water tint (0xRRGGBB) for a biome id; plains blue for unknown biomes. */
    fun waterColor(biome: String?): Int {
        biome ?: return DEFAULT_WATER
        return WATER[biome] ?: DEFAULT_WATER
    }

    /** Split 0xRRGGBB into float RGB (0..1). */
    fun toFloatRgb(color: Int): FloatArray =
        floatArrayOf(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f
        )

    /** Same as [toFloatRgb] but with an explicit alpha (0..1) as the 4th component. */
    fun toFloatRgba(color: Int, alpha: Float): FloatArray =
        floatArrayOf(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            alpha
        )
}
