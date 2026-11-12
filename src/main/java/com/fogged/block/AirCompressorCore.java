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
 * Everything an air compressor does, whatever drives it: the room it holds -- alone, or with the other
 * compressors opening into it (see elect) -- and the specks it breathes out. It lives in a
 * {@link CompressorHost} block entity -- the FE {@link AirCompressorBlockEntity}, or with Create the
 * kinetic one -- and is paid for by that host's {@link Drive}. Each compressor is a block of its own:
 * more of them in a room add up.
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
        UNFILTERED,
        /** Every intake that could feed it has a clogged filter: change them. */
        CLOGGED,
        /** A redstone signal has it stopped. */
        STOPPED,
        /** Its outlets open into a room another unit holds: it works that room with it. */
        SHARED;

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

        /** Whether this is a shaft, turned by Create rather than paid in FE. */
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
    private java.util.Map<BlockPos, Integer> intakes; // each with the ducts between it and the unit
    private Set<BlockPos> intakeDucts = Set.of();
    private final List<BlockPos> liveMouths = new ArrayList<>(); // mouths of the intakes giving clean air
    // The intakes giving clean air, with what each supplies (blocks/s) and whether through a filter.
    private record Live(BlockPos pos, double flow, boolean filtered) {}
    private final List<Live> live = new ArrayList<>();
    private double supplyFlow; // blocks/s, all the live intakes together
    // No clean air coming in, but the room still sealed: it holds what it has and loses it slowly
    // (Config#COMPRESSOR_DRAIN_RATE), farthest from the outlet first.
    private boolean draining;
    private LongArrayList roomOrder = new LongArrayList(); // the room as last found sealed, ducts first
    private int drainCursor;     // roomOrder is drained from the end down to here
    private double drainCarry;   // the part of a block owed from earlier ticks
    private boolean stopped;     // a redstone signal into the unit
    // The room grew past what the group can hold (capacity fell -- a compressor failed, power dropped):
    // it keeps the air nearest the outlets, up to what it can hold, and loses the rest slowly.
    private boolean shrinking;
    private int filterSignal;    // the comparator reading, see updateSignal

    // --- Sharing a room: units whose outlets open into the same room work it as one (see elect). The
    // one lowest in the world leads: it holds the room, with the group's capacity together, fills it out
    // of every member's outlets as fast as they push and their intakes supply, and wears their filters.
    // The rest follow: each keeps its own power, intakes and outlets current for it, and pays its share.
    private BlockPos leaderPos;                       // the unit holding the room this one shares; null alone
    private List<BlockPos> group = List.of();         // the leader's: the other units sharing its room
    private double share = 1.0;                       // this unit's part of the room's holding cost
    private int roomCapacity;                         // the leader's: the group's capacity together
    private double groupPush;                         // the leader's: blocks/s the group pushes
    private double groupFlow;                         // the leader's: blocks/s the group's intakes supply
    private List<AirFill.Seed> ownSeeds = List.of();  // where this unit's own air comes out
    private Set<BlockPos> outletDucts = Set.of();     // the ducts it goes out through
    // Per level, each cell units' air comes out into -> those units: a fill reaching one has found them.
    // Several can own one cell -- units on the same duct network let their air out of the same ends.
    private static final java.util.Map<Level, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Set<BlockPos>>> SEED_OWNERS =
            new java.util.WeakHashMap<>();

    // --- Lifecycle ---
    private boolean unloading;     // going with its chunk, not removed: the room stays registered

    // --- Client ---
    private float fanAngle;
    private float lastFanAngle;
    // What the goggles read, mirrored from the server's controller (see writeClient).
    private int shownState;
    private int shownHeld;
    private int shownVolume;
    private int shownCapacity;
    private double shownFlow;
    private int shownNeed;
    private int shownCount = 1; // the compressors working the room together
    private double shownOwnFlow; // this compressor's own air in
    private int shownOwnSource;  // where it comes from: 1 clean, 2 filtered, 3 both, 0 none
    /** Ticks between keeping the client's readout current. */
    private static final int READOUT_INTERVAL = 10;

    public AirCompressorCore(BlockEntity be, CompressorHost host, Drive drive) {
        this.be = be;
        this.host = host;
        this.drive = drive;
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

    /** The two directions spanning the plane of a compressor's face, positive along their axes. */
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

    /** Every compressor holds its own room or shares one: each is its own controller. */
    public boolean isController() {
        return true;
    }

    /** A compressor is one block. */
    public int size() {
        return 1;
    }

    /** This compressor. */
    public AirCompressorCore controller() {
        return this;
    }

    /** This compressor's own position: the block that blows. */
    public List<BlockPos> members() {
        return List.of(pos());
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
        int area = 1;

        // A redstone signal into any block of the unit stops it: it spends nothing and draws no air.
        boolean wasStopped = stopped;
        stopped = false;
        for (BlockPos m : members) {
            if (level.hasNeighborSignal(m)) {
                stopped = true;
                break;
            }
        }
        if (stopped != wasStopped) {
            host.syncToClient(); // the fan, and a kinetic one's load
        }
        updatePower(members);
        capacity = (int) (Config.COMPRESSOR_VOLUME.getAsInt() * area * power);
        boolean dirty = space.takeDirty();
        if (intakes == null || dirty
                || level.getGameTime() % FAST_RECHECK == Math.floorMod(pos().asLong(), FAST_RECHECK)) {
            findIntakes(level, members, facing);
        }
        State supply = supply(level);
        updateSignal(level, members);

        // Sharing a room another unit holds: keep this unit's part current for it, and no more.
        if (leaderPos != null) {
            AirCompressorCore lead = coreAt(level, leaderPos);
            if (lead != null && lead != this && lead.isController() && lead.group.contains(pos())) {
                followerTick(level, members, facing);
                return;
            }
            leaderPos = null; // on its own again: look at the room straight away
            share = 1.0;
            setState(State.OFF);
        }
        List<AirCompressorCore> mates = mates(level);
        gather(mates, area);

        // Capacity drifts with power; it only matters once it crosses the room: a held room that no
        // longer fits, or a room too big that might fit now.
        boolean capacityCrossed = ((state == State.SEALED || state == State.PRESSURIZING) && roomCapacity < roomVolume)
                || (state == State.OVERSIZE && roomCapacity > checkedCapacity);
        // A room that is not holding is looked at every second, so shutting the hole (a door swung to)
        // seals it quickly even where no block-change event says so -- as on a Sable ship, whose blocks
        // can change without one; a ship's room is checked that often throughout, for the same reason.
        int every = state == State.SEALED && !space.onShip() ? RECHECK_INTERVAL : FAST_RECHECK;
        boolean recheck = dirty || capacityCrossed
                || level.getGameTime() % every == Math.floorMod(pos().asLong(), every);
        if (retryIn > 0) {
            recheck = --retryIn == 0;
        }

        // The group runs while any of it does, and has air while any of its intakes give clean air.
        boolean runs = contributes();
        for (AirCompressorCore m : mates) {
            runs |= m.contributes();
            if (m.contributes() && m.supplyFlow > 0.0) {
                supply = State.PRESSURIZING;
            }
        }
        if (!runs) {
            if (stopped) {
                starve(level, members, facing, area, State.STOPPED, recheck);
            } else {
                settle(State.OFF);
            }
        } else if (supply == null) {
            // An intake's mouth is in a chunk not loaded yet: wait for it rather than call it shut.
        } else if (supply != State.PRESSURIZING) {
            starve(level, members, facing, area, supply, recheck);
        } else if (recheck || draining || state == State.OFF || state == State.NO_INTAKE
                || state == State.UNFILTERED || state == State.CLOGGED || state == State.STOPPED) {
            check(level, members, facing); // keeps what a draining room still holds, fills the rest
            draining = false;
        }

        if (state == State.PRESSURIZING) {
            pressurize(level, mates);
        }
        if (shrinking && state == State.OVERSIZE) {
            drainTo(Config.COMPRESSOR_DRAIN_RATE.get(), Math.max(0, roomCapacity));
        }
        emit(level);
        // A draining or shrinking room is still a sealed room: saved with the world, to go on after a load.
        space.report(state.ordinal(), state == State.SEALED || draining || shrinking, roomVolume, roomCapacity,
                leakPath);
        // Keep the goggles' readout current: the room and the drive change all the time.
        if (level.getGameTime() % READOUT_INTERVAL == Math.floorMod(pos().asLong(), READOUT_INTERVAL)) {
            host.syncToClient();
        }
    }

    // Whether this unit adds to its room: running, and not stopped by redstone.
    private boolean contributes() {
        return !stopped && power >= RUNNING;
    }

    // A unit sharing a room another holds: its outlets current for the leader's fill, its own air given
    // over, and nothing else -- the leader reads its power, capacity and intakes.
    private void followerTick(ServerLevel level, List<BlockPos> members, Direction facing) {
        if (ownSeeds.isEmpty() || level.getGameTime() % FAST_RECHECK == Math.floorMod(pos().asLong(), FAST_RECHECK)) {
            Set<BlockPos> ducts = new HashSet<>();
            registerSeeds(level, computeSeeds(level, members, facing, ducts));
            outletDucts = ducts;
        }
        space.cells().clear();
        draining = false;
        pending = new LongArrayList();
        cursor = 0;
        roomVolume = 0;
        roomCapacity = capacity;
        leakPath = new long[0];
        setState(State.SHARED);
        space.report(state.ordinal(), false, 0, capacity, leakPath);
        if (level.getGameTime() % READOUT_INTERVAL == Math.floorMod(pos().asLong(), READOUT_INTERVAL)) {
            host.syncToClient();
        }
    }

    // The leader's live group: the units still following it. Any gone or gone their own way drop out.
    private List<AirCompressorCore> mates(Level level) {
        if (group.isEmpty()) {
            return List.of();
        }
        List<AirCompressorCore> out = new ArrayList<>(group.size());
        List<BlockPos> still = new ArrayList<>(group.size());
        for (BlockPos p : group) {
            AirCompressorCore m = coreAt(level, p);
            if (m != null && m != this && m.isController() && pos().equals(m.leaderPos)) {
                out.add(m);
                still.add(p);
            }
        }
        if (still.size() != group.size()) {
            group = still;
            if (space != null) {
                space.markDirty(); // smaller now: look again
            }
        }
        return out;
    }

    // The group's capacity, push and supply together, and each member's share of the holding.
    private void gather(List<AirCompressorCore> mates, int area) {
        roomCapacity = capacity;
        groupPush = contributes() ? Config.COMPRESSOR_FILL_RATE.getAsInt() * area * power : 0.0;
        groupFlow = contributes() ? supplyFlow : 0.0;
        for (AirCompressorCore m : mates) {
            roomCapacity += m.capacity;
            if (m.contributes()) {
                groupPush += Config.COMPRESSOR_FILL_RATE.getAsInt() * m.power;
                groupFlow += m.supplyFlow;
            }
        }
        share = roomCapacity > 0 ? capacity / (double) roomCapacity : 1.0;
        for (AirCompressorCore m : mates) {
            m.share = roomCapacity > 0 ? m.capacity / (double) roomCapacity : 0.0;
        }
    }

    // Where this unit's air comes out: out of each outlet face -- or, where an air duct is set on it, out
    // of the duct network's open ends instead (AirDuctBlock.ends), the ducts walked going to `ducts`.
    private List<AirFill.Seed> computeSeeds(Level level, List<BlockPos> members, Direction facing, Set<BlockPos> ducts) {
        List<AirFill.Seed> seeds = new ArrayList<>(members.size());
        for (BlockPos m : members) {
            BlockPos out = m.relative(facing);
            List<AirFill.Seed> ends = AirDuctBlock.ends(level, out, facing, ducts);
            if (ends.isEmpty() && !(level.getBlockState(out).getBlock() instanceof AirDuctBlock)) {
                seeds.add(new AirFill.Seed(out, facing));
            } else {
                seeds.addAll(ends); // a duct with no open end (all capped) lets nothing out
            }
        }
        return seeds;
    }

    // Record where this unit's air comes out, so a fill reaching there knows it is in the same room.
    private void registerSeeds(Level level, List<AirFill.Seed> seeds) {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Set<BlockPos>> owners =
                SEED_OWNERS.computeIfAbsent(level, l -> new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
        forget(owners);
        ownSeeds = seeds;
        for (AirFill.Seed seed : seeds) {
            owners.computeIfAbsent(seed.pos().asLong(), k -> new HashSet<>()).add(pos().immutable());
        }
    }

    // Take this unit off the cells its air last came out into.
    private void forget(it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Set<BlockPos>> owners) {
        for (AirFill.Seed seed : ownSeeds) {
            long key = seed.pos().asLong();
            Set<BlockPos> set = owners.get(key);
            if (set != null) {
                set.remove(pos());
                if (set.isEmpty()) {
                    owners.remove(key);
                }
            }
        }
    }

    private void unregisterSeeds(Level level) {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Set<BlockPos>> owners = SEED_OWNERS.get(level);
        if (owners != null) {
            forget(owners);
        }
        ownSeeds = List.of();
    }

    /**
     * After a fill: the other units whose air comes out into what it reached share this room. The one
     * lowest in the world leads it. True when that is another, which this unit now follows -- its air
     * handed to the leader, so the room does not lose a breath on the way.
     */
    private boolean elect(ServerLevel level, AirFill result) {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Set<BlockPos>> owners = SEED_OWNERS.get(level);
        Set<BlockPos> found = new java.util.LinkedHashSet<>();
        if (owners != null) {
            LongArrayList order = result.order();
            for (int i = 0; i < order.size(); i++) {
                Set<BlockPos> here = owners.get(order.getLong(i));
                if (here == null) {
                    continue;
                }
                for (BlockPos owner : here) {
                    if (!owner.equals(pos())) {
                        AirCompressorCore other = coreAt(level, owner);
                        if (other != null && other.isController()) {
                            found.add(owner);
                        }
                    }
                }
            }
        }
        BlockPos lead = pos();
        for (BlockPos o : found) {
            if (o.compareTo(lead) < 0) {
                lead = o;
            }
        }
        if (!lead.equals(pos())) {
            AirCompressorCore leader = coreAt(level, lead);
            if (leader.space != null && space != null) {
                leader.space.cells().addAll(space.cells());
            }
            if (!leader.group.contains(pos())) {
                List<BlockPos> grown = new ArrayList<>(leader.group);
                grown.add(pos().immutable());
                leader.group = grown;
            }
            if (leader.space != null) {
                leader.space.markDirty();
            }
            follow(lead);
            return true;
        }
        // Members no longer reached -- a door shut between them -- take the air on their side with them,
        // and hold it themselves from here.
        LongOpenHashSet reached = new LongOpenHashSet(result.order());
        for (BlockPos p : group) {
            if (found.contains(p)) {
                continue;
            }
            AirCompressorCore gone = coreAt(level, p);
            if (gone == null || gone.space == null || !pos().equals(gone.leaderPos) || space == null) {
                continue;
            }
            LongOpenHashSet cut = new LongOpenHashSet();
            for (long c : space.cells()) {
                if (!reached.contains(c)) {
                    cut.add(c);
                }
            }
            gone.leaderPos = null;
            gone.share = 1.0;
            gone.space.cells().addAll(cut);
            gone.setState(cut.isEmpty() ? State.OFF : State.SEALED);
            gone.space.markDirty(); // look at its own room straight away, keeping the air it took
            space.markDirty();      // and this one at what is left of its own
        }
        boolean grew = false;
        for (BlockPos o : found) {
            AirCompressorCore other = coreAt(level, o);
            if (!pos().equals(other.leaderPos)) {
                if (other.space != null && space != null) {
                    space.cells().addAll(other.space.cells());
                }
                other.follow(pos());
                grew = true;
            }
        }
        group = new ArrayList<>(found);
        if (grew) {
            space.markDirty(); // with their capacity and outlets, look again
        }
        return false;
    }

    // The capacity of every other compressor in the level whose air comes out somewhere, outside this
    // group: the most a room this one cannot hold alone could gain by finding them.
    private int othersCapacity(Level level, List<AirCompressorCore> mates) {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Set<BlockPos>> owners = SEED_OWNERS.get(level);
        if (owners == null) {
            return 0;
        }
        Set<BlockPos> counted = new HashSet<>();
        counted.add(pos());
        for (AirCompressorCore m : mates) {
            counted.add(m.pos());
        }
        int sum = 0;
        for (Set<BlockPos> set : owners.values()) {
            for (BlockPos o : set) {
                if (counted.add(o)) {
                    AirCompressorCore other = coreAt(level, o);
                    if (other != null) {
                        sum += Math.max(other.capacity, Config.COMPRESSOR_VOLUME.getAsInt());
                    }
                }
            }
        }
        return sum;
    }

    // Follow the unit at `lead`: its room is held there now.
    private void follow(BlockPos lead) {
        leaderPos = lead.immutable();
        group = List.of();
        if (space != null) {
            space.cells().clear();
        }
        draining = false;
        roomOrder = new LongArrayList();
        pending = new LongArrayList();
        cursor = 0;
        roomVolume = 0;
        setState(State.SHARED);
    }

    /** The unit holding this unit's room: its leader when it shares one, itself otherwise. */
    public AirCompressorCore holder() {
        AirCompressorCore c = controller();
        Level level = level();
        if (c.leaderPos != null && level != null) {
            AirCompressorCore lead = coreAt(level, c.leaderPos);
            if (lead != null) {
                return lead;
            }
        }
        return c;
    }

    /** Blocks of room this unit pays to hold: its share of the room it holds or shares. */
    public int loadHeld() {
        return (int) Math.round(holder().heldBlocks() * controller().share);
    }

    /** Blocks of room this unit pays to fill: its share of the room's size. */
    public int loadVolume() {
        return (int) Math.round(holder().roomVolume() * controller().share);
    }

    // No clean air coming in (`why`: no intake, murk unfiltered, clogged filters, stopped by redstone):
    // a sealed room keeps what it has and loses it slowly. Still looked at, so opening it floods it at
    // once as ever; with no room held, the unit just says why.
    private void starve(ServerLevel level, List<BlockPos> members, Direction facing, int area, State why,
            boolean recheck) {
        if (draining || shrinking || state == State.SEALED || state == State.PRESSURIZING) {
            draining = true;
            shrinking = false;
            if (recheck) {
                check(level, members, facing);
            }
            if (draining) {
                setState(why);
                // Stopped by redstone it vents, fast enough to cycle an airlock; starved of air, it
                // only loses it slowly.
                drainTo(why == State.STOPPED ? Config.COMPRESSOR_VENT_RATE.get() : Config.COMPRESSOR_DRAIN_RATE.get(), 0);
            }
        } else {
            settle(why);
        }
    }

    // The comparator reading: the average pollution of the unit's intake filters, 0 (clean or none) to 15
    // (every one clogged). Every block of the unit gives it; comparators are told when it changes.
    private void updateSignal(Level level, List<BlockPos> members) {
        int signal = (int) Math.ceil(averagePollution(level) * 15);
        if (signal == 15 && !allClogged(level)) {
            signal = 14; // 15 means every filter is clogged
        }
        if (signal != filterSignal) {
            filterSignal = signal;
            for (BlockPos m : members) {
                level.updateNeighbourForOutputSignal(m, level.getBlockState(m).getBlock());
            }
        }
    }

    // The intakes of every compressor working this compressor's room, its own among them.
    private List<BlockPos> roomIntakes() {
        AirCompressorCore h = holder();
        Level level = level();
        List<BlockPos> out = new ArrayList<>();
        if (h.intakes != null) {
            out.addAll(h.intakes.keySet());
        }
        if (level != null) {
            for (BlockPos p : h.group) {
                AirCompressorCore m = coreAt(level, p);
                if (m != null && m.intakes != null) {
                    out.addAll(m.intakes.keySet());
                }
            }
        }
        return out;
    }

    // The average pollution, 0..1, of the filters fitted to the room's intakes; 0 with none fitted.
    private double averagePollution(Level level) {
        double sum = 0.0;
        int n = 0;
        for (BlockPos in : roomIntakes()) {
            BlockState s = level.getBlockState(in);
            if (s.getBlock() instanceof AirCompressorIntakeBlock && s.getValue(AirCompressorIntakeBlock.FILTERED)
                    && level.getBlockEntity(in) instanceof AirCompressorIntakeBlockEntity be) {
                sum += s.getValue(AirCompressorIntakeBlock.POLLUTION) >= AirCompressorIntakeBlock.CLOGGED ? 1.0 : be.worn();
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    // Whether the room's intakes have filters fitted and every one is clogged.
    private boolean allClogged(Level level) {
        int n = 0;
        for (BlockPos in : roomIntakes()) {
            BlockState s = level.getBlockState(in);
            if (s.getBlock() instanceof AirCompressorIntakeBlock && s.getValue(AirCompressorIntakeBlock.FILTERED)) {
                if (s.getValue(AirCompressorIntakeBlock.POLLUTION) < AirCompressorIntakeBlock.CLOGGED) {
                    return false;
                }
                n++;
            }
        }
        return n > 0;
    }

    // --- Readings, server side, for Create's Display Link (com.fogged.create.AirCompressorDisplaySources) ---

    // Every compressor working a room reads the room's: its state, and all their intakes together.

    /** The room's state. */
    public State state() {
        return holder().state;
    }

    /** Blocks of air per second the room's intakes supply now, every compressor's together. */
    public double supplyFlow() {
        return holder().groupFlow;
    }

    /** The average pollution of the filters fitted to the room's intakes, 0..1; 0 with none fitted. */
    public double filterPollution() {
        return level() == null ? 0.0 : averagePollution(level());
    }

    /** How many of the room's intake filters are clogged. */
    public int cloggedFilters() {
        Level level = level();
        int n = 0;
        if (level != null) {
            for (BlockPos in : roomIntakes()) {
                BlockState s = level.getBlockState(in);
                if (s.getBlock() instanceof AirCompressorIntakeBlock && s.getValue(AirCompressorIntakeBlock.FILTERED)
                        && s.getValue(AirCompressorIntakeBlock.POLLUTION) >= AirCompressorIntakeBlock.CLOGGED) {
                    n++;
                }
            }
        }
        return n;
    }

    /** What a comparator reads from this block: its unit's intake filters' average pollution, 0..15. */
    public int comparatorSignal() {
        return controller().filterSignal;
    }

    /** Whether a redstone signal has the unit stopped. */
    public boolean stopped() {
        Level level = level();
        return level != null && level.isClientSide ? State.of(controller().shownState) == State.STOPPED
                : controller().stopped;
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

    private void updatePower(List<BlockPos> members) {
        int held = loadHeld();
        // Up at once, down slowly: a unit coming on holds its room straight away, and one starved for a
        // moment (a supply a little short of the draw, a shaft network still loading) rides it out.
        // Stopped by redstone, it asks nothing of its drive: no FE spent, no stress.
        float raw = stopped ? 0.0F : Mth.clamp(unitDrive(members).hold(members, held), 0.0F, 1.0F);
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
        java.util.Map<BlockPos, Integer> found = new java.util.LinkedHashMap<>();
        Set<BlockPos> ducts = new HashSet<>();
        for (BlockPos m : members) {
            BlockPos back = m.relative(facing.getOpposite());
            BlockState there = level.getBlockState(back);
            if (there.getBlock() instanceof AirCompressorIntakeBlock
                    && there.getValue(AirCompressorIntakeBlock.FACING) == facing) {
                found.merge(back.immutable(), 0, Math::min);
            } else {
                AirDuctBlock.intakes(level, back, facing.getOpposite(), ducts)
                        .forEach((in, n) -> found.merge(in, n, Math::min));
            }
        }
        intakes = found;
        intakeDucts = ducts;
    }

    // What the intakes give, as the state the unit settles in without it: PRESSURIZING when at least
    // one draws clean air (and liveMouths says where), UNFILTERED when the only open ones draw murk,
    // NO_INTAKE when there is none or every one is stopped up. Null when an intake's mouth is not
    // loaded yet and nothing else is drawing.
    private State supply(Level level) {
        liveMouths.clear();
        live.clear();
        supplyFlow = 0.0;
        boolean unknown = false;
        boolean murk = false;
        boolean clogged = false;
        boolean gone = false;
        for (java.util.Map.Entry<BlockPos, Integer> entry : intakes.entrySet()) {
            BlockPos in = entry.getKey();
            BlockState s = level.getBlockState(in);
            if (!(s.getBlock() instanceof AirCompressorIntakeBlock)) {
                gone = true;
                continue;
            }
            AirCompressorIntakeBlock.Supply supply = AirCompressorIntakeBlock.supply(level, in, s);
            switch (supply) {
                case CLEAN, FILTERED -> {
                    liveMouths.add(AirCompressorIntakeBlock.mouth(in, s));
                    double flow = AirCompressorIntakeBlock.flow(level, in, s, supply, entry.getValue());
                    live.add(new Live(in, flow, supply == AirCompressorIntakeBlock.Supply.FILTERED));
                    supplyFlow += flow;
                }
                case MURK -> murk = true;
                case CLOGGED -> clogged = true;
                case UNKNOWN -> unknown = true;
                case BLOCKED -> {
                }
            }
        }
        if (gone) {
            // Broken or replaced: drop it now; the next look (a re-check, or soon) finds any new one.
            intakes = new java.util.LinkedHashMap<>(intakes);
            intakes.keySet().removeIf(in -> !(level.getBlockState(in).getBlock() instanceof AirCompressorIntakeBlock));
        }
        if (!liveMouths.isEmpty()) {
            return State.PRESSURIZING;
        }
        if (unknown) {
            return null;
        }
        return clogged ? State.CLOGGED : murk ? State.UNFILTERED : State.NO_INTAKE;
    }

    // Whether every clean intake draws from inside the room the outlet fills: the unit would breathe
    // its own air back in. One intake drawing from outside is enough.
    private boolean intakesInside(LongArrayList room, List<AirCompressorCore> mates) {
        List<BlockPos> mouths = new ArrayList<>(liveMouths);
        for (AirCompressorCore m : mates) {
            mouths.addAll(m.liveMouths);
        }
        if (mouths.isEmpty()) {
            return false; // nothing drawing at all: that is the supply's to say, not this
        }
        LongOpenHashSet cells = new LongOpenHashSet(room);
        for (BlockPos mouth : mouths) {
            if (!cells.contains(mouth.asLong())) {
                return false;
            }
        }
        return true;
    }

    private void check(ServerLevel level, List<BlockPos> members, Direction facing) {
        // Out of this unit's outlets and every member's of its group.
        Set<BlockPos> ducts = new HashSet<>();
        registerSeeds(level, computeSeeds(level, members, facing, ducts));
        outletDucts = ducts;
        List<AirCompressorCore> mates = mates(level);
        List<AirFill.Seed> seeds = new ArrayList<>(ownSeeds);
        // The ducts are full of air too, outlet side and intake side alike, the group's as well: they
        // count as room, first to fill, and leave the less for the room itself.
        Set<BlockPos> allDucts = new java.util.LinkedHashSet<>(ducts);
        allDucts.addAll(intakeDucts);
        for (AirCompressorCore m : mates) {
            seeds.addAll(m.ownSeeds);
            allDucts.addAll(m.outletDucts);
            allDucts.addAll(m.intakeDucts);
        }
        lastSeeds = seeds;
        // A draining room is only looked at for a leak: whatever the power has wound down to, it is not
        // too big for what it already holds.
        int limit = draining ? Math.max(roomCapacity, roomVolume) : roomCapacity;
        AirFill result = AirFill.run(level, seeds, Math.max(1, limit - allDucts.size()));
        if (result.result() == AirFill.Result.UNLOADED) {
            // The room runs into a chunk still loading -- usually just after a world loads. Leave the
            // air as it is and look again shortly, rather than flood a room that is likely fine.
            retryIn = 10;
            return;
        }
        // Who shares this room is told by a fill from this compressor's own outlets alone: the group's
        // fill starts at every member's, so it reaches each of them even after a door shut between them.
        // It looks on as far as every other compressor about could add -- a room too big for this group
        // alone may hold another compressor past where its capacity stopped the fill.
        AirFill reach = result;
        if (!mates.isEmpty() || result.result() == AirFill.Result.OVERSIZE) {
            AirFill own = AirFill.run(level, ownSeeds,
                    Math.max(1, limit + othersCapacity(level, mates) - allDucts.size()));
            if (own.result() != AirFill.Result.UNLOADED) {
                reach = own;
            }
        }
        if (elect(level, reach)) {
            return; // another unit holds this room: this one follows it now
        }
        mates = mates(level);
        fill = result;
        checkedCapacity = roomCapacity;
        // Watch the intakes and what is in front of them as well, wherever the ducts run to.
        List<BlockPos> watched = new ArrayList<>(allDucts);
        watched.addAll(intakes.keySet());
        watched.addAll(liveMouths);
        space.watch(result, watched);
        switch (result.result()) {
            case SEALED -> {
                if (intakesInside(result.order(), mates)) {
                    flood(State.NO_INTAKE); // drawing the room's own air back in: nothing clean comes in
                    return;
                }
                LongArrayList order = new LongArrayList(allDucts.size() + result.order().size());
                for (BlockPos d : allDucts) {
                    order.add(d.asLong());
                }
                order.addAll(result.order());
                roomVolume = order.size();
                roomOrder = order;
                drainCursor = 0;
                LongOpenHashSet cells = space.cells();
                if (savedState == State.SEALED) {
                    // Saved sealed and found sealed again on load: the air never left, so it is not
                    // pumped in again -- a base under the murk is breathable the moment it loads.
                    cells.addAll(order);
                } else if (state == State.SEALED || state == State.PRESSURIZING || draining || shrinking) {
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
                shrinking = false;
                setState(pending.isEmpty() ? State.SEALED : State.PRESSURIZING);
            }
            case LEAK -> {
                leakPath = result.leakPath();
                flood(State.LEAK);
            }
            case OVERSIZE -> {
                // Too big for what the group holds now. A room that was held keeps the air nearest the
                // outlets and loses the rest slowly, to what fits (see tickController); one never held
                // has nothing to keep.
                boolean held = space.cells().size() > 0 && (state == State.SEALED || state == State.PRESSURIZING
                        || state == State.OVERSIZE || draining || shrinking);
                if (held) {
                    shrinking = true;
                    draining = false;
                    pending = new LongArrayList();
                    cursor = 0;
                    leakPath = new long[0];
                    setState(State.OVERSIZE);
                } else {
                    flood(State.OVERSIZE);
                }
            }
            case BLOCKED -> flood(State.BLOCKED);
            default -> {
            }
        }
        savedState = State.OFF; // a real answer: what was saved has served its purpose
    }

    // Spread the air on through the next stretch of the room, as far as the fill rate and the drive's
    // filtering allow, a speck here and there along the flow.
    private void pressurize(ServerLevel level, List<AirCompressorCore> mates) {
        // As fast as the group pushes, or its intakes supply, whichever is less.
        double perTick = Math.min(groupPush, groupFlow) / 20.0;
        int want = (int) perTick + (level.random.nextDouble() < perTick - (int) perTick ? 1 : 0);
        if (want <= 0) {
            return;
        }
        int budget = unitDrive(members()).filter(Math.min(want, pending.size() - cursor));
        if (budget <= 0) {
            return;
        }
        // The air drawn comes in through the intakes by their share of the flow; through a filter, it
        // wears it.
        List<AirCompressorCore> drawing = new ArrayList<>(mates.size() + 1);
        if (contributes()) {
            drawing.add(this);
        }
        for (AirCompressorCore m : mates) {
            if (m.contributes()) {
                drawing.add(m);
            }
        }
        for (AirCompressorCore d : drawing) {
            for (Live in : d.live) {
                if (in.filtered() && groupFlow > 0.0) {
                    AirCompressorIntakeBlock.wear(level, in.pos(), budget * in.flow() / groupFlow);
                }
            }
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

    // Lose a little of the room's air, `rate` blocks a second, farthest from the outlet first -- the cells
    // last reached by the fill -- down to `keep` blocks. Gone entirely, the room floods.
    private void drainTo(double rate, int keep) {
        LongOpenHashSet cells = space.cells();
        if (cells.size() <= keep) {
            drainCarry = 0.0;
            return;
        }
        drainCarry += rate / 20.0;
        int lose = Math.min((int) drainCarry, cells.size() - keep);
        drainCarry -= (int) drainCarry;
        for (int i = roomOrder.size() - 1 - drainCursor; lose > 0 && i >= 0; i--) {
            drainCursor++;
            if (cells.remove(roomOrder.getLong(i))) {
                lose--;
            }
        }
        // The room as it was found when loaded with the world, before any fill told its order.
        java.util.Iterator<Long> it = cells.iterator();
        while (lose > 0 && it.hasNext()) {
            it.next();
            it.remove();
            lose--;
        }
        if (cells.isEmpty()) {
            flood(state);
        }
    }

    private void flood(State why) {
        draining = false;
        shrinking = false;
        roomOrder = new LongArrayList();
        drainCursor = 0;
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
        if (level() != null) {
            unregisterSeeds(level());
        }
        leaderPos = null;
        group = List.of();
        share = 1.0;
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
        tag.putFloat("Power", power);
        // Every compressor working a room shows the room's: its state, air, and the group's totals.
        AirCompressorCore h = holder();
        tag.putInt("State", h.state.ordinal());
        tag.putInt("Held", h.heldBlocks());
        tag.putInt("Volume", h.roomVolume);
        tag.putInt("Capacity", h.roomCapacity);
        tag.putDouble("Flow", h.groupFlow);
        tag.putInt("Need", Config.COMPRESSOR_FILL_RATE.getAsInt() * (h.group.size() + 1));
        tag.putInt("Count", h.group.size() + 1);
        // This compressor's own part: what its intakes supply, and from where.
        tag.putDouble("OwnFlow", supplyFlow);
        boolean clean = false;
        boolean filtered = false;
        for (Live in : live) {
            clean |= !in.filtered();
            filtered |= in.filtered();
        }
        tag.putInt("OwnSource", (clean ? 1 : 0) | (filtered ? 2 : 0));
    }

    /** Read what {@link #writeClient} wrote. */
    public void readClient(CompoundTag tag) {
        power = tag.getFloat("Power");
        shownState = tag.getInt("State");
        shownHeld = tag.getInt("Held");
        shownVolume = tag.getInt("Volume");
        shownCapacity = tag.getInt("Capacity");
        shownFlow = tag.getDouble("Flow");
        shownNeed = tag.getInt("Need");
        shownCount = Math.max(1, tag.getInt("Count"));
        shownOwnFlow = tag.getDouble("OwnFlow");
        shownOwnSource = tag.getInt("OwnSource");
    }

    /**
     * The goggles' lines for this unit, read from its controller's mirror: the unit, what its air is
     * doing, and the room against what it can hold. Client side.
     */
    public void goggleLines(List<net.minecraft.network.chat.Component> tooltip) {
        AirCompressorCore c = controller();
        // First in the tooltip, the header sits beside the goggles' icon: leave room for it.
        tooltip.add(net.minecraft.network.chat.Component.literal(tooltip.isEmpty() ? "    " : "")
                .append(net.minecraft.network.chat.Component.translatable("fogged.goggles.compressor"))
                .withStyle(net.minecraft.ChatFormatting.WHITE));
        // Working a room with others: everything below is the room's, all of them together.
        if (c.shownCount > 1) {
            tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.compressors")
                    .withStyle(net.minecraft.ChatFormatting.GRAY)));
            tooltip.add(line(net.minecraft.network.chat.Component.literal(" " + c.shownCount)
                    .withStyle(net.minecraft.ChatFormatting.AQUA)));
        }
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
        // What the intakes supply against what the unit can push: short, it fills only that fast.
        tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.air_in")
                .withStyle(net.minecraft.ChatFormatting.GRAY)));
        tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.per_second",
                amount(c.shownFlow), amount(c.shownNeed))
                .withStyle(c.shownFlow + 0.5 >= c.shownNeed ? net.minecraft.ChatFormatting.AQUA
                        : net.minecraft.ChatFormatting.GOLD)));
        // Working a room with others: this compressor's own part, and where its air comes from.
        if (c.shownCount > 1) {
            String source = switch (c.shownOwnSource) {
                case 1 -> "clean";
                case 2 -> "filtered";
                case 3 -> "mixed";
                default -> "none";
            };
            tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.this_one")
                    .withStyle(net.minecraft.ChatFormatting.GRAY)));
            tooltip.add(line(net.minecraft.network.chat.Component.translatable("fogged.goggles.own_air",
                    amount(c.shownOwnFlow), net.minecraft.network.chat.Component.translatable("fogged.goggles.source." + source))
                    .withStyle(net.minecraft.ChatFormatting.AQUA)));
        }
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
        tag.putInt("Air", state.ordinal());
        tag.putFloat("Power", power);
        tag.putBoolean("Draining", draining);
    }

    /** Read what {@link #save} wrote, as the block entity loads. */
    public void load(CompoundTag tag) {
        savedState = State.of(tag.getInt("Air"));
        draining = tag.getBoolean("Draining"); // the room comes back with the world, still draining
        // The smoothed power picks up where it left off, so a loaded unit does not start from nothing
        // and call its room too big while it spins up.
        power = Mth.clamp(tag.getFloat("Power"), 0.0F, 1.0F);
        syncedRunning = power >= RUNNING;
    }

    /** The block entity is in the level: nothing to join, every compressor stands alone. */
    public void onLoad() {
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
