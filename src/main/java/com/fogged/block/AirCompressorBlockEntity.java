package com.fogged.block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fogged.AirFill;
import com.fogged.AirtightRooms;
import com.fogged.Config;
import com.fogged.CreateCompatibility;
import com.fogged.registry.ModBlockEntities;
import com.fogged.registry.ModParticles;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Drives an air compressor: its power, the unit it belongs to, and the room it holds.
 *
 * <h2>Units</h2>
 * Compressors facing the same way, side by side in the plane of their faces, join into square units
 * of 1x1, 2x2 or 3x3 ({@link #reform}). One block of each unit -- the corner lowest along both plane
 * axes -- is its controller: it alone ticks the room, holds the unit's energy (every member hands out
 * the controller's storage), and draws the one big fan. Everything a unit does scales with its face
 * area, so a 3x3 holds nine times the room of a 1x1 at the same power.
 *
 * <h2>Power</h2>
 * FE, always: {@link Config#COMPRESSOR_ENERGY_PER_TICK} per face, all or nothing. With Create, a
 * turning shaft or cog pointed into any member's side runs it as well, at the share of
 * {@link Config#COMPRESSOR_FULL_SPEED} it turns at; the unit takes whichever of the two gives more,
 * and only spends FE when the shaft alone falls short. The shaft is read, not joined -- Create is a soft
 * dependency, so the compressor adds no stress to the network.
 *
 * <h2>The room</h2>
 * The air goes out of the outlet and floods the room ({@link AirFill}). A sealed room then
 * <em>pressurizes</em>: the air spreads from the outlet outward in the order the fill reached each
 * cell, at {@link Config#COMPRESSOR_FILL_RATE}, and each cell is breathable from the moment it is
 * reached, the white specks drifting along the way the air flows. A leak floods the whole room at
 * once, and the specks then stream from the outlet to the hole and out of it, so the hole can be
 * found by following them -- Starship EVO's oxygen, which shows a leak by the molecules escaping it.
 *
 * <p>Any block change near the room re-checks it on the next tick ({@link AirtightRooms}): open a
 * door and the room floods, close it and it pressurizes again.
 */
public class AirCompressorBlockEntity extends BlockEntity {

    /** What the unit's air is doing, in the order the status line reads them. */
    public enum State {
        OFF, NO_INTAKE, BLOCKED, OVERSIZE, LEAK, PRESSURIZING, SEALED;

        static State of(int ordinal) {
            return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : OFF;
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

    // --- Unit (saved, and synced so the client knows which block draws the fan, and how big) ---
    private BlockPos controllerPos;
    private int size = 1;

    // --- Power ---
    private final Battery energy = new Battery();
    // Power, 0..1. Rises at once and falls over a few seconds (POWER_EASE per tick): an FE source that
    // supplies a little less than the draw, or a shaft network still loading, must not flick it off.
    private float power;
    private boolean syncedRunning;
    private static final float POWER_EASE = 0.05F;
    /** Below this the unit counts as off. */
    private static final float RUNNING = 0.02F;

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

    // --- Client ---
    private float fanAngle;
    private float lastFanAngle;

    public AirCompressorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIR_COMPRESSOR.get(), pos, state);
        this.controllerPos = pos;
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

    /**
     * Re-form every unit in the patch of same-facing compressors touching {@code pos}: gather the
     * patch, then cut it into squares, largest first, scanning from its lowest corner. The same patch
     * always cuts the same way, so units stay put as long as nothing in them changes.
     */
    public static void reform(Level level, BlockPos pos, Direction facing) {
        Direction[] uv = plane(facing);
        Set<BlockPos> patch = patch(level, pos, facing, uv);
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
            // Pool the members' energy into the new controller, so joining or splitting loses none.
            int pooled = 0;
            List<AirCompressorBlockEntity> members = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    BlockPos at = origin.relative(u, i).relative(v, j);
                    taken.add(at);
                    if (level.getBlockEntity(at) instanceof AirCompressorBlockEntity be) {
                        members.add(be);
                        pooled += be.energy.getEnergyStored();
                        be.energy.set(0);
                    }
                }
            }
            for (AirCompressorBlockEntity be : members) {
                be.join(origin, n);
            }
            if (level.getBlockEntity(origin) instanceof AirCompressorBlockEntity controller) {
                controller.energy.resize(n * n);
                controller.energy.set(pooled);
            }
        }
    }

    /** Re-form the units next to {@code pos} after the compressor there is gone or turned. */
    public static void reformAround(Level level, BlockPos pos, Direction facing) {
        Set<BlockPos> done = new HashSet<>();
        Direction[] uv = plane(facing);
        for (Direction d : new Direction[] {uv[0], uv[0].getOpposite(), uv[1], uv[1].getOpposite()}) {
            BlockPos n = pos.relative(d);
            if (!done.contains(n) && sameFacing(level, n, facing)) {
                done.addAll(patch(level, n, facing, uv));
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

    // Same-facing compressors connected to start within the plane, capped so a wall of them is cheap.
    private static Set<BlockPos> patch(Level level, BlockPos start, Direction facing, Direction[] uv) {
        Set<BlockPos> patch = new HashSet<>();
        if (!sameFacing(level, start, facing)) {
            return patch;
        }
        List<BlockPos> queue = new ArrayList<>();
        queue.add(start.immutable());
        patch.add(start.immutable());
        Direction[] steps = {uv[0], uv[0].getOpposite(), uv[1], uv[1].getOpposite()};
        for (int i = 0; i < queue.size() && patch.size() < 256; i++) {
            for (Direction d : steps) {
                BlockPos n = queue.get(i).relative(d);
                if (!patch.contains(n) && sameFacing(level, n, facing)) {
                    patch.add(n);
                    queue.add(n);
                }
            }
        }
        return patch;
    }

    private static boolean sameFacing(Level level, BlockPos pos, Direction facing) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof AirCompressorBlock && s.getValue(AirCompressorBlock.FACING) == facing;
    }

    private void join(BlockPos controller, int n) {
        boolean changed = !controller.equals(controllerPos) || n != size;
        controllerPos = controller.immutable();
        size = n;
        if (changed) {
            if (!isController()) {
                dropRoom();
            }
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                level.invalidateCapabilities(worldPosition);
            }
        }
    }

    public boolean isController() {
        return controllerPos.equals(worldPosition);
    }

    public int size() {
        return size;
    }

    /** This unit's controller: the block that holds its energy and its room. Itself if that is gone. */
    public AirCompressorBlockEntity controller() {
        if (isController() || level == null) {
            return this;
        }
        return level.getBlockEntity(controllerPos) instanceof AirCompressorBlockEntity c ? c : this;
    }

    /** The energy every member hands out: the controller's. */
    public IEnergyStorage energyStorage() {
        return controller().energy;
    }

    private Direction facing() {
        return getBlockState().getValue(AirCompressorBlock.FACING);
    }

    private List<BlockPos> members() {
        Direction[] uv = plane(facing());
        List<BlockPos> out = new ArrayList<>(size * size);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                out.add(worldPosition.relative(uv[0], i).relative(uv[1], j));
            }
        }
        return out;
    }

    // ================================================================================================
    // Server tick
    // ================================================================================================

    public static void serverTick(Level level, BlockPos pos, BlockState blockState, AirCompressorBlockEntity be) {
        if (!be.isController()) {
            be.dropRoom();
            return;
        }
        be.tickController((ServerLevel) level);
    }

    private void tickController(ServerLevel level) {
        if (space == null) {
            space = AirtightRooms.open(level, worldPosition);
        }
        Direction facing = facing();
        List<BlockPos> members = members();
        int area = size * size;

        updatePower(level, members, facing, area);
        capacity = (int) (Config.COMPRESSOR_VOLUME.getAsInt() * area * power);
        // Capacity drifts with power every tick; it only matters once it crosses the room: a held room
        // that no longer fits, or a room too big that might fit now.
        boolean capacityCrossed = ((state == State.SEALED || state == State.PRESSURIZING) && capacity < roomVolume)
                || (state == State.OVERSIZE && capacity > checkedCapacity);
        // A room that is not holding is looked at every second, so shutting the hole (a door swung to)
        // seals it quickly even where no block-change event says so -- as on a Sable ship, whose blocks
        // can change without one; a ship's room is checked that often throughout, for the same reason.
        int every = state == State.SEALED && !space.onShip() ? RECHECK_INTERVAL : FAST_RECHECK;
        boolean recheck = space.takeDirty() || capacityCrossed
                || level.getGameTime() % every == Math.floorMod(worldPosition.asLong(), every);
        if (retryIn > 0) {
            recheck = --retryIn == 0;
        }

        Boolean intake = intakeOpen(level, members, facing);
        if (power < RUNNING) {
            settle(State.OFF);
        } else if (intake == null) {
            // Behind the intake is a chunk not loaded yet: wait for it rather than call it shut.
        } else if (!intake) {
            settle(State.NO_INTAKE);
        } else if (recheck || state == State.OFF || state == State.NO_INTAKE) {
            check(level, members, facing);
        }

        if (state == State.PRESSURIZING) {
            pressurize(level, area);
        }
        emit(level, members, facing);
        space.report(state.ordinal(), state == State.SEALED, roomVolume, capacity, leakPath);
    }

    private void updatePower(ServerLevel level, List<BlockPos> members, Direction facing, int area) {
        float shaft = 0.0F;
        if (CreateCompatibility.installed()) {
            float rpm = 0.0F;
            for (BlockPos m : members) {
                for (Direction d : Direction.values()) {
                    if (d.getAxis() != facing.getAxis()) {
                        rpm = Math.max(rpm, CreateCompatibility.kineticSpeed(level, m.relative(d), d.getAxis()));
                    }
                }
            }
            shaft = Math.min(1.0F, rpm / Config.COMPRESSOR_FULL_SPEED.getAsInt());
        }
        float fe = 0.0F;
        if (shaft < 1.0F) {
            int need = Config.COMPRESSOR_ENERGY_PER_TICK.getAsInt() * area;
            energy.resize(area);
            if (need <= 0) {
                fe = 1.0F;
            } else {
                // Whatever share of the draw is there runs it at that share, rather than all or nothing.
                int take = Math.min(energy.getEnergyStored(), need);
                if (take > 0) {
                    energy.set(energy.getEnergyStored() - take);
                    setChanged();
                }
                fe = (float) take / need;
            }
        }
        // Up at once, down slowly: a unit coming on holds its room straight away, and one starved for a
        // moment (a supply a little short of the draw, a shaft network still loading) rides it out.
        float raw = Math.max(shaft, fe);
        power = raw >= power ? raw : power + (raw - power) * POWER_EASE;
        boolean running = power >= RUNNING;
        if (running != syncedRunning) {
            syncedRunning = running;
            sync(); // the client only needs on or off, for the fan
        }
    }

    // The intake needs air behind it: at least one intake cell whose face toward the compressor is
    // open. Null when that cannot be told yet: the chunk behind it is not loaded.
    private static Boolean intakeOpen(Level level, List<BlockPos> members, Direction facing) {
        Direction back = facing.getOpposite();
        for (BlockPos m : members) {
            BlockPos in = m.relative(back);
            if (!level.isLoaded(in)) {
                return null;
            }
            if (!AirFill.closes(level, in, level.getBlockState(in), facing)) {
                return true;
            }
        }
        return false;
    }

    // Whether every open intake cell is inside the room the outlet fills: the compressor is standing
    // in the room rather than in its wall. One intake cell outside is enough to draw from.
    private static boolean intakeInside(LongArrayList room, List<BlockPos> members, Direction facing) {
        LongOpenHashSet cells = new LongOpenHashSet(room);
        for (BlockPos m : members) {
            if (!cells.contains(m.relative(facing.getOpposite()).asLong())) {
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
        AirFill result = AirFill.run(level, seeds, Math.max(1, capacity));
        if (result.result() == AirFill.Result.UNLOADED) {
            // The room runs into a chunk still loading -- usually just after a world loads. Leave the
            // air as it is and look again shortly, rather than flood a room that is likely fine.
            retryIn = 10;
            return;
        }
        fill = result;
        checkedCapacity = capacity;
        space.watch(result, ducts);
        switch (result.result()) {
            case SEALED -> {
                LongArrayList order = result.order();
                if (intakeInside(order, members, facing)) {
                    flood(State.NO_INTAKE); // drawing the room's own air back in: nothing clean comes in
                    return;
                }
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

    // Spread the air on through the next stretch of the room, a speck here and there along the flow.
    private void pressurize(ServerLevel level, int area) {
        double perTick = Config.COMPRESSOR_FILL_RATE.getAsInt() * area * power / 20.0;
        int budget = Math.max(1, (int) perTick + (level.random.nextDouble() < perTick - (int) perTick ? 1 : 0));
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
            setChanged();
        }
    }

    private void dropRoom() {
        if (space != null && level != null) {
            AirtightRooms.close(level, worldPosition);
        }
        space = null;
        fill = null;
        state = State.OFF;
    }

    // ================================================================================================
    // Specks
    // ================================================================================================

    private static ParticleOptions speck() {
        return ColorParticleOption.create(ModParticles.AIR.get(), AIR_TINT[0], AIR_TINT[1], AIR_TINT[2]);
    }

    private void emit(ServerLevel level, List<BlockPos> members, Direction facing) {
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

    public static void clientTick(Level level, BlockPos pos, BlockState blockState, AirCompressorBlockEntity be) {
        be.lastFanAngle = be.fanAngle;
        // Up to two thirds of a turn a second... per tick: 24 degrees at full power.
        be.fanAngle = (be.fanAngle + 24.0F * be.power) % 360.0F;
        if (be.fanAngle < be.lastFanAngle) {
            be.lastFanAngle -= 360.0F;
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
    // Save / sync
    // ================================================================================================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeUnit(tag);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Air", state.ordinal());
        tag.putFloat("Power", power);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        fresh = false;
        readUnit(tag);
        energy.resize(size * size);
        energy.set(tag.getInt("Energy"));
        savedState = State.of(tag.getInt("Air"));
        // The smoothed power picks up where it left off, so a loaded unit does not start from nothing
        // and call its room too big while it spins up.
        power = Mth.clamp(tag.getFloat("Power"), 0.0F, 1.0F);
        syncedRunning = power >= RUNNING;
    }

    private void writeUnit(CompoundTag tag) {
        BlockPos off = controllerPos.subtract(worldPosition);
        tag.putInt("CtrlX", off.getX());
        tag.putInt("CtrlY", off.getY());
        tag.putInt("CtrlZ", off.getZ());
        tag.putInt("Size", size);
    }

    private void readUnit(CompoundTag tag) {
        controllerPos = worldPosition.offset(tag.getInt("CtrlX"), tag.getInt("CtrlY"), tag.getInt("CtrlZ"));
        size = Mth.clamp(tag.contains("Size") ? tag.getInt("Size") : 1, 1, MAX_SIZE);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        writeUnit(tag);
        tag.putFloat("Power", power);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readUnit(tag);
        power = tag.getFloat("Power");
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket pkt,
            HolderLookup.Provider registries) {
        handleUpdateTag(pkt.getTag(), registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // The block entity's data alone, to the players who have this chunk. Not sendBlockUpdated: that
    // also re-sends the block, and the client rebuilds the whole chunk section for it.
    private void sync() {
        if (level instanceof ServerLevel server) {
            Packet<ClientGamePacketListener> packet = getUpdatePacket();
            for (net.minecraft.server.level.ServerPlayer player : server.getChunkSource().chunkMap
                    .getPlayers(new net.minecraft.world.level.ChunkPos(worldPosition), false)) {
                player.connection.send(packet);
            }
        }
    }

    // A freshly placed compressor (not one loaded from disk) joins its neighbours here: the block's
    // onPlace runs before this block entity exists, so it cannot.
    private boolean fresh = true;

    @Override
    public void onLoad() {
        super.onLoad();
        if (fresh && level != null && !level.isClientSide) {
            fresh = false;
            reform(level, worldPosition, facing());
        }
    }

    // Unloading with its chunk is not being removed: the room stays registered (and saved) while the
    // chunk is away, so the murk does not take what is in it before the compressor is back to say.
    private boolean unloading;

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide && !unloading) {
            dropRoom();
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        unloading = true;
        space = null; // let go of it without closing it
    }

    // ================================================================================================

    /** FE storage sized to the unit, pooled at the controller. Takes FE in, never hands it out. */
    static final class Battery extends EnergyStorage {
        Battery() {
            super(Config.COMPRESSOR_ENERGY_CAPACITY.getAsInt(), Integer.MAX_VALUE, 0);
        }

        void resize(int area) {
            capacity = Config.COMPRESSOR_ENERGY_CAPACITY.getAsInt() * area;
            energy = Math.min(energy, capacity);
        }

        void set(int value) {
            energy = Mth.clamp(value, 0, capacity);
        }
    }
}
