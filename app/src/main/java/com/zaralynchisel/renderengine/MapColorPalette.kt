package com.zaralynchisel.renderengine

/**
 * Minecraft MapColor palette.
 * Based on Minecraft 1.21 MapColor class.
 * Each MapColor has 4 brightness levels: base, shade1 (darker), shade2, shade3 (lightest).
 */
object MapColorPalette {
    // Base colors (index 0-63 in MC's palette)
    private val COLORS = intArrayOf(
        0xFF000000.toInt(), // 0  NONE (transparent)
        0xFF7FB238.toInt(), // 1  GRASS light (green)
        0xFF79C06A.toInt(), // 2  SAND (tan)
        0xFFA0A0A0.toInt(), // 3  WOOL (gray)
        0xFFFF0000.toInt(), // 4  FIRE (red)
        0xFF00FFFF.toInt(), // 5  ICE (cyan)
        0xFF8C8C8C.toInt(), // 6  METAL (light gray)
        0xFF5B8C31.toInt(), // 7  PLANT (dark green)
        0xFFFFFFFF.toInt(), // 8  SNOW (white)
        0xFFD4D4D4.toInt(), // 9  CLAY (light gray 2)
        0xFFA0653B.toInt(), // 10 DIRT (brown)
        0xFF8B6B4A.toInt(), // 11 STONE (dark brown)
        0xFF7FB2C0.toInt(), // 12 WATER (blue)
        0xFF4A5263.toInt(), // 13 WOOD (dark gray)
        0xFF7F6654.toInt(), // 14 QUARTZ?
        0xFFFFA500.toInt(), // 15 ADOBE? (orange)
        0xFFFF69B4.toInt(), // 16 MAGENTA?
        0xFF87CEFA.toInt(), // 17 LIGHT_BLUE?
        0xFFFFFF00.toInt(), // 18 YELLOW
        0xFF00FF00.toInt(), // 19 LIME?
        0xFFFFC0CB.toInt(), // 20 PINK?
        0xFF808080.toInt(), // 21 GRAY?
        0xFFC0C0C0.toInt(), // 22 LIGHT_GRAY?
        0xFF008080.toInt(), // 23 TEAL?
        0xFF800080.toInt(), // 24 PURPLE?
        0xFF000080.toInt(), // 25 NAVY?
        0xFF606028.toInt(), // 26 EMERALD? (dark green)
        0xFFC78168.toInt(), // 27 TERRACOTTA_WHITE
        0xFFAF5227.toInt(), // 28 TERRACOTTA_ORANGE
        0xFFA85568.toInt(), // 29 TERRACOTTA_MAGENTA
        0xFF7FC5CC.toInt(), // 30 TERRACOTTA_LIGHT_BLUE
        0xFFD8B35E.toInt(), // 31 TERRACOTTA_YELLOW
        0xFF82BA47.toInt(), // 32 TERRACOTTA_LIME
        0xFFD17C8E.toInt(), // 33 TERRACOTTA_PINK
        0xFF75675D.toInt(), // 34 TERRACOTTA_GRAY
        0xFF869CAD.toInt(), // 35 TERRACOTTA_LIGHT_GRAY
        0xFF226E79.toInt(), // 36 TERRACOTTA_CYAN
        0xFF794473.toInt(), // 37 TERRACOTTA_PURPLE
        0xFF3F305D.toInt(), // 38 TERRACOTTA_BLUE
        0xFF5B4626.toInt(), // 39 TERRACOTTA_BROWN
        0xFF5F6F32.toInt(), // 40 TERRACOTTA_GREEN
        0xFFAC3B31.toInt(), // 41 TERRACOTTA_RED
        0xFF1C1B21.toInt(), // 42 TERRACOTTA_BLACK
        0xFFB28B44.toInt(), // 43 GOLD
        0xFF409194.toInt(), // 44 DIAMOND
        0xFF4F32A0.toInt(), // 45 LAPIS
        0xFF3E9A39.toInt(), // 46 EMERALD (green)
        0xFF905E3F.toInt(), // 47 PODZOL (brown)
        0xFFC53535.toInt(), // 48 NETHER
        0xFFF9F9F9.toInt(), // 49 TERRACOTTA_WHITE_ALT
        0xFFC78168.toInt(), // 50
        0xFFAF5227.toInt(), // 51
        0xFFA85568.toInt(), // 52
        0xFF7FC5CC.toInt(), // 53
        0xFFD8B35E.toInt(), // 54
        0xFF82BA47.toInt(), // 55
        0xFFD17C8E.toInt(), // 56
        0xFF75675D.toInt(), // 57
        0xFF869CAD.toInt(), // 58
        0xFF226E79.toInt(), // 59
        0xFF794473.toInt(), // 60
        0xFF3F305D.toInt(), // 61
        0xFF5B4626.toInt(), // 62
        0xFF5F6F32.toInt(), // 63
    )

