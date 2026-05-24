package com.zaralynchisel.renderengine

/**
 * Minecraft MapColor palette (1.21.1, 61 base colors).
 * Source: https://minecraft.wiki/w/Map_item_format
 */
object MapColorPalette {
    private val COLORS = intArrayOf(
        0x00000000.toInt(), // 0  NONE
        0xFF7FB238.toInt(), // 1  GRASS
        0xFFF7E9A3.toInt(), // 2  SAND
        0xFFC7C7C7.toInt(), // 3  WOOL
        0xFFFF0000.toInt(), // 4  FIRE
        0xFFA0A0FF.toInt(), // 5  ICE
        0xFFA7A7A7.toInt(), // 6  METAL
        0xFF007C00.toInt(), // 7  PLANT
        0xFFFFFFFF.toInt(), // 8  SNOW
        0xFFA4A8B8.toInt(), // 9  CLAY
        0xFF976D4D.toInt(), // 10 DIRT
        0xFF707070.toInt(), // 11 STONE
        0xFF4040FF.toInt(), // 12 WATER
        0xFF8F7748.toInt(), // 13 WOOD
        0xFFFFFCF5.toInt(), // 14 QUARTZ
        0xFFD87F33.toInt(), // 15 COLOR_ORANGE
        0xFFB24CD8.toInt(), // 16 COLOR_MAGENTA
        0xFF6699D8.toInt(), // 17 COLOR_LIGHT_BLUE
        0xFFE5E533.toInt(), // 18 COLOR_YELLOW
        0xFF7FCC19.toInt(), // 19 COLOR_LIGHT_GREEN
        0xFFF27FA5.toInt(), // 20 COLOR_PINK
        0xFF4C4C4C.toInt(), // 21 COLOR_GRAY
        0xFF999999.toInt(), // 22 COLOR_LIGHT_GRAY
        0xFF4C7F99.toInt(), // 23 COLOR_CYAN
        0xFF7F3FB2.toInt(), // 24 COLOR_PURPLE
        0xFF334CB2.toInt(), // 25 COLOR_BLUE
        0xFF664C33.toInt(), // 26 COLOR_BROWN
        0xFF667F33.toInt(), // 27 COLOR_GREEN
        0xFF993333.toInt(), // 28 COLOR_RED
        0xFF191919.toInt(), // 29 COLOR_BLACK
        0xFFFAEE4D.toInt(), // 30 GOLD
        0xFF5CDBD5.toInt(), // 31 DIAMOND
        0xFF4A80FF.toInt(), // 32 LAPIS
        0xFF00D93A.toInt(), // 33 EMERALD
        0xFF815631.toInt(), // 34 PODZOL
        0xFF700200.toInt(), // 35 NETHER
        0xFFD1B1A1.toInt(), // 36 TERRACOTTA_WHITE
        0xFF9F5224.toInt(), // 37 TERRACOTTA_ORANGE
        0xFF95576C.toInt(), // 38 TERRACOTTA_MAGENTA
        0xFF706C8A.toInt(), // 39 TERRACOTTA_LIGHT_BLUE
        0xFFBA8524.toInt(), // 40 TERRACOTTA_YELLOW
        0xFF677535.toInt(), // 41 TERRACOTTA_LIGHT_GREEN
        0xFFA04D4E.toInt(), // 42 TERRACOTTA_PINK
        0xFF392923.toInt(), // 43 TERRACOTTA_GRAY
        0xFF876B62.toInt(), // 44 TERRACOTTA_LIGHT_GRAY
        0xFF575C5C.toInt(), // 45 TERRACOTTA_CYAN
        0xFF7A4958.toInt(), // 46 TERRACOTTA_PURPLE
        0xFF4C3E5C.toInt(), // 47 TERRACOTTA_BLUE
        0xFF4C3223.toInt(), // 48 TERRACOTTA_BROWN
        0xFF4C522A.toInt(), // 49 TERRACOTTA_GREEN
        0xFF8E3C2E.toInt(), // 50 TERRACOTTA_RED
        0xFF251610.toInt(), // 51 TERRACOTTA_BLACK
        0xFFBD3031.toInt(), // 52 CRIMSON_NYLIUM
        0xFF943F61.toInt(), // 53 CRIMSON_STEM
        0xFF5C191D.toInt(), // 54 CRIMSON_HYPHAE
        0xFF167E86.toInt(), // 55 WARPED_NYLIUM
        0xFF3A8E8C.toInt(), // 56 WARPED_STEM
        0xFF562C3E.toInt(), // 57 WARPED_HYPHAE
        0xFF14B485.toInt(), // 58 WARPED_WART_BLOCK
        0xFF646464.toInt(), // 59 DEEPSLATE
        0xFFD8AF93.toInt(), // 60 RAW_IRON
        0xFF7FA796.toInt(), // 61 GLOW_LICHEN
    )

