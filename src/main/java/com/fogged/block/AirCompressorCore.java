package com.fogged.block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fogged.AirFill;
import com.fogged.AirtightRooms;
import com.fogged.Config;
import com.fogged.registry.ModParticles;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Everything an air compressor does, whatever drives it: the unit it belongs to, the room it holds,
 * and the specks it breathes out. It lives in a {@link CompressorHost} block entity -- the FE
 * {@link AirCompressorBlockEntity}, or with Create the kinetic one -- and is paid for by that host's
 * {@link Drive}.
 *
 * <h2>Units</h2>
 * Compressors of the same block, facing the same way, side by side in the plane of their faces, join
 * into square units of 1x1, 2x2 or 3x3 ({@link #reform}). One block of each unit -- the corner lowest
 * along both plane axes -- is its controller: it alone ticks the room and draws the one big fan. The
 * room a unit holds and the rate it fills scale with its face area.
 *
 * <h2>Power</h2>
 * A room costs to hold, block by block, and costs again to fill: FE for the FE compressor
 * ({@link Config#COMPRESSOR_HOLD_FE}, {@link Config#COMPRESSOR_FILTER_FE}), stress for the kinetic one.
 * The {@link Drive} says what share of the holding it could pay this tick, and how many blocks of
 * filtering; a unit that cannot pay its holding in full holds that share of its room, so a starved one
 * finds its room too big and lets it go.
 *
 * <h2>Air in</h2>
 * A unit draws through {@link AirCompressorIntakeBlock}s: one set right behind a member, or any feeding
 * a duct network run from a member's back ({@link AirDuctBlock#intakes}). With none it does nothing
 * ({@link State#NO_INTAKE}); one drawing from the murk needs a filter in it ({@link State#UNFILTERED}).
 *
 * <h2>The room</h2>
 * The air goes out of the outlet -- or the open ends of an air duct set on it -- and floods the room
 * ({@link AirFill}). A sealed room then <em>pressurizes</em>: the air spreads outward in the order
 * the fill reached each cell, and each cell is breathable from the moment it is reached, white specks
 * drifting along the way the air flows. A leak floods the whole room at once, and the specks then
 * stream from the outlet to the hole and out of it -- Starship EVO's oxygen, which shows a leak by the
 * molecules escaping it. Any block change near the room re-checks it ({@link AirtightRooms}).
 */
public final class AirCompressorCore {

    /** What the unit's air is doing. */
    public enum State {
        OFF, NO_INTAKE, BLOCKED, OVERSIZE, LEAK, PRESSURIZING, SEALED,
        /** Every intake feeding it is in the murk without a filter. */
        UNFILTERED;

        static State of(int ordinal) {
            return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : OFF;
        }
    }

    /** What pays for the air. */
    public interface Drive {
        /**
         * Pay to hold {@code heldBlocks} blocks of room for one tick, for a unit of {@code members}
         * blocks. Returns the share of full power the unit runs at, 0..1.
         */
        float hold(List<BlockPos> members, int heldBlocks);

        /** Pay to filter up to {@code want} blocks of room this tick; returns how many it paid for. */
        int filter(int want);

        /** Called when the unit's controller or size changes: pool or split anything the drive keeps. */
        default void joined(AirCompressorCore controller) {
        }

        /** Whether this is a shaft: one in a unit drives the whole unit, FE or not. */
        default boolean shaft() {
            return false;
        }

        /**
         * The goggles' lines for what this drive is spending, client side: a grey label, then the amount
         * in aqua -- the way Create shows stress and Create Crafts & Additions shows energy.
         */
        default List<net.minecraft.network.chat.Component> readout() {
            return List.of();
        }
    }

    /** Largest unit edge. */
    public static final int MAX_SIZE = 3;
    /** Re-check the room this often even without a block change, for changes that notify nobody. */
    private static final int RECHECK_INTERVAL = 100;
    /** ...and this often while it is not holding, or rides a ship (see tickController). */
    private static final int FAST_RECHECK = 20;
    /** Specks a pressurizing unit releases per tick, at most. */
    private static final int FILL_SPECKS = 6;
    private static final float[] AIR_TINT = {0.92F, 0.96F, 1.0F};
    /** Power falls by this share of the gap per tick: rises at once, falls over a few seconds. */
    private static final float POWER_EASE = 0.05F;
    /** Below this the unit counts as off. */
    private static final float RUNNING = 0.02F;

    private final BlockEntity be;
    private final CompressorHost host;
    private final Drive drive;

    // --- Unit (saved, and synced so the client knows which block draws the fan, and how big) ---
    private BlockPos controllerPos;
    private int size = 1;

    // --- Power ---
    private float power;
    private boolean syncedRunning;

    // --- Room (controller, server only; rebuilt from the world after a load) ---
    private State state = State.OFF;
    private State savedState = State.OFF; // what the room was when last saved, to skip the refill on load
    private AirtightRooms.Space space;
    private AirFill fill;
    private LongArrayList pending = new LongArrayList();
    private int cursor;
    private int roomVolume;
    private int capacity;
    private int checkedCapacity; // the capacity the last fill ran with
    private int retryIn;         // ticks until a fill that met an unloaded chunk tries again
    private long[] leakPath = new long[0];
    private List<AirFill.Seed> lastSeeds = List.of(); // where the air last came in: outlets, duct ends
    // The intakes feeding the unit, and the ducts between them and its back; looked up again on a
    // re-check and every FAST_RECHECK ticks.
    private List<BlockPos> intakes;
    private Set<BlockPos> intakeDucts = Set.of();
    private final List<BlockPos> liveMouths = new ArrayList<>(); // mouths of the intakes giving clean air

    // --- Lifecycle ---
    private boolean fresh = true;  // placed, not loaded from disk: joins its neighbours once in the level
    private boolean unloading;     // going with its chunk, not removed: the room stays registered

    // --- Client ---
    private float fanAngle;
    private float lastFanAngle;
    // What the goggles read, mirrored from the server's controller (see writeClient).
    private int shownState;
    private int shownHeld;
    private int shownVolume;
    private int shownCapacity;
    /** Ticks between keeping the client's readout current. */
    private static final int READOUT_INTERVAL = 10;

    public AirCompressorCore(BlockEntity be, CompressorHost host, Drive drive) {
        this.be = be;
        this.host = host;
        this.drive = drive;
        this.controllerPos = be.getBlockPos();
    }

    /** The block entity this core lives in. */
    public BlockEntity blockEntity() {
        return be;
    }

    private Level level() {
        return be.getLevel();
    }

    private BlockPos pos() {
        return be.getBlockPos();
    }

    public Direction facing() {
        return be.getBlockState().getValue(BlockStateProperties.FACING);
    }

    // ================================================================================================
    // Units
    // ================================================================================================

    /** The two directions spanning the plane of a compressor's faces, positive along their axes. */
    public static Direction[] plane(Direction facing) {
        return switch (facing.getAxis()) {
            case X -> new Direction[] {Direction.SOUTH, Direction.UP};
            case Y -> new Direction[] {Direction.EAST, Direction.SOUTH};
            case Z -> new Direction[] {Direction.EAST, Direction.UP};
        };
    }

    private static AirCompressorCore coreAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CompressorHost h ? h.core() : null;
    }

    /**
     * Re-form every unit in the patch of compressors touching {@code pos}: gather the patch, then cut
     * it into squares, largest first, scanning from its lowest corner. The same patch always cuts the
     * same way, so units stay put as long as nothing in them changes. FE and kinetic compressors join
     * alike: a shaft set into one block of a unit drives the whole unit.
     */
    public static void reform(Level level, BlockPos pos, Direction facing) {
        Block block = level.getBlockState(pos).getBlock();
        Direction[] uv = plane(facing);
        Set<BlockPos> patch = patch(level, pos, block, facing, uv);
        if (patch.isEmpty()) {
            return;
        }
        List<BlockPos> sorted = new ArrayList<>(patch);
        Direction u = uv[0], v = uv[1];
        sorted.sort((a, b) -> {
            int dv = coord(a, v) - coord(b, v);
            return dv != 0 ? dv : coord(a, u) - coord(b, u);
        });
        Set<BlockPos> taken = new HashSet<>();
        for (BlockPos origin : sorted) {
            if (taken.contains(origin)) {
                continue;
            }
            int n = MAX_SIZE;
            while (n > 1 && !squareFree(origin, n, u, v, patch, taken)) {
                n--;
            }
            List<AirCompressorCore> members = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    BlockPos at = origin.relative(u, i).relative(v, j);
                    taken.add(at);
                    AirCompressorCore core = coreAt(level, at);
                    if (core != null) {
                        members.add(core);
                    }
                }
            }
            AirCompressorCore controller = coreAt(level, origin);
            for (AirCompressorCore core : members) {
                core.join(origin, n);
            }
            if (controller != null) {
                for (AirCompressorCore core : members) {
                    core.drive.joined(controller);
                }
            }
        }
    }

    /** Re-form the units next to {@code pos} after the compressor there is gone or turned. */
    public static void reformAround(Level level, BlockPos pos, Block block, Direction facing) {
        Set<BlockPos> done = new HashSet<>();
        Direction[] uv = plane(facing);
        for (Direction d : new Direction[] {uv[0], uv[0].getOpposite(), uv[1], uv[1].getOpposite()}) {
            BlockPos n = pos.relative(d);
            if (!done.contains(n) && same(level, n, block, facing)) {
                done.addAll(patch(level, n, block, facing, uv));
                reform(level, n, facing);
            }
        }
    }

    private static int coord(BlockPos pos, Direction d) {
        return pos.get(d.getAxis());
    }

    private static boolean squareFree(BlockPos origin, int n, Direction u, Direction v,
            Set<BlockPos> patch, Set<BlockPos> taken) {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                BlockPos at = origin.relative(u, i).relative(v, j);
                if (!patch.contains(at) || taken.contains(at)) {
                    return false;
                }
            }
        }
        return true;
    }

    // Same compressors connected to start within the plane, capped so a wall of them is cheap.
    private static Set<BlockPos> patch(Level level, BlockPos start, Block block, Direction facing, Direction[] uv) {
        Set<BlockPos> patch = new HashSet<>();
        if (!same(level, start, block, facing)) {
            return patch;
        }
        List<BlockPos> queue = new ArrayList<>();
        queue.add(start.immutable());
        patch.add(start.immutable());
        Direction[] steps = {uv[0], uv[0].getOpposite(), uv[1], uv[1].getOpposite()};
        for (int i = 0; i < queue.size() && patch.size() < 256; i++) {
            for (Direction d : steps) {
                BlockPos n = queue.get(i).relative(d);
                if (!patch.contains(n) && same(level, n, block, facing)) {
                    patch.add(n);
                    queue.add(n);
                }
            }
        }
        return patch;
    }

    private static boolean same(Level level, BlockPos pos, Block block, Direction facing) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof CompressorBlock && s.getValue(BlockStateProperties.FACING) == facing;
    }

    private void join(BlockPos controller, int n) {
        boolean changed = !controller.equals(controllerPos) || n != size;
        controllerPos = controller.immutable();
        size = n;
        if (changed) {
            if (!isController()) {
                dropRoom();
            }
            be.setChanged();
            Level level = level();
            if (level != null) {
                level.sendBlockUpdated(pos(), be.getBlockState(), be.getBlockState(), 3);
                level.invalidateCapabilities(pos());
            }
        }
    }

    public boolean isController() {
        return controllerPos.equals(pos());
    }

    public int size() {
        return size;
    }

    /** This unit's controller: the core that holds its room. Itself if that is gone. */
    public AirCompressorCore controller() {
        Level level = level();
        if (isController() || level == null) {
            return this;
        }
        AirCompressorCore c = coreAt(level, controllerPos);
        return c != null ? c : this;
    }

    /** The unit's member positions, controller first. */
    public List<BlockPos> members() {
        Direction[] uv = plane(facing());
        List<BlockPos> out = new ArrayList<>(size * size);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                out.add(pos().relative(uv[0], i).relative(uv[1], j));
            }
        }
        return out;
    }

    // ================================================================================================
    // Server tick
    // ================================================================================================

    /** Run from the host's server tick. */
    public void serverTick() {
        if (!isController()) {
            dropRoom();
            return;
        }
        tickController((ServerLevel) level());
    }

    private void tickController(ServerLevel level) {
        if (space == null) {
            space = AirtightRooms.open(level, pos());
        }
        Direction facing = facing();
        List<BlockPos> members = members();
        int area = size * size;

        updatePower(members);
        capacity = (int) (Config.COMPRESSOR_VOLUME.getAsInt() * area * power);
        // Capacity drifts with power; it only matters once it crosses the room: a held room that no
        // longer fits, or a room too big that might fit now.
        boolean capacityCrossed = ((state == State.SEALED || state == State.PRESSURIZING) && capacity < roomVolume)
                || (state == State.OVERSIZE && capacity > checkedCapacity);
        // A room that is not holding is looked at every second, so shutting the hole (a door swung to)
        // seals it quickly even where no block-change event says so -- as on a Sable ship, whose blocks
        // can change without one; a ship's room is checked that often throughout, for the same reason.
        int every = state == State.SEALED && !space.onShip() ? RECHECK_INTERVAL : FAST_RECHECK;
        boolean recheck = space.takeDirty() || capacityCrossed
                || level.getGameTime() % every == Math.floorMod(pos().asLong(), every);
        if (retryIn > 0) {
            recheck = --retryIn == 0;
        }

        if (intakes == null || recheck
                || level.getGameTime() % FAST_RECHECK == Math.floorMod(pos().asLong(), FAST_RECHECK)) {
            findIntakes(level, members, facing);
        }
        State supply = supply(level);
        if (power < RUNNING) {
            settle(State.OFF);
        } else if (supply == null) {
            // An intake's mouth is in a chunk not loaded yet: wait for it rather than call it shut.
        } else if (supply != State.PRESSURIZING) {
            settle(supply);
        } else if (recheck || state == State.OFF || state == State.NO_INTAKE || state == State.UNFILTERED) {
            check(level, members, facing);
        }

        if (state == State.PRESSURIZING) {
            pressurize(level, area);
        }
        emit(level);
        space.report(state.ordinal(), state == State.SEALED, roomVolume, capacity, leakPath);
        // Keep the goggles' readout current: the room and the drive change all the time.
        if (level.getGameTime() % READOUT_INTERVAL == Math.floorMod(pos().asLong(), READOUT_INTERVAL)) {
            host.syncToClient();
        }
    }

    // The drive that pays for the unit: a shaft set into any of its blocks, or else the controller's own.
    private Drive unitDrive(List<BlockPos> members) {
        Level level = level();
        for (BlockPos m : members) {
            AirCompressorCore core = level == null ? null : coreAt(level, m);
            if (core != null && core.drive.shaft()) {
                return core.drive;
            }
        }
        return drive;
    }

    /** The unit's member with a shaft in it, or null: there is at most one. */
    public AirCompressorCore shaftMember() {
        Level level = level();
        AirCompressorCore controller = controller();
        for (BlockPos m : controller.members()) {
            AirCompressorCore core = level == null ? null : coreAt(level, m);
            if (core != null && core.drive.shaft()) {
                return core;
            }
        }
        return null;
    }

    private void updatePower(List<BlockPos> members) {
        int held = space == null ? 0 : space.cells().size();
        // Up at once, down slowly: a unit coming on holds its room straight away, and one starved for a
        // moment (a supply a little short of the draw, a shaft network still loading) rides it out.
        float raw = Mth.clamp(unitDrive(members).hold(members, held), 0.0F, 1.0F);
        power = raw >= power ? raw : power + (raw - power) * POWER_EASE;
        boolean running = power >= RUNNING;
        if (running != syncedRunning) {
            syncedRunning = running;
            host.syncToClient(); // the client only needs on or off, for the fan
        }
    }

    // The intakes feeding the unit: one set right behind a member, feeding it, or one feeding a duct
    // network that runs from a member's back.
    private void findIntakes(Level level, List<BlockPos> members, Direction facing) {
        Set<BlockPos> found = new java.util.LinkedHashSet<>();
        Set<BlockPos> ducts = new HashSet<>();
        for (BlockPos m : members) {
            BlockPos back = m.relative(facing.getOpposite());
            BlockState there = level.getBlockState(back);
            if (there.getBlock() instanceof AirCompressorIntakeBlock
                    && there.getValue(AirCompressorIntakeBlock.FACING) == facing) {
                found.add(back.immutable());
            } else {
                found.addAll(AirDuctBlock.intakes(level, back, facing.getOpposite(), ducts));
            }
        }
        intakes = new ArrayList<>(found);
        intakeDucts = ducts;
    }

    // What the intakes give, as the state the unit settles in without it: PRESSURIZING when at least
    // one draws clean air (and liveMouths says where), UNFILTERED when the only open ones draw murk,
    // NO_INTAKE when there is none or every one is stopped up. Null when an intake's mouth is not
    // loaded yet and nothing else is drawing.
    private State supply(Level level) {
        liveMouths.clear();
        boolean unknown = false;
        boolean murk = false;
        boolean gone = false;
        for (BlockPos in : intakes) {
            BlockState s = level.getBlockState(in);
            if (!(s.getBlock() instanceof AirCompressorIntakeBlock)) {
                gone = true;
                continue;
            }
            switch (AirCompressorIntakeBlock.supply(level, in, s)) {
                case CLEAN, FILTERED -> liveMouths.add(AirCompressorIntakeBlock.mouth(in, s));
                case MURK -> murk = true;
                case UNKNOWN -> unknown = true;
                case BLOCKED -> {
                }
            }
        }
        if (gone) {
            // Broken or replaced: drop it now; the next look (a re-check, or soon) finds any new one.
            intakes = new ArrayList<>(intakes);
            intakes.removeIf(in -> !(level.getBlockState(in).getBlock() instanceof AirCompressorIntakeBlock));
        }
        if (!liveMouths.isEmpty()) {
            return State.PRESSURIZING;
        }
        if (unknown) {
            return null;
        }
        return murk ? State.UNFILTERED : State.NO_INTAKE;
    }

    // Whether every clean intake draws from inside the room the outlet fills: the unit would breathe
    // its own air back in. One intake drawing from outside is enough.
    private boolean intakesInside(LongArrayList room) {
        LongOpenHashSet cells = new LongOpenHashSet(room);
        for (BlockPos mouth : liveMouths) {
            if (!cells.contains(mouth.asLong())) {
                return false;
            }
        }
        return true;
    }

    private void check(ServerLevel level, List<BlockPos> members, Direction facing) {
        // Out of each outlet face -- or, where an air duct is set on it, out of the duct network's
        // open ends instead (AirDuctBlock.ends).
        List<AirFill.Seed> seeds = new ArrayList<>(members.size());
        Set<BlockPos> ducts = new HashSet<>();
        for (BlockPos m : members) {
            BlockPos out = m.relative(facing);
            List<AirFill.Seed> ends = AirDuctBlock.ends(level, out, facing, ducts);
            if (ends.isEmpty() && !(level.getBlockState(out).getBlock() instanceof AirDuctBlock)) {
                seeds.add(new AirFill.Seed(out, facing));
            } else {
                seeds.addAll(ends); // a duct with no open end (all capped) lets nothing out
            }
        }
        lastSeeds = seeds;
        // The ducts are full of air too, outlet side and intake side alike: they count as room, first
        // to fill, and leave the less for the room itself.
        Set<BlockPos> allDucts = new java.util.LinkedHashSet<>(ducts);
        allDucts.addAll(intakeDucts);
        AirFill result = AirFill.run(level, seeds, Math.max(1, capacity - allDucts.size()));
        if (result.result() == AirFill.Result.UNLOADED) {
            // The room runs into a chunk still loading -- usually just after a world loads. Leave the
            // air as it is and look again shortly, rather than flood a room that is likely fine.
            retryIn = 10;
            return;
        }
        fill = result;
        checkedCapacity = capacity;
        // Watch the intakes and what is in front of them as well, wherever the ducts run to.
        List<BlockPos> watched = new ArrayList<>(allDucts);
        watched.addAll(intakes);
        watched.addAll(liveMouths);
        space.watch(result, watched);
        switch (result.result()) {
            case SEALED -> {
                if (intakesInside(result.order())) {
                    flood(State.NO_INTAKE); // drawing the room's own air back in: nothing clean comes in
                    return;
                }
                LongArrayList order = new LongArrayList(allDucts.size() + result.order().size());
                for (BlockPos d : allDucts) {
                    order.add(d.asLong());
                }
                order.addAll(result.order());
                roomVolume = order.size();
                LongOpenHashSet cells = space.cells();
                if (savedState == State.SEALED) {
                    // Saved sealed and found sealed again on load: the air never left, so it is not
                    // pumped in again -- a base under the murk is breathable the moment it loads.
                    cells.addAll(order);
                } else if (state == State.SEALED || state == State.PRESSURIZING) {
                    // Still sealed: keep the air already in, drop what was walled off, carry on with the rest.
                    LongOpenHashSet room = new LongOpenHashSet(order);
                    cells.removeIf((java.util.function.LongPredicate) c -> !room.contains(c));
                } else {
                    cells.clear();
                }
                savedState = State.OFF;
                pending = new LongArrayList(order.size());
                for (int i = 0; i < order.size(); i++) {
                    long c = order.getLong(i);
                    if (!cells.contains(c)) {
                        pending.add(c);
                    }
                }
                cursor = 0;
                leakPath = new long[0];
                setState(pending.isEmpty() ? State.SEALED : State.PRESSURIZING);
            }
            case LEAK -> {
                leakPath = result.leakPath();
                flood(State.LEAK);
            }
            case OVERSIZE -> flood(State.OVERSIZE);
            case BLOCKED -> flood(State.BLOCKED);
            default -> {
            }
        }
        savedState = State.OFF; // a real answer: what was saved has served its purpose
    }

    // Spread the air on through the next stretch of the room, as far as the fill rate and the drive's
    // filtering allow, a speck here and there along the flow.
    private void pressurize(ServerLevel level, int area) {
        double perTick = Config.COMPRESSOR_FILL_RATE.getAsInt() * area * power / 20.0;
        int want = Math.max(1, (int) perTick + (level.random.nextDouble() < perTick - (int) perTick ? 1 : 0));
        int budget = unitDrive(members()).filter(Math.min(want, pending.size() - cursor));
        if (budget <= 0) {
            return;
        }
        LongOpenHashSet cells = space.cells();
        int end = Math.min(pending.size(), cursor + budget);
        int every = Math.max(1, (end - cursor) / FILL_SPECKS);
        for (int i = cursor; i < end; i++) {
            long cell = pending.getLong(i);
            cells.add(cell);
            if ((i - cursor) % every == 0) {
                flowSpeck(level, fill.parentOf(cell), cell, 0.06);
            }
        }
        cursor = end;
        if (cursor >= pending.size()) {
            pending = new LongArrayList();
            cursor = 0;
            setState(State.SEALED);
        }
    }

    private void flood(State why) {
        if (space != null) {
            space.cells().clear();
        }
        pending = new LongArrayList();
        cursor = 0;
        roomVolume = 0;
        setState(why);
    }

    // Lose the room without re-checking: power gone, intake shut. A no-op while it is already so and
    // holds no air -- but a room restored with the world (AirtightRooms.Saved) holds air before its
    // compressor has said anything, and an unpowered one must not keep it.
    private void settle(State why) {
        if (state != why || (space != null && !space.cells().isEmpty())) {
            flood(why);
            if (space != null) {
                space.markDirty(); // re-check at once when it comes back
            }
        }
    }

    private void setState(State next) {
        if (state != next) {
            state = next;
            be.setChanged();
        }
    }

    private void dropRoom() {
        if (space != null && level() != null) {
            AirtightRooms.close(level(), pos());
        }
        space = null;
        fill = null;
        state = State.OFF;
    }

    /** Blocks of room breathable now (0 for a member: the controller holds the room). */
    public int heldBlocks() {
        return space == null ? 0 : space.cells().size();
    }

    /** Whether the room is being filled right now. */
    public boolean pressurizing() {
        return state == State.PRESSURIZING;
    }

    /** How big the room being held or filled is, in blocks. */
    public int roomVolume() {
        return roomVolume;
    }

    // ================================================================================================
    // Specks
    // ================================================================================================

    private static ParticleOptions speck() {
        return ColorParticleOption.create(ModParticles.AIR.get(), AIR_TINT[0], AIR_TINT[1], AIR_TINT[2]);
    }

    private void emit(ServerLevel level) {
        RandomSource rand = level.random;
        switch (state) {
            case SEALED -> {
                // A breath out of an outlet or duct end now and then: it is running and holding.
                if (!lastSeeds.isEmpty() && rand.nextInt(10) == 0) {
                    seedSpeck(level, lastSeeds.get(rand.nextInt(lastSeeds.size())), 0.05, rand);
                }
            }
            case LEAK -> {
                if (leakPath.length < 2 || level.getGameTime() % 2 != 0) {
                    return;
                }
                // Specks streaming down the path the air takes to the hole...
                for (int k = 0; k < 3; k++) {
                    int i = rand.nextInt(leakPath.length - 1);
                    flowSpeck(level, leakPath[i], leakPath[i + 1], 0.12);
                }
                // ...and spraying out of it.
                int last = leakPath.length - 1;
                flowSpeck(level, leakPath[last - 1], leakPath[last], 0.25);
            }
            case OVERSIZE -> {
                // Sputtering: the air goes in and never comes back up to pressure.
                if (!lastSeeds.isEmpty() && rand.nextInt(6) == 0) {
                    seedSpeck(level, lastSeeds.get(rand.nextInt(lastSeeds.size())), 0.15, rand);
                }
            }
            default -> {
            }
        }
    }

    // A speck in from -> to, starting somewhere in the `from` cell and drifting toward `to`.
    private static void flowSpeck(ServerLevel level, long from, long to, double speed) {
        if (from == Long.MIN_VALUE) {
            return;
        }
        RandomSource rand = level.random;
        double fx = BlockPos.getX(from) + 0.2 + rand.nextDouble() * 0.6;
        double fy = BlockPos.getY(from) + 0.2 + rand.nextDouble() * 0.6;
        double fz = BlockPos.getZ(from) + 0.2 + rand.nextDouble() * 0.6;
        Vec3 dir = new Vec3(BlockPos.getX(to) - BlockPos.getX(from), BlockPos.getY(to) - BlockPos.getY(from),
                BlockPos.getZ(to) - BlockPos.getZ(from));
        if (dir.lengthSqr() < 1.0E-6) {
            dir = new Vec3(rand.nextGaussian(), rand.nextGaussian(), rand.nextGaussian());
        }
        dir = dir.normalize().scale(speed);
        // count 0: the offset is the velocity, so the speck moves exactly along the flow.
        level.sendParticles(speck(), fx, fy, fz, 0, dir.x, dir.y, dir.z, 1.0);
    }

    // A speck out of the face the air comes in through: the outlet, or a duct's open end.
    private static void seedSpeck(ServerLevel level, AirFill.Seed seed, double speed, RandomSource rand) {
        Direction facing = seed.into();
        BlockPos member = seed.pos().relative(facing.getOpposite());
        Vec3 face = Vec3.atCenterOf(member).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(0.55));
        double x = face.x + (rand.nextDouble() - 0.5) * 0.7 * (facing.getAxis() == Direction.Axis.X ? 0 : 1);
        double y = face.y + (rand.nextDouble() - 0.5) * 0.7 * (facing.getAxis() == Direction.Axis.Y ? 0 : 1);
        double z = face.z + (rand.nextDouble() - 0.5) * 0.7 * (facing.getAxis() == Direction.Axis.Z ? 0 : 1);
        level.sendParticles(speck(), x, y, z, 0,
                facing.getStepX() * speed, facing.getStepY() * speed, facing.getStepZ() * speed, 1.0);
    }

    // ================================================================================================
    // Client: the fan
    // ================================================================================================

    /** Run from the host's client tick. */
    public void clientTick() {
        lastFanAngle = fanAngle;
        fanAngle = (fanAngle + 24.0F * power) % 360.0F;
        if (fanAngle < lastFanAngle) {
            lastFanAngle -= 360.0F;
        }
    }

    /** Whether the unit has power: the renderer spins its fan faces while it does. */
    public boolean running() {
        return power >= RUNNING;
    }

    /** The fan's angle in degrees at this frame. */
    public float fanAngle(float partialTick) {
        return Mth.lerp(partialTick, lastFanAngle, fanAngle);
    }

    // ================================================================================================
    // Save / sync / lifecycle -- the host forwards these
    // ================================================================================================

    /** Write what the client needs: the unit, whether it runs, and what the goggles read. */
    public void writeClient(CompoundTag tag) {
        writeUnit(tag);
        tag.putFloat("Power", power);
        tag.putInt("State", state.ordinal());
        tag.putInt("Held", heldBlocks());
        tag.putInt("Volume", roomVolume);
        tag.putInt("Capacity", capacity);
    }

    /** Read what {@link #writeClient} wrote. */
    public void readClient(CompoundTag tag) {
        readUnit(tag);
        power = tag.getFloat("Power");
        shownState = tag.getInt("State");
        shownHeld = tag.getInt("Held");
        shownVolume = tag.getInt("Volume");
        shownCapacity = tag.getInt("Capacity");
    }

    /**
     * The goggles' lines for this unit, read from its controller's mirror: the unit, what its air is
     * doing, and the room against what it can hold. Client side.
     */
    public void goggleLines(List<net.minecraft.network.chat.Component> tooltip) {
        AirCompressorCore c = controller();
        // First in the tooltip, the header sits beside the goggles' icon: leave room for it.
        tooltip.add(net.minecraft.network.chat.Component.literal(tooltip.isEmpty() ? "    " : "")
                .append(net.minecraft.network.chat.Component.translatable("fogged.goggles.compressor", c.size, c.size))
                .withStyle(net.minecraft.ChatFormatting.WHITE));
        State shown = State.of(c.shownState);
        // Each a grey label over its value, as Create and Create Crafts & Additions lay theirs out.
        tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.status")
                .withStyle(net.minecraft.ChatFormatting.GRAY)));
        tooltip.add(line(net.minecraft.network.chat.Component.literal(" ").append(net.minecraft.network.chat.Component.translatable(
                "fogged.goggles.state." + shown.name().toLowerCase(java.util.Locale.ROOT)))
                .withStyle(shown == State.SEALED ? net.minecraft.ChatFormatting.GREEN
                        : shown == State.PRESSURIZING ? net.minecraft.ChatFormatting.YELLOW
                        : net.minecraft.ChatFormatting.RED)));
        if (c.shownVolume > 0) {
            tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.room")
                    .withStyle(net.minecraft.ChatFormatting.GRAY)));
            tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.blocks_of",
                    amount(c.shownHeld), amount(c.shownVolume)).withStyle(net.minecraft.ChatFormatting.AQUA)));
        }
        tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.capacity")
                .withStyle(net.minecraft.ChatFormatting.GRAY)));
        tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.blocks",
                amount(c.shownCapacity)).withStyle(net.minecraft.ChatFormatting.AQUA)));
        // What the unit is spending, from whatever drives it -- the same from every block of it.
        for (net.minecraft.network.chat.Component spend : c.unitDrive(c.members()).readout()) {
            tooltip.add(line(spend));
        }
    }

    /** Blocks of room held, client side: the mirror the goggles read. */
    public int shownHeld() {
        return shownHeld;
    }

    /** The room's size, client side. */
    public int shownVolume() {
        return shownVolume;
    }

    /** Whether the room is filling, client side. */
    public boolean shownPressurizing() {
        return State.of(shownState) == State.PRESSURIZING;
    }

    /** An amount the way Create Crafts & Additions writes energy: 950, 1.2k, 3.4M, 5.6G. */
    public static String amount(double value) {
        if (value >= 1.0E9) {
            return Math.round(value / 1.0E8) / 10.0 + "G";
        }
        if (value >= 1.0E6) {
            return Math.round(value / 1.0E5) / 10.0 + "M";
        }
        if (value >= 1000) {
            return Math.round(value / 100.0) / 10.0 + "k";
        }
        return Integer.toString((int) Math.round(value));
    }

    /** A goggle line indented under the header, as Create's own are. */
    public static net.minecraft.network.chat.Component line(net.minecraft.network.chat.Component text) {
        return net.minecraft.network.chat.Component.literal("    ").append(text);
    }

    /** Write everything that is saved with the world. */
    public void save(CompoundTag tag) {
        writeUnit(tag);
        tag.putInt("Air", state.ordinal());
        tag.putFloat("Power", power);
    }

    /** Read what {@link #save} wrote, as the block entity loads. */
    public void load(CompoundTag tag) {
        fresh = false;
        readUnit(tag);
        savedState = State.of(tag.getInt("Air"));
        // The smoothed power picks up where it left off, so a loaded unit does not start from nothing
        // and call its room too big while it spins up.
        power = Mth.clamp(tag.getFloat("Power"), 0.0F, 1.0F);
        syncedRunning = power >= RUNNING;
    }

    private void writeUnit(CompoundTag tag) {
        BlockPos off = controllerPos.subtract(pos());
        tag.putInt("CtrlX", off.getX());
        tag.putInt("CtrlY", off.getY());
        tag.putInt("CtrlZ", off.getZ());
        tag.putInt("Size", size);
    }

    private void readUnit(CompoundTag tag) {
        controllerPos = pos().offset(tag.getInt("CtrlX"), tag.getInt("CtrlY"), tag.getInt("CtrlZ"));
        size = Mth.clamp(tag.contains("Size") ? tag.getInt("Size") : 1, 1, MAX_SIZE);
    }

    /**
     * The block entity is in the level. A freshly placed compressor (not one loaded from disk) joins
     * its neighbours here: the block's onPlace runs before its block entity exists, so it cannot.
     */
    public void onLoad() {
        Level level = level();
        if (fresh && level != null && !level.isClientSide) {
            fresh = false;
            reform(level, pos(), facing());
        }
    }

    /**
     * The block entity is gone. Going with its chunk is not being removed: the room stays registered
     * (and saved) while the chunk is away, so the murk does not take what is in it before the
     * compressor is back to say.
     */
    public void onRemoved() {
        Level level = level();
        if (level != null && !level.isClientSide && !unloading) {
            dropRoom();
        }
    }

    /** The block entity's chunk is unloading. */
    public void onChunkUnloaded() {
        unloading = true;
        space = null; // let go of it without closing it
    }
}