    /**
     * Multiply a color by a brightness factor.
     * shade: 0=base, 1=darker, 2=darker still, 3=lightest
     */
    private fun shadeColor(baseColor: Int, shade: Int): Int {
        val r = (baseColor shr 16) and 0xFF
        val g = (baseColor shr 8) and 0xFF
        val b = baseColor and 0xFF

        val factor = when (shade) {
            0 -> 1.0
            1 -> 180.0 / 255.0
            2 -> 140.0 / 255.0
            3 -> 220.0 / 255.0
            else -> 1.0
        }

        val sr = (r * factor).toInt().coerceIn(0, 255)
        val sg = (g * factor).toInt().coerceIn(0, 255)
        val sb = (b * factor).toInt().coerceIn(0, 255)

        return (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
    }

    /**
     * Get the RGB color for a given map color id and shade.
     */
    fun getColor(id: Int, shade: Int = 0): Int {
        if (id < 0 || id >= COLORS.size) return 0xFF000000.toInt()
        return shadeColor(COLORS[id], shade)
    }

    /**
     * Map common block names to MapColor indices.
     * Returns map color ID (0-63).
     */
    fun getMapColorId(blockName: String): Int {
        val name = blockName.removePrefix("minecraft:")
        return when {
            // Grass / plants
            name.startsWith("grass") || name.contains("grass") -> 1
            name == "short_grass" || name == "tall_grass" || name == "fern" -> 1
            name == "vine" || name == "lily_pad" -> 1
            name == "sugar_cane" || name == "bamboo" -> 7

            // Leaves
            name.contains("leaves") -> 7
            name.contains("leaf") -> 7

            // Sand / clay
            name == "sand" || name == "red_sand" -> 2
            name == "sandstone" || name.contains("sandstone") -> 2
            name == "clay" -> 9

            // Stone / rock
            name == "stone" || name == "cobblestone" -> 11
            name == "stone_bricks" || name.contains("bricks") -> 11
            name == "andesite" || name == "diorite" || name == "granite" -> 11
            name == "gravel" -> 11
            name == "bedrock" -> 13
            name == "obsidian" -> 13

            // Dirt
            name == "dirt" || name == "coarse_dirt" -> 10
            name == "podzol" -> 47
            name == "farmland" -> 10
            name == "mud" || name == "mud_bricks" -> 10

            // Wood / logs
            name.contains("log") || name.contains("wood") -> 13
            name.contains("planks") -> 27  // terracotta_white (birch-like)

            // Water / ice
            name == "water" || name.contains("water") -> 12
            name == "ice" || name == "packed_ice" || name == "blue_ice" -> 5

            // Snow
            name == "snow" || name == "snow_block" || name == "powder_snow" -> 8

            // Nether blocks
            name == "netherrack" || name == "crimson_nylium" || name == "warped_nylium" -> 48
            name == "soul_sand" || name == "soul_soil" -> 48
            name == "basalt" || name == "blackstone" -> 13

            // End blocks
            name == "end_stone" -> 2  // sand-like

            // Terracotta / concrete
            name.contains("terracotta") -> when {
                name.contains("white") -> 27
                name.contains("orange") -> 28
                name.contains("red") -> 41
                name.contains("yellow") -> 31
                name.contains("green") -> 40
                name.contains("blue") || name.contains("light_blue") -> 38
                else -> 27
            }
            name.contains("concrete") -> when {
                name.contains("white") -> 8
                name.contains("black") || name.contains("gray") -> 6
                else -> 6
            }
            name.contains("wool") -> 3

            // Ores / minerals
            name == "gold_block" || name == "gold_ore" || name.contains("gold") -> 43
            name == "diamond_block" || name == "diamond_ore" -> 44
            name == "iron_block" || name == "iron_ore" || name.contains("iron") -> 6
            name == "lapis_block" || name == "lapis_ore" -> 45
            name == "emerald_block" || name == "emerald_ore" -> 46
            name == "copper_block" || name == "copper_ore" || name.contains("copper") -> 28

            // Coral / underwater
            name.contains("coral") -> when {
                name.contains("red") -> 4
                name.contains("blue") -> 12
                name.contains("yellow") -> 18
                else -> 27
            }

            // Default by material guess
            else -> 0  // transparent (air)
        }
    }
}