    fun getColor(id: Int, shade: Int = 0): Int {
        if (id < 0 || id >= COLORS.size) return 0xFF000000.toInt()
        if (shade == 0) return COLORS[id]
        val c = COLORS[id]
        val r = ((c shr 16) and 0xFF) * when (shade) {
            1 -> 0.71; 2 -> 0.55; 3 -> 0.86; else -> 1.0
        }
        val g = ((c shr 8) and 0xFF) * when (shade) {
            1 -> 0.71; 2 -> 0.55; 3 -> 0.86; else -> 1.0
        }
        val b = (c and 0xFF) * when (shade) {
            1 -> 0.71; 2 -> 0.55; 3 -> 0.86; else -> 1.0
        }
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    fun getMapColorId(blockName: String): Int {
        val name = blockName.removePrefix("minecraft:")
        return when {
            name == "air" || name == "cave_air" || name == "void_air" || name == "barrier" || name == "light" -> 0
            name == "grass_block" || name == "short_grass" || name == "tall_grass" || name == "fern" || name == "large_fern" || name == "vine" || name == "lily_pad" || name == "sugar_cane" || name == "bamboo" -> 1
            name.contains("leaves") || name == "azalea" || name == "flowering_azalea" || name == "mangrove_roots" || name == "moss_block" || name == "moss_carpet" -> 7
            name == "sand" || name == "red_sand" || name == "suspicious_sand" -> 2
            name.startsWith("sandstone") -> 2
            name == "end_stone" || name == "end_stone_bricks" || name == "bone_block" -> 2
            name == "stone" || name == "cobblestone" || name == "mossy_cobblestone" || name == "gravel" || name == "suspicious_gravel" -> 11
            name == "andesite" || name == "polished_andesite" -> 11
            name == "diorite" || name == "polished_diorite" -> 14
            name == "granite" || name == "polished_granite" -> 15
            name.startsWith("deepslate") || name == "deepslate" -> 59
            name == "tuff" || name.startsWith("tuff_") || name == "basalt" || name == "polished_basalt" -> 21
            name == "calcite" -> 14
            name == "bedrock" -> 29
            name == "obsidian" || name == "crying_obsidian" -> 29
            name == "dirt" || name == "coarse_dirt" || name == "rooted_dirt" || name == "dirt_path" || name == "farmland" || name == "mud" || name == "packed_mud" || name == "mud_bricks" -> 10
            name == "podzol" -> 34
            name == "mycelium" -> 24
            name.contains("_log") || name.contains("_wood") -> when {
                name.contains("oak") || name.contains("spruce") || name.contains("birch") -> 13
                name.contains("jungle") || name.contains("acacia") -> 15
                name.contains("dark") -> 26
                name.contains("mangrove") -> 35
                name.contains("cherry") -> 20
                name.contains("crimson") -> 53
                name.contains("warped") -> 56
                else -> 13
            }
            name.contains("planks") || name == "bookshelf" || name == "crafting_table" || name == "note_block" -> when {
                name.contains("oak") || name.contains("spruce") || name.contains("birch") -> 13
                name.contains("jungle") || name.contains("acacia") -> 15
                name.contains("dark") -> 26
                name.contains("mangrove") -> 35
                name.contains("cherry") -> 20
                name.contains("crimson") -> 53
                name.contains("warped") -> 56
                else -> 13
            }
            name == "water" || name == "bubble_column" -> 12
            name == "ice" || name == "packed_ice" || name == "blue_ice" || name == "frosted_ice" -> 5
            name == "snow" || name == "snow_block" || name == "powder_snow" || name == "white_wool" -> 8
            name == "quartz_block" || name.startsWith("quartz_") || name == "smooth_quartz" -> 14
            name == "clay" -> 9
            name.contains("terracotta") -> {
                val tc = name.removePrefix("_").removeSuffix("terracotta").removeSuffix("_terracotta")
                when {
                    name.contains("white") -> 36; name.contains("orange") -> 37
                    name.contains("magenta") -> 38; name.contains("light_blue") -> 39
                    name.contains("yellow") -> 40; name.contains("lime") -> 41
                    name.contains("pink") -> 42; name.contains("gray") && !name.contains("light") -> 43
                    name.contains("light_gray") -> 44; name.contains("cyan") -> 45
                    name.contains("purple") -> 46; name.contains("blue") -> 47
                    name.contains("brown") -> 48; name.contains("green") -> 49
                    name.contains("red") -> 50; name.contains("black") -> 51
                    else -> 10
                }
            }
            name.contains("concrete") -> when {
                name.contains("white") -> 8; name.contains("orange") -> 15
                name.contains("magenta") -> 16; name.contains("light_blue") -> 17
                name.contains("yellow") -> 18; name.contains("lime") -> 19
                name.contains("pink") -> 20; name.contains("gray") && !name.contains("light") -> 21
                name.contains("light_gray") -> 22; name.contains("cyan") -> 23
                name.contains("purple") -> 24; name.contains("blue") -> 25
                name.contains("brown") -> 26; name.contains("green") -> 27
                name.contains("red") -> 28; name.contains("black") -> 29
                else -> 6
            }
            name.contains("wool") || name.contains("carpet") -> when {
                name.contains("white") -> 8; name.contains("orange") -> 15
                name.contains("magenta") -> 16; name.contains("light_blue") -> 17
                name.contains("yellow") -> 18; name.contains("lime") -> 19
                name.contains("pink") -> 20; name.contains("gray") && !name.contains("light") -> 21
                name.contains("light_gray") -> 22; name.contains("cyan") -> 23
                name.contains("purple") -> 24; name.contains("blue") -> 25
                name.contains("brown") -> 26; name.contains("green") -> 27
                name.contains("red") -> 28; name.contains("black") -> 29
                else -> 3
            }
            name == "iron_block" || name == "iron_ore" || name.contains("iron_ore") || name == "raw_iron_block" -> 6
            name == "gold_block" || name == "gold_ore" || name.contains("gold_ore") || name == "raw_gold_block" -> 30
            name == "diamond_block" || name == "diamond_ore" || name.contains("diamond_ore") -> 31
            name == "emerald_block" || name == "emerald_ore" || name.contains("emerald_ore") -> 33
            name == "lapis_block" || name == "lapis_ore" || name.contains("lapis_ore") -> 32
            name == "redstone_block" || name == "redstone_ore" || name.contains("redstone_ore") -> 28
            name == "copper_block" || name.startsWith("copper_") || name.contains("copper_ore") -> 15
            name == "coal_block" || name == "coal_ore" || name.contains("coal_ore") -> 29
            name == "netherrack" || name == "nether_bricks" || name == "red_nether_bricks" || name == "ancient_debris" -> 35
            name == "crimson_nylium" -> 52; name == "warped_nylium" -> 55
            name == "soul_sand" || name == "soul_soil" -> 26
            name == "blackstone" || name.startsWith("blackstone_") -> 29
            name == "glowstone" || name == "shroomlight" || name == "sponge" || name == "wet_sponge" || name == "hay_block" || name == "bee_nest" || name == "beehive" -> 18
            name == "pumpkin" || name == "carved_pumpkin" || name == "jack_o_lantern" -> 15
            name == "melon" -> 19
            name.contains("coral") -> when {
                name.contains("red") || name.contains("fire") -> 4
                name.contains("blue") || name.contains("tube") -> 12
                name.contains("yellow") || name.contains("horn") -> 18
                name.contains("pink") || name.contains("brain") -> 20
                name.contains("purple") || name.contains("bubble") -> 24
                else -> 20
            }
            name == "sculk" || name.startsWith("sculk_") -> 29
            name == "kelp" || name == "seagrass" || name == "tall_seagrass" -> 7
            else -> 0
        }
    }
}