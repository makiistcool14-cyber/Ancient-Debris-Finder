package dev.debrisfinder;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IMineProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Seed-based Ancient Debris prediction (singleplayer / integrated server only),
 * confirmed-block scanning of loaded chunks, and a Baritone-driven miner with hazard stops.
 */
public final class DebrisFinder {
    public record Target(BlockPos pos, boolean confirmed, String note) {}

    private record Spec(PlacedFeature feature, int step, int index, int size) {}

    private static final int SCAN_RADIUS = 8;      // chunks, client side (confirmed blocks)
    private static final int PREDICT_RADIUS = 24;  // chunks, server side (seed prediction)
    private static final int REPREDICT_CHUNKS = 4; // recompute when the player moved this far

    public static volatile boolean openGuiRequested;

    private static volatile String status = "Idle";
    private static volatile boolean running;
    private static volatile Long seed;
    private static volatile List<Target> predictedRaw = List.of();
    private static volatile List<BlockPos> confirmedPositions = List.of();
    private static volatile List<Target> display = List.of();
    private static volatile boolean predictionBusy;

    private static int lastPredCx = Integer.MAX_VALUE;
    private static int lastPredCz = Integer.MAX_VALUE;
    private static int tickCounter;
    private static int noTargetTicks;
    private static float lastHealth;

    private DebrisFinder() {}

    // ---------- public accessors ----------

    public static String getStatus() { return status; }
    public static boolean isRunning() { return running; }
    public static List<Target> getDisplay() { return display; }

    public static String getSeedText() {
        Long s = seed;
        return s != null ? Long.toString(s) : "unavailable (only readable in singleplayer / LAN host)";
    }

    public static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    // ---------- main tick ----------

    public static void tick(Minecraft mc) {
        if (openGuiRequested) {
            openGuiRequested = false;
            mc.setScreen(new DebrisScreen());
        }

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            if (running) stop("Left the world");
            predictedRaw = List.of();
            confirmedPositions = List.of();
            display = List.of();
            lastPredCx = Integer.MAX_VALUE;
            return;
        }

        IntegratedServer server = mc.getSingleplayerServer();
        seed = server != null ? server.overworld().getSeed() : null;

        if (!level.dimension().equals(Level.NETHER)) {
            predictedRaw = List.of();
            confirmedPositions = List.of();
            display = List.of();
            lastPredCx = Integer.MAX_VALUE;
            if (running) stop("Not in the Nether");
            return;
        }

        tickCounter++;
        if (tickCounter % 20 == 1) {
            scanConfirmed(level, player);
            rebuildDisplay(level, player);
        }
        if (server != null) maybePredict(player, server);
        if (running) runMiner(mc);
    }

    // ---------- confirmed blocks (client-loaded chunks) ----------

    private static void scanConfirmed(ClientLevel level, LocalPlayer player) {
        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        int minSection = level.getMinY() >> 4;
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection sec = sections[i];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    if (!sec.maybeHas(s -> s.is(Blocks.ANCIENT_DEBRIS))) continue;
                    int baseY = (minSection + i) << 4;
                    for (int x = 0; x < 16; x++)
                        for (int y = 0; y < 16; y++)
                            for (int z = 0; z < 16; z++)
                                if (sec.getBlockState(x, y, z).is(Blocks.ANCIENT_DEBRIS))
                                    out.add(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z));
                }
            }
        }
        confirmedPositions = out;
    }

    /** Confirmed blocks first; predictions are only shown for chunks the client has not loaded yet. */
    private static void rebuildDisplay(ClientLevel level, LocalPlayer player) {
        BlockPos pp = player.blockPosition();
        List<Target> all = new ArrayList<>();
        for (BlockPos p : confirmedPositions) all.add(new Target(p, true, "(real block)"));
        for (Target t : predictedRaw) {
            if (level.getChunkSource().hasChunk(t.pos().getX() >> 4, t.pos().getZ() >> 4)) continue;
            all.add(t);
        }
        all.sort(Comparator.comparingDouble((Target t) -> t.pos().distSqr(pp)));
        display = all.size() > 40 ? List.copyOf(all.subList(0, 40)) : List.copyOf(all);
    }

    // ---------- seed-based prediction (runs on the integrated server thread) ----------

    private static void maybePredict(LocalPlayer player, IntegratedServer server) {
        Long s = seed;
        if (s == null || predictionBusy) return;
        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        if (lastPredCx != Integer.MAX_VALUE
            && Math.abs(cx - lastPredCx) < REPREDICT_CHUNKS && Math.abs(cz - lastPredCz) < REPREDICT_CHUNKS) return;
        lastPredCx = cx;
        lastPredCz = cz;
        predictionBusy = true;
        final long worldSeed = s;
        server.execute(() -> {
            try {
                ServerLevel nether = server.getLevel(Level.NETHER);
                predictedRaw = nether == null ? List.of() : computePredicted(nether, worldSeed, cx, cz);
            } catch (Throwable t) {
                predictedRaw = List.of();
                System.err.println("[DebrisFinder] prediction failed: " + t);
            } finally {
                predictionBusy = false;
            }
        });
    }

    private static List<Target> computePredicted(ServerLevel level, long worldSeed, int centerCx, int centerCz) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        BiomeSource biomeSource = gen.getBiomeSource();
        // Same feature ordering the game itself builds for this generator.
        List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(
            List.copyOf(biomeSource.possibleBiomes()),
            (Holder<net.minecraft.world.level.biome.Biome> b) -> gen.getBiomeGenerationSettings(b).features(),
            false);

        List<Spec> specs = new ArrayList<>();
        for (int step = 0; step < steps.size(); step++) {
            List<PlacedFeature> features = steps.get(step).features();
            for (int idx = 0; idx < features.size(); idx++) {
                int size = debrisSize(features.get(idx));
                if (size > 0) specs.add(new Spec(features.get(idx), step, idx, size));
            }
        }

        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        Set<BlockPos> seen = new HashSet<>();
        List<Target> out = new ArrayList<>();
        for (int dx = -PREDICT_RADIUS; dx <= PREDICT_RADIUS; dx++) {
            for (int dz = -PREDICT_RADIUS; dz <= PREDICT_RADIUS; dz++) {
                int cx = centerCx + dx, cz = centerCz + dz;
                BlockPos origin = new BlockPos(cx << 4, level.getMinY(), cz << 4);
                long decoSeed = random.setDecorationSeed(worldSeed, origin.getX(), origin.getZ());
                for (Spec spec : specs) {
                    random.setFeatureSeed(decoSeed, spec.index(), spec.step());
                    PlacementContext ctx = new PlacementContext(level, gen, Optional.of(spec.feature()));
                    final int size = spec.size();
                    spec.feature().getPositions(ctx, random, origin).forEach(p -> {
                        BlockPos ip = p.immutable();
                        if (seen.add(ip)) out.add(new Target(ip, false, "(vein center, blocks within ~" + size + ")"));
                    });
                }
            }
        }
        return out;
    }

    private static int debrisSize(PlacedFeature pf) {
        ConfiguredFeature<?, ?> cf = pf.feature().value();
        if (cf.config() instanceof OreConfiguration oc) {
            for (OreConfiguration.TargetBlockState t : oc.targetStates) {
                if (t.state.is(Blocks.ANCIENT_DEBRIS)) return oc.size;
            }
        }
        return 0;
    }

    // ---------- auto-miner ----------

    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) { status = "Cannot start: not in a world"; return; }
        if (!mc.level.dimension().equals(Level.NETHER)) { status = "Cannot start: not in the Nether"; return; }
        if (!hasPickaxe(player)) { status = "Cannot start: need a diamond or netherite pickaxe"; return; }
        running = true;
        noTargetTicks = 0;
        lastHealth = player.getHealth();
        status = "Starting...";
    }

    public static void stop(String reason) {
        boolean wasRunning = running;
        running = false;
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            b.getMineProcess().cancel();
            b.getPathingBehavior().cancelEverything();
        } catch (Throwable ignored) {
        }
        status = wasRunning || !reason.equals("Stopped by user") ? reason : "Idle";
    }

    private static void runMiner(Minecraft mc) {
        LocalPlayer player = mc.player;
        String hazard = hazard(mc, player);
        if (hazard != null) {
            stop("STOPPED - hazard: " + hazard);
            return;
        }

        BlockPos pp = player.blockPosition();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos p : confirmedPositions) {
            double d = p.distSqr(pp);
            if (d < best) { best = d; nearest = p; }
        }
        if (nearest == null) {
            if (++noTargetTicks > 100) stop("Finished: no confirmed debris in loaded chunks");
            else status = "Waiting for confirmed debris...";
            return;
        }
        noTargetTicks = 0;

        IMineProcess mine = BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess();
        if (!mine.isActive()) mine.mine(Blocks.ANCIENT_DEBRIS);
        status = "Mining -> " + fmt(nearest) + " (" + (int) Math.sqrt(best) + " blocks away)";
    }

    private static boolean hasPickaxe(LocalPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.DIAMOND_PICKAXE) || s.is(Items.NETHERITE_PICKAXE)) return true;
        }
        return false;
    }

    /** Returns a description of a danger, or null if everything looks fine. */
    private static String hazard(Minecraft mc, LocalPlayer player) {
        ClientLevel level = mc.level;
        float hp = player.getHealth();
        boolean damaged = hp < lastHealth - 0.01f;
        lastHealth = hp;
        if (damaged) return "took damage (" + String.format("%.1f", hp) + " HP left)";
        if (hp <= 8.0f) return "low health";
        if (player.isOnFire()) return "player is on fire";
        if (player.fallDistance > 5.0) return "falling";

        BlockPos b = player.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -2; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    m.set(b.getX() + dx, b.getY() + dy, b.getZ() + dz);
                    BlockState st = level.getBlockState(m);
                    if (st.getFluidState().is(FluidTags.LAVA)) return "lava at " + fmt(m);
                    if (st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE)) return "fire at " + fmt(m);
                }

        for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(6.0))) {
            if (mob instanceof Enemy) return "hostile mob nearby (" + mob.getName().getString() + ")";
        }

        // Lava right next to the block we are about to mine
        for (BlockPos t : confirmedPositions) {
            if (t.distSqr(b) > 100) continue;
            for (int dx = -2; dx <= 2; dx++)
                for (int dy = -2; dy <= 2; dy++)
                    for (int dz = -2; dz <= 2; dz++) {
                        m.set(t.getX() + dx, t.getY() + dy, t.getZ() + dz);
                        if (level.getBlockState(m).getFluidState().is(FluidTags.LAVA))
                            return "lava next to target " + fmt(t);
                    }
        }
        return null;
    }
}
