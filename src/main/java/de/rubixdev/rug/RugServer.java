package de.rubixdev.rug;


import carpet.CarpetExtension;
import carpet.CarpetServer;
import carpet.api.settings.CarpetRule;
import carpet.api.settings.RuleHelper;
import carpet.script.Module;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Lists;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import de.rubixdev.rug.commands.*;
import de.rubixdev.rug.util.CraftingRule;
import de.rubixdev.rug.util.Logging;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.item.BlockItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ReloadCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RugServer implements CarpetExtension, ModInitializer {
    public static final String VERSION = "1.3.5";
    public static final Logger LOGGER = LogManager.getLogger("Rug");

    private static MinecraftServer minecraftServer;

    @Override
    public String version() {
        return "rug";
    }

    @Override
    public void onInitialize() {
        CarpetServer.manageExtension(new RugServer());
    }

    @Override
    public void onGameStarted() {
        LOGGER.info("Rug Mod v" + VERSION + " loaded!");

        CarpetServer.settingsManager.parseSettingsClass(RugSettings.class);
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        InputStream langFile = RugServer.class.getClassLoader()
            .getResourceAsStream("assets/rug/lang/%s.json5".formatted(lang));
        if (langFile == null) {
            // we don't have that language
            return Collections.emptyMap();
        }
        String jsonData;
        try {
            jsonData = IOUtils.toString(langFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
        Gson gson = new GsonBuilder().setLenient().create(); // lenient allows for comments
        return gson.fromJson(jsonData, new TypeToken<Map<String, String>>() {}.getType());
    }

    @Override
    public void registerCommands(
        CommandDispatcher<ServerCommandSource> dispatcher,
        CommandRegistryAccess commandBuildContext
    ) {
        SlimeChunkCommand.register(dispatcher);
        FrameCommand.register(dispatcher);
        SkullCommand.register(dispatcher);
        SudoCommand.register(dispatcher);
        PeekCommand.register(dispatcher);
        MaxEffectCommand.register(dispatcher);
        ModsCommand.register(dispatcher);
    }

    @Override
    public void onServerClosed(MinecraftServer server) {
        File datapackPath = new File(server.getSavePath(WorldSavePath.DATAPACKS).toString() + "/RugData/data/");
        if (Files.isDirectory(datapackPath.toPath())) {
            try {
                FileUtils.deleteDirectory(datapackPath);
            } catch (IOException e) {
                Logging.logStackTrace(e);
            }
        }
    }

    @Override
    public void onServerLoaded(MinecraftServer server) {
        minecraftServer = server;

        UseBlockCallback.EVENT.register(( (player, world, hand, hitResult) -> {
            if (RugSettings.easyHarvesting.equals("off") || world.isClient() || hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            ItemStack tool = player != null ? player.getStackInHand(hand) : ItemStack.EMPTY;
            if (RugSettings.easyHarvesting.equals("require_hoe") && !( tool.getItem() instanceof HoeItem )) {
                return ActionResult.PASS;
            }
            boolean allowsHarvestFromTop = RugSettings.easyHarvesting.equals("require_hoe")
                || hitResult.getSide() != Direction.UP;
            boolean allowsHarvestFromBottom = RugSettings.easyHarvesting.equals("require_hoe")
                || hitResult.getSide() != Direction.DOWN;

            if (isMature(state)) {
                List<ItemStack> droppedItems = Block.getDroppedStacks(
                    state,
                    (ServerWorld) world,
                    pos,
                    null,
                    player,
                    tool
                );
                boolean removedSeed = false;
                for (ItemStack itemStack : droppedItems) {
                    if (!removedSeed) {
                        Item item = itemStack.getItem();
                        if (item instanceof BlockItem && ( (BlockItem) item ).getBlock() == block) {
                            itemStack.decrement(1);
                            removedSeed = true;
                        }
                    }
                    Block.dropStack(world, pos, itemStack);
                }

                world.setBlockState(
                    pos,
                    removedSeed ? state.with(getAgeProperty(block), 0) : Blocks.AIR.getDefaultState()
                );
            } else if (List.of(
                Blocks.SUGAR_CANE,
                Blocks.CACTUS,
                Blocks.BAMBOO,
                Blocks.KELP_PLANT,
                Blocks.TWISTING_VINES_PLANT
            ).contains(state.getBlock()) && allowsHarvestFromTop) {
                if (!harvestStemPlant(pos, world, state.getBlock(), Direction.UP)) return ActionResult.PASS;
            } else if (state.isOf(Blocks.KELP) && allowsHarvestFromTop) {
                if (!harvestStemPlant(pos, world, Blocks.KELP_PLANT, Direction.UP)) return ActionResult.PASS;
            } else if (state.isOf(Blocks.TWISTING_VINES) && allowsHarvestFromTop) {
                if (!harvestStemPlant(pos, world, Blocks.TWISTING_VINES_PLANT, Direction.UP)) return ActionResult.PASS;
            } else if (List.of(Blocks.WEEPING_VINES_PLANT, Blocks.CAVE_VINES_PLANT).contains(state.getBlock())
                && allowsHarvestFromBottom) {
                    if (!harvestStemPlant(pos, world, state.getBlock(), Direction.DOWN)) return ActionResult.PASS;
                } else if (state.isOf(Blocks.WEEPING_VINES) && allowsHarvestFromBottom) {
                    if (!harvestStemPlant(pos, world, Blocks.WEEPING_VINES_PLANT, Direction.DOWN))
                        return ActionResult.PASS;
                } else if (state.isOf(Blocks.CAVE_VINES) && allowsHarvestFromBottom) {
                    if (!harvestStemPlant(pos, world, Blocks.CAVE_VINES_PLANT, Direction.DOWN))
                        return ActionResult.PASS;
                } else {
                    return ActionResult.PASS;
                }

            if (RugSettings.easyHarvesting.equals("require_hoe") && player != null) {
                tool.damage(1, player, p -> p.sendToolBreakStatus(hand));
            }
            return ActionResult.SUCCESS;
        } ));
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean harvestStemPlant(BlockPos pos, World world, Block stem, Direction growDirection) {
        int count = 1;
        BlockPos root = pos.offset(growDirection.getOpposite());
        while (world.getBlockState(root).isOf(stem)) {
            count++;
            root = root.offset(growDirection.getOpposite());
        }

        if (count == 1 && !world.getBlockState(pos.offset(growDirection)).isOf(stem)) { return false; }
        world.breakBlock(root.offset(growDirection, 2), true);
        return true;
    }

    @Override
    public void onServerLoadedWorlds(MinecraftServer server) {
        String datapackPath = server.getSavePath(WorldSavePath.DATAPACKS).toString();
        if (Files.isDirectory(new File(datapackPath + "/Rug_flexibleData/").toPath())) {
            try {
                FileUtils.deleteDirectory(new File(datapackPath + "/Rug_flexibleData/"));
            } catch (IOException e) {
                Logging.logStackTrace(e);
            }
        }
        datapackPath += "/RugData/";
        boolean isFirstLoad = !Files.isDirectory(new File(datapackPath).toPath());

        try {
            Files.createDirectories(new File(datapackPath + "data/rug/recipes").toPath());
            Files.createDirectories(new File(datapackPath + "data/rug/advancements").toPath());
            Files.createDirectories(new File(datapackPath + "data/minecraft/recipes").toPath());
            copyFile("assets/rug/RugDataStorage/pack.mcmeta", datapackPath + "pack.mcmeta");
        } catch (IOException e) {
            Logging.logStackTrace(e);
        }

        copyFile(
            "assets/rug/RugDataStorage/rug/advancements/root.json",
            datapackPath + "data/rug/advancements/root.json"
        );

        for (Field f : RugSettings.class.getDeclaredFields()) {
            CraftingRule craftingRule = f.getAnnotation(CraftingRule.class);
            if (craftingRule == null) continue;
            registerCraftingRule(
                craftingRule.name().isEmpty() ? f.getName() : craftingRule.name(),
                craftingRule.recipes(),
                craftingRule.recipeNamespace(),
                datapackPath + "data/"
            );
        }
        reload();
        if (isFirstLoad) {
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), "datapack enable \"file/RugData\"");
        }
    }

    private void registerCraftingRule(String ruleName, String[] recipes, String recipeNamespace, String dataPath) {
        updateCraftingRule(
            CarpetServer.settingsManager.getCarpetRule(ruleName),
            recipes,
            recipeNamespace,
            dataPath,
            ruleName
        );

        CarpetServer.settingsManager.registerRuleObserver((source, rule, s) -> {
            if (rule.name().equals(ruleName)) {
                updateCraftingRule(rule, recipes, recipeNamespace, dataPath, ruleName);
                reload();
            }
        });
    }

    private void updateCraftingRule(
        CarpetRule<?> rule,
        String[] recipes,
        String recipeNamespace,
        String datapackPath,
        String ruleName
    ) {
        ruleName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, ruleName);

        if (rule.type() == String.class) {
            String value = RuleHelper.toRuleString(rule.value());

            List<String> installedRecipes = Lists.newArrayList();
            try {
                Stream<Path> fileStream = Files.list(new File(datapackPath + recipeNamespace, "recipes").toPath());
                fileStream.forEach(( path -> {
                    for (String recipeName : recipes) {
                        String fileName = path.getFileName().toString();
                        if (fileName.startsWith(recipeName)) {
                            installedRecipes.add(fileName);
                        }
                    }
                } ));
                fileStream.close();
            } catch (IOException e) {
                Logging.logStackTrace(e);
            }

            deleteRecipes(installedRecipes.toArray(new String[0]), recipeNamespace, datapackPath, ruleName, false);

            if (recipeNamespace.equals("rug")) {
                List<String> installedAdvancements = Lists.newArrayList();
                try {
                    Stream<Path> fileStream = Files.list(new File(datapackPath, "rug/advancements").toPath());
                    String finalRuleName = ruleName;
                    fileStream.forEach(( path -> {
                        String fileName = path.getFileName().toString().replace(".json", "");
                        if (fileName.startsWith(finalRuleName)) {
                            installedAdvancements.add(fileName);
                        }
                    } ));
                    fileStream.close();
                } catch (IOException e) {
                    Logging.logStackTrace(e);
                }
                for (String advancement : installedAdvancements.toArray(new String[0])) {
                    removeAdvancement(datapackPath, advancement);
                }
            }

            if (!value.equals("off")) {
                List<String> tempRecipes = Lists.newArrayList();
                for (String recipeName : recipes) {
                    tempRecipes.add(recipeName + "_" + value + ".json");
                }

                copyRecipes(tempRecipes.toArray(new String[0]), recipeNamespace, datapackPath, ruleName + "_" + value);
            }
        } else if (rule.type() == int.class && (Integer) rule.value() > 0) {
            copyRecipes(recipes, recipeNamespace, datapackPath, ruleName);

            int value = (Integer) rule.value();
            for (String recipeName : recipes) {
                String filePath = datapackPath + recipeNamespace + "/recipes/" + recipeName;
                JsonObject jsonObject = readJson(filePath);
                assert jsonObject != null;
                jsonObject.getAsJsonObject("result").addProperty("count", value);
                writeJson(jsonObject, filePath);
            }
        } else if (rule.type() == boolean.class && RuleHelper.getBooleanValue(rule)) {
            copyRecipes(recipes, recipeNamespace, datapackPath, ruleName);
        } else {
            deleteRecipes(recipes, recipeNamespace, datapackPath, ruleName, true);
        }
    }

    private void copyRecipes(String[] recipes, String recipeNamespace, String datapackPath, String ruleName) {
        for (String recipeName : recipes) {
            copyFile(
                "assets/rug/RugDataStorage/" + recipeNamespace + "/recipes/" + recipeName,
                datapackPath + recipeNamespace + "/recipes/" + recipeName
            );
        }
        if (recipeNamespace.equals("rug")) {
            writeAdvancement(datapackPath, ruleName, recipes);
        }
    }

    private void deleteRecipes(
        String[] recipes,
        String recipeNamespace,
        String datapackPath,
        String ruleName,
        boolean removeAdvancement
    ) {
        for (String recipeName : recipes) {
            try {
                Files.deleteIfExists(new File(datapackPath + recipeNamespace + "/recipes", recipeName).toPath());
            } catch (IOException e) {
                Logging.logStackTrace(e);
            }
        }
        if (removeAdvancement && recipeNamespace.equals("rug")) {
            removeAdvancement(datapackPath, ruleName);
        }
    }

    private void writeAdvancement(String datapackPath, String ruleName, String[] recipes) {
        copyFile(
            "assets/rug/RugDataStorage/rug/advancements/recipe_rule.json",
            datapackPath + "rug/advancements/" + ruleName + ".json"
        );

        JsonObject advancementJson = readJson(datapackPath + "rug/advancements/" + ruleName + ".json");
        assert advancementJson != null;
        JsonArray recipeRewards = advancementJson.getAsJsonObject("rewards").getAsJsonArray("recipes");

        for (String recipeName : recipes) {
            recipeRewards.add("rug:" + recipeName.replace(".json", ""));
        }
        writeJson(advancementJson, datapackPath + "rug/advancements/" + ruleName + ".json");
    }

    private void removeAdvancement(String datapackPath, String ruleName) {
        try {
            Files.deleteIfExists(new File(datapackPath + "rug/advancements/" + ruleName + ".json").toPath());
        } catch (IOException e) {
            Logging.logStackTrace(e);
        }
    }

    private JsonObject readJson(String filePath) {
        try {
            FileReader reader = new FileReader(filePath);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void writeJson(JsonObject jsonObject, String filePath) {
        try {
            FileWriter writer = new FileWriter(filePath);
            writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject));
            writer.close();
        } catch (IOException e) {
            Logging.logStackTrace(e);
        }
    }

    private void reload() {
        ResourcePackManager resourcePackManager = minecraftServer.getDataPackManager();
        resourcePackManager.scanPacks();
        Collection<String> collection = Lists.newArrayList(resourcePackManager.getEnabledNames());
        collection.add("RugData");

        ReloadCommand.tryReloadDataPacks(collection, minecraftServer.getCommandSource());
    }

    private static boolean isMature(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock) {
            return ( (CropBlock) block ).isMature(state);
        } else if (block instanceof NetherWartBlock) {
            return state.get(NetherWartBlock.AGE) == 3;
        } else if (block instanceof CocoaBlock) { return state.get(CocoaBlock.AGE) == 2; }
        return false;
    }

    private static IntProperty getAgeProperty(Block block) {
        if (block instanceof CropBlock) {
            return ( (CropBlock) block ).getAgeProperty();
        } else if (block instanceof NetherWartBlock) {
            return NetherWartBlock.AGE;
        } else if (block instanceof CocoaBlock) { return CocoaBlock.AGE; }
        return null;
    }

    private void copyFile(String resourcePath, String targetPath) {
        InputStream source = Module.class.getClassLoader().getResourceAsStream(resourcePath);
        Path target = new File(targetPath).toPath();

        try {
            assert source != null;
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logging.logStackTrace(e);
        } catch (NullPointerException e) {
            LOGGER.error("Resource '" + resourcePath + "' is null:");
            Logging.logStackTrace(e);
        }
    }

    public static void savePlayerData(ServerPlayerEntity player) {
        File playerDataDir = minecraftServer.getSavePath(WorldSavePath.PLAYERDATA).toFile();
        try {
            NbtCompound compoundTag = player.writeNbt(new NbtCompound());
            File file = File.createTempFile(player.getUuidAsString() + "-", ".dat", playerDataDir);
            NbtIo.writeCompressed(compoundTag, file);
            File file2 = new File(playerDataDir, player.getUuidAsString() + ".dat");
            File file3 = new File(playerDataDir, player.getUuidAsString() + ".dat_old");
            Util.backupAndReplace(file2, file, file3);
        } catch (Exception ignored) {
            LOGGER.warn("Failed to save player data for " + player.getName().getString());
        }
    }
}
