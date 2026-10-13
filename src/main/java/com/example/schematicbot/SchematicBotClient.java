package com.example.schematicbot;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Litematica-linked builder bot (Minecraft 26.2, official Mojang names).
 * Commands: /sbot start | /sbot stop
 *
 * Human-like behaviour: builds bottom-up, nearest block first, smoothly turns its head,
 * randomised delays, occasional short pauses, walks toward out-of-reach blocks, only
 * places within normal reach. Stairs (facing + top/bottom half) and slabs
 * (top/bottom/double) are placed the same way vanilla placement decides them.
 * When you log off, the last placed block's coordinates are saved to a file and
 * (optionally) posted to a Discord webhook.
 */
public class SchematicBotClient implements ClientModInitializer {
    private static final int SCAN_RADIUS = 6;
    private static final double REACH = 4.5;
    private static final Random RNG = new Random();

    /** Where/how to click so vanilla placement produces the wanted state. */
    private record Plan(BlockPos clicked, Direction side, Vec3 hit, Float yaw) {}

    /** Last block the bot clicked, remembered so it can be reported when you log off. */
    private record LastPlacement(BlockPos pos, String block, String dimension, String server) {}

    private boolean running = false;
    private int cooldown = 0;
    private int tickCount = 0;
    private BlockPos target = null;
    /** Positions to leave alone until the given tick (server lag guard / unsupported blocks). */
    private final Map<BlockPos, Integer> skipUntil = new HashMap<>();

