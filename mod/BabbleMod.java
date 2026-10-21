package dev.babble;

// Version-templated source: build.py keeps the //#if branch matching the target Minecraft
// version (MC = major*10000 + minor*100 + patch, e.g. 1.20.1 -> 12001, 26.1 -> 260100) and,
// for MC < 11700, maps the Mojang class names below to their 1.16 names.

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.babble.core.Log;
import dev.babble.core.VoiceConfig;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
//#if MC >= 11700
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
//#else
//$import net.minecraft.command.CommandSource;
//$import net.minecraft.command.Commands;
//$import net.minecraft.util.math.BlockPos;
//$import net.minecraft.world.server.ServerWorld;
//$import net.minecraft.entity.player.ServerPlayerEntity;
//$import net.minecraft.block.Block;
//$import net.minecraft.block.Blocks;
//$import net.minecraft.block.BlockState;
//#endif
import net.minecraftforge.api.distmarker.Dist;
//#if MC < 12106
import net.minecraftforge.common.MinecraftForge;
//#endif
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(BabbleMod.MOD_ID)
public final class BabbleMod {
    public static final String MOD_ID = "babble";

    private static VoiceConfig config;

    public BabbleMod() {
//#if MC >= 12106
        RegisterCommandsEvent.BUS.addListener(BabbleMod::registerCommands);
//#else
//$     MinecraftForge.EVENT_BUS.addListener(BabbleMod::registerCommands);
//#endif
        VoiceConfig cfg = config(); // also writes the documented config file on servers
        if (FMLEnvironment.dist == Dist.CLIENT) {
            VoiceClient.start(cfg);
        }
    }

    /** Shared by client and server; on a dedicated server only the server-side keys matter. */
    static synchronized VoiceConfig config() {
        if (config == null) {
            config = VoiceConfig.load(new File(FMLPaths.CONFIGDIR.get().toFile(), "babble.properties"));
        }
        return config;
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        // /babble-break <radius> <block ids...>  — sent by the client when it hears a block name.
        // ("/babble" itself is the player's client-side command.) No permission requirement, so it works
        // in survival single-player and on servers with the mod.
        event.getDispatcher().register(Commands.literal("babble-break")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .then(Commands.argument("blocks", StringArgumentType.greedyString())
                                .executes(ctx -> breakBlocks(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        StringArgumentType.getString(ctx, "blocks"))))));
    }

    private static int breakBlocks(CommandSourceStack source, int requestedRadius, String blockIds)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        VoiceConfig cfg = config();
        Set<String> targets = new HashSet<String>(Arrays.asList(blockIds.trim().split("\\s+")));
        targets.removeAll(Arrays.asList(cfg.excludedBlocks.split("\\s*,\\s*")));
        if (targets.isEmpty()) return 0;

        int r = Math.max(1, Math.min(requestedRadius, cfg.maxRadius));
        BlockPos center = player.blockPosition();
        Map<Block, Boolean> isTarget = new IdentityHashMap<Block, Boolean>();
        int destroyed = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            int dx = pos.getX() - center.getX(), dy = pos.getY() - center.getY(), dz = pos.getZ() - center.getZ();
            if (cfg.sphere && dx * dx + dy * dy + dz * dz > r * r + r) continue;
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            Boolean hit = isTarget.get(block);
            if (hit == null) {
                hit = targets.contains(String.valueOf(ForgeRegistries.BLOCKS.getKey(block)));
                isTarget.put(block, hit);
            }
            if (!hit) continue;
            BlockPos p = pos.immutable();
            level.destroyBlock(p, cfg.dropItems, player);
            // destroyBlock leaves fluids (water/lava) in place; clear them explicitly.
            if (level.getBlockState(p).getBlock() == block) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
            destroyed++;
        }
        Log.debug(player.getName().getString() + " destroyed " + destroyed + " x " + targets);
        return destroyed;
    }
}
