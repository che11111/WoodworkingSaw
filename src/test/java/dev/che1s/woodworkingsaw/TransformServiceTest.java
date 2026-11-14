package dev.che1s.woodworkingsaw;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformServiceTest {

    @Test
    void parsesRuleAmounts() {
        assertEquals(1, TransformService.parseAmount("1", 1));
        assertEquals(64, TransformService.parseAmount("64", 1));
        assertEquals(7, TransformService.parseAmount(null, 7));
        assertEquals(7, TransformService.parseAmount("", 7));
        assertEquals(7, TransformService.parseAmount("   ", 7));
        assertEquals(7, TransformService.parseAmount("abc", 7));
        assertEquals(7, TransformService.parseAmount("0", 7));
        assertEquals(7, TransformService.parseAmount("-5", 7));
        assertEquals(1, TransformService.parseAmount("null", 1));
    }

    @Test
    void splitsLargeOutputIntoValidStacks() {
        assertEquals(List.of(64, 64), TransformService.splitOutput(128, 64));
        assertEquals(List.of(1, 1), TransformService.splitOutput(2, 1));
        assertEquals(List.of(64, 64, 13), TransformService.splitOutput(141, 64));
        assertEquals(List.of(), TransformService.splitOutput(0, 64));
    }

    @Test
    void skipsMaterialsUnavailableOnTheCurrentServer() {
        Map<String, Object> rule = Map.of(
                "input", "minecraft:sulfur",
                "output", "minecraft:sulfur_spike",
                "input-amount", 1,
                "output-amount", 1);

        TransformService.RuleBuild build = TransformService.buildTransformRules(List.of(rule));

        assertEquals(Map.of(), build.rules());
        assertEquals(List.of("minecraft:sulfur", "minecraft:sulfur_spike"),
                build.unavailableItems());
    }

    @Test
    void usesTheNextRuleWhenAnEarlierTargetIsUnavailable() {
        Map<String, Object> versioned = Map.of(
                "input", "minecraft:oak_log",
                "output", "minecraft:missing_wood_block",
                "input-amount", 1,
                "output-amount", 1);
        Map<String, Object> fallback = Map.of(
                "input", "minecraft:oak_log",
                "output", "minecraft:stripped_oak_log",
                "input-amount", 1,
                "output-amount", 1);

        TransformService.RuleBuild build = TransformService.buildTransformRules(
                List.of(versioned, fallback));

        assertEquals(Material.STRIPPED_OAK_LOG, build.rules().get(Material.OAK_LOG).target());
        assertEquals(List.of("minecraft:missing_wood_block"), build.unavailableItems());
    }

    @Test
    void bundledRulesFallBackCleanlyOn12111() throws Exception {
        try (var stream = getClass().getResourceAsStream("/transformations.yml")) {
            assertNotNull(stream);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));

            TransformService.RuleBuild build = TransformService.buildTransformRules(
                    yaml.getList("rules"));

            assertEquals(Material.WHITE_CARPET,
                    build.rules().get(Material.WHITE_WOOL).target());
            assertFalse(build.rules().containsKey(
                    Material.matchMaterial("minecraft:white_wool_stairs")));
            assertTrue(build.unavailableItems().contains("minecraft:sulfur"));
        }
    }

    @Test
    void keepsOnlyTheFirstEffectiveRuleForEachInput() {
        Map<String, Object> first = Map.of(
                "input", "minecraft:oak_log",
                "output", "minecraft:stripped_oak_log",
                "input-amount", 2,
                "output-amount", 3);
        Map<String, Object> unreachable = Map.of(
                "input", "minecraft:oak_log",
                "output", "minecraft:oak_planks",
                "input-amount", 1,
                "output-amount", 4);

        Map<Material, TransformService.Rule> rules = TransformService.buildTransformMap(
                List.of(first, unreachable));

        assertEquals(1, rules.size());
        TransformService.Rule rule = rules.get(Material.OAK_LOG);
        assertEquals(Material.STRIPPED_OAK_LOG, rule.target());
        assertEquals(2, rule.inputAmount());
        assertEquals(3, rule.outputAmount());
    }

    @Test
    void cerRecipeIdsAreStableAndNamespacedPerInput() {
        assertEquals("transform/minecraft/oak_log",
                CerRecipePublisher.recipeId(Material.OAK_LOG));
    }
}