    private LastPlacement last = null;
    private boolean reported = true;
    private String webhookUrl = "";

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("sbot")
                .then(ClientCommands.literal("start").executes(ctx -> { start(); return 1; }))
                .then(ClientCommands.literal("stop").executes(ctx -> { stop("Stopped."); return 1; }))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        loadConfig();
        // Logging off, getting kicked, or leaving a world: stop building and report the last block.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            running = false;
            target = null;
            reportLastPlacement(false);
        });
        // Closing the game entirely: wait briefly so the webhook message actually goes out.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> reportLastPlacement(true));
    }

    private void say(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[SchematicBot] " + msg));
    }

    private boolean hasSchematic() {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        return world != null
            && !DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty();
    }

    private void start() {
        Minecraft mc = Minecraft.getInstance();
        if (!hasSchematic()) {
            say(mc, "No schematic's found");
            return;
        }
        running = true;
        target = null;
        skipUntil.clear();
        say(mc, "Started.");
    }

    private void stop(String reason) {
        Minecraft mc = Minecraft.getInstance();
        running = false;
        target = null;
        releaseKeys(mc);
        say(mc, reason);
    }

    private void releaseKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private void tick(Minecraft mc) {
        if (!running || mc.player == null || mc.level == null || mc.gameMode == null) return;
        tickCount++;

        if (!hasSchematic()) {
            stop("No schematic's found");
            return;
        }
        if (cooldown > 0) { cooldown--; return; }

        LocalPlayer player = mc.player;
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();

        if (target == null || !needsBlock(mc, schem, target)) {
            target = findNextTarget(mc, schem, player);
            if (target == null) {
                releaseKeys(mc);
                cooldown = 20 + RNG.nextInt(20);
                return;
            }
        }

        BlockState wanted = schem.getBlockState(target);
        BlockState have = mc.level.getBlockState(target);

        Item item = wanted.getBlock().asItem();
        int slot = findHotbarSlot(player, item);
        if (slot < 0) {
            releaseKeys(mc);
            say(mc, "Missing " + wanted.getBlock().getName().getString() + " in hotbar. Put it there and the bot continues.");
            cooldown = 100;
            return;
        }

        // Walk closer if out of reach.
        Vec3 eye = player.getEyePosition();
        Vec3 aim = Vec3.atCenterOf(target);
        if (eye.distanceTo(aim) > REACH - 0.5) {
            lookToward(player, aim, 18f, null);
            mc.options.keyUp.setDown(true);
            mc.options.keyJump.setDown(player.horizontalCollision);
            return;
        }
        releaseKeys(mc);

        Plan plan = planPlacement(mc, target, wanted, have);
        if (plan == null) {
            // No usable support/face yet: leave it for a while so we don't get stuck on it.
            skipUntil.put(target, tickCount + 60);
            target = null;
            cooldown = 2;
            return;
        }

        // Stairs need the right yaw; the server must already have it, so we only click
        // once we were aligned at the start of this tick.
        boolean aligned = lookToward(player, plan.hit(), 14f + RNG.nextFloat() * 10f, plan.yaw());
        if (!aligned) return;

        player.getInventory().setSelectedSlot(slot);
        BlockHitResult hit = new BlockHitResult(plan.hit(), plan.side(), plan.clicked(), false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        recordPlacement(mc, target, wanted);

        // Wait for the server to confirm before touching this position again
        // (otherwise a second click could accidentally turn a slab into a double slab).
        skipUntil.put(target, tickCount + 8);
        target = null;
        cooldown = 3 + RNG.nextInt(5);
        if (RNG.nextInt(25) == 0) cooldown += 15 + RNG.nextInt(25);
    }

    /**
     * Works out which block face to click, and at what height / yaw, so that vanilla
     * placement logic gives the state the schematic wants.
     */
    private Plan planPlacement(Minecraft mc, BlockPos target, BlockState want, BlockState have) {
        Block block = want.getBlock();
        Float yaw = null;
        Boolean wantTop = null; // null = orientation doesn't depend on where we click

        if (block instanceof StairBlock) {
            // Vanilla: FACING = direction the player looks; HALF from click face / click height.
            wantTop = want.getValue(StairBlock.HALF) == Half.TOP;
            yaw = want.getValue(StairBlock.FACING).toYRot();
        } else if (block instanceof SlabBlock) {
            SlabType type = want.getValue(SlabBlock.TYPE);
            if (type == SlabType.DOUBLE) {
                if (have.is(block)) {
                    // One half is already placed: click it from its open side to complete it.
                    Direction side = have.getValue(SlabBlock.TYPE) == SlabType.TOP ? Direction.DOWN : Direction.UP;
                    return new Plan(target, side, faceHit(target, side), null);
                }
                wantTop = false; // start with the bottom half, finish it on a later pass
            } else {
                wantTop = type == SlabType.TOP;
            }
        }

        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || !ns.getFluidState().isEmpty()) continue;

            Direction side = d.getOpposite(); // face of the neighbour that points at the target

            if (wantTop != null) {
                // Clicking an UP face always gives bottom, a DOWN face always gives top.
                if (wantTop && side == Direction.UP) continue;
                if (!wantTop && side == Direction.DOWN) continue;
            }

            Vec3 hit = faceHit(n, side);
            if (wantTop != null && side.getAxis().isHorizontal()) {
                // On side faces the click height decides: above half = top, otherwise bottom.
                double frac = wantTop ? 0.55 + RNG.nextDouble() * 0.4 : 0.05 + RNG.nextDouble() * 0.4;
                hit = new Vec3(hit.x, n.getY() + frac, hit.z);
            }
            return new Plan(n, side, hit, yaw);
        }
        return null;
    }

    /** A point on the given face of a block, slightly randomised like a human aim. */
    private Vec3 faceHit(BlockPos block, Direction side) {
        Vec3 c = Vec3.atCenterOf(block);
        double rx = side.getAxis() == Direction.Axis.X ? 0 : (RNG.nextDouble() - 0.5) * 0.6;
        double ry = side.getAxis() == Direction.Axis.Y ? 0 : (RNG.nextDouble() - 0.5) * 0.6;
        double rz = side.getAxis() == Direction.Axis.Z ? 0 : (RNG.nextDouble() - 0.5) * 0.6;
        return new Vec3(
            c.x + side.getStepX() * 0.5 + rx,
            c.y + side.getStepY() * 0.5 + ry,
            c.z + side.getStepZ() * 0.5 + rz);
    }

    /** True if the schematic wants something here that the real world doesn't have yet. */
    private boolean needsBlock(Minecraft mc, WorldSchematic schem, BlockPos pos) {
        BlockState want = schem.getBlockState(pos);
        if (want.isAir()) return false;
        BlockState have = mc.level.getBlockState(pos);

        if (want.getBlock() instanceof SlabBlock && want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            if (have.is(want.getBlock())) return have.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
            return have.isAir() || have.canBeReplaced();
        }
        return have.getBlock() != want.getBlock() && (have.isAir() || have.canBeReplaced());
    }

    /** Lowest layer first, then closest to the player. */
    private BlockPos findNextTarget(Minecraft mc, WorldSchematic schem, LocalPlayer player) {
        if (skipUntil.size() > 256) skipUntil.values().removeIf(u -> u < tickCount);

        BlockPos origin = player.blockPosition();
        AABB playerBox = player.getBoundingBox();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++)
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++)
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    Integer until = skipUntil.get(p);
                    if (until != null && tickCount < until) continue;
                    if (!needsBlock(mc, schem, p)) continue;
                    if (playerBox.intersects(new AABB(p))) continue; // can't place inside ourselves
                    double score = p.getY() * 100.0 + p.distSqr(origin);
                    if (score < bestScore) { bestScore = score; best = p; }
                }
        return best;
    }

    /** Creates config/schematicbot.properties on first run; reads the optional Discord webhook. */
    private void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("schematicbot.properties");
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) { props.load(in); }
            } else {
                props.setProperty("discord_webhook", "");
                try (var out = Files.newOutputStream(file)) {
                    props.store(out, "SchematicBot - paste a Discord webhook URL to get the last block's coordinates when you log off");
                }
            }
        } catch (IOException e) {
            System.err.println("[SchematicBot] Could not read/write config: " + e);
        }
        webhookUrl = props.getProperty("discord_webhook", "").trim();
    }

    private void recordPlacement(Minecraft mc, BlockPos pos, BlockState state) {
        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        last = new LastPlacement(pos.immutable(), state.getBlock().getName().getString(), dimensionName(mc), server);
        reported = false;
    }

    /** "minecraft:overworld" etc., without depending on a specific identifier class name. */
    private static String dimensionName(Minecraft mc) {
        String s = String.valueOf(mc.level.dimension()); // ResourceKey[minecraft:dimension / minecraft:overworld]
        int slash = s.lastIndexOf("/ ");
        int end = s.lastIndexOf(']');
        return (slash >= 0 && end > slash) ? s.substring(slash + 2, end) : s;
    }

    /** Writes the last placement to a file and, if configured, posts it to the Discord webhook. */
    private void reportLastPlacement(boolean waitForSend) {
        if (last == null || reported) return;
        reported = true;
        LastPlacement lp = last;

        String text = "Last block placed at X " + lp.pos().getX() + ", Y " + lp.pos().getY()
            + ", Z " + lp.pos().getZ() + " (" + lp.dimension() + ") on " + lp.server()
            + " - " + lp.block();

        try {
            Path out = FabricLoader.getInstance().getGameDir().resolve("schematicbot_last_block.txt");
            Files.writeString(out, text + System.lineSeparator() + "Saved: " + Instant.now() + System.lineSeparator());
        } catch (IOException e) {
            System.err.println("[SchematicBot] Could not write last-block file: " + e);
        }

        if (!webhookUrl.isEmpty()) {
            try {
                String json = "{\"content\":\"" + jsonEscape("SchematicBot: " + text) + "\"}";
                HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                var future = HttpClient.newHttpClient().sendAsync(req, HttpResponse.BodyHandlers.discarding());
                if (waitForSend) future.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[SchematicBot] Webhook failed: " + e);
            }
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private int findHotbarSlot(LocalPlayer player, Item item) {
        for (int i = 0; i < 9; i++)
            if (player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    /**
     * Smoothly rotate toward a point (or a forced yaw). Returns true if we were already
     * aligned BEFORE this tick's turn, i.e. the server has had that rotation for a tick.
     */
    private boolean lookToward(LocalPlayer player, Vec3 point, float maxStep, Float forcedYaw) {
        Vec3 d = point.subtract(player.getEyePosition());
        float targetYaw = forcedYaw != null
            ? forcedYaw
            : (float) (Mth.atan2(d.z, d.x) * 180.0 / Math.PI) - 90f;
        float targetPitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180.0 / Math.PI);

        float dyaw = Mth.wrapDegrees(targetYaw - player.getYRot());
        float dpitch = targetPitch - player.getXRot();
        boolean aligned = Math.abs(dyaw) < 6f && Math.abs(dpitch) < 6f;
        float jitter = (RNG.nextFloat() - 0.5f) * 0.6f;

        player.setYRot(player.getYRot() + Mth.clamp(dyaw, -maxStep, maxStep) + jitter);
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(dpitch, -maxStep, maxStep), -90f, 90f));

        return aligned;
    }
}
