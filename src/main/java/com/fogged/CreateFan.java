package com.fogged;

import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.fml.ModList;

/**
 * Soft (reflection-only) integration with Create's Encased Fan. The mod has no compile-time dependency
 * on Create -- exactly like {@link BacktankAirOverlay} -- so we read the fan's state by name at runtime.
 * If Create is absent, or its internals change, every call here degrades to {@link FanState#NONE}
 * rather than throwing.
 *
 * <p>An Encased Fan is a {@code KineticBlockEntity} ({@code getSpeed()} -> signed RPM) and an
 * {@code IAirCurrentSource} ({@code getAirFlowDirection()} -> which way it moves air). We read both:
 * the speed magnitude drives the breathing radius, and the flow direction tells suck from blow.
 */
public final class CreateFan {

    // Create is optional at runtime (no required dependency, no compile-time reference). When it is
    // absent every lookup below is skipped and the nozzle filter simply stays inert -- same isolation
    // pattern PlaneSensor uses for Sable.
    private static final boolean CREATE = ModList.get().isLoaded("create");

    private static final ResourceLocation ENCASED_FAN = ResourceLocation.fromNamespaceAndPath("create", "encased_fan");

    // Cached reflective handles, resolved lazily off the first fan BE encountered.
    private static volatile Method getSpeed;
    private static volatile Method getAirFlowDirection;
    private static volatile boolean resolveFailed;

    /** A fan's readout: speed magnitude (RPM) and the direction it currently moves air (null if idle). */
    public record FanState(float speed, Direction airFlow) {
        public static final FanState NONE = new FanState(0.0F, null);
    }

    /**
     * The attached fan's state, checking the block on {@code attachSide} first (a Create nozzle only
     * attaches to a fan, so that is where it should be) then the remaining neighbours as a fallback.
     * Returns {@link FanState#NONE} when no running fan is found or Create is not present.
     */
    public static FanState attachedFan(Level level, BlockPos pos, Direction attachSide) {
        if (!CREATE) {
            return FanState.NONE; // Create not installed: no fans exist.
        }
        FanState best = fanAt(level, pos.relative(attachSide));
        if (best.speed() > 0.0F) {
            return best;
        }
        for (Direction d : Direction.values()) {
            if (d == attachSide) {
                continue;
            }
            FanState other = fanAt(level, pos.relative(d));
            if (other.speed() > 0.0F) {
                return other;
            }
        }
        return FanState.NONE;
    }

    /** Whether Create is installed at all. Callers can skip fan-dependent behaviour when it isn't. */
    public static boolean installed() {
        return CREATE;
    }

    /** True when the block at {@code pos} is a Create Encased Fan. */
    public static boolean isFan(BlockGetter level, BlockPos pos) {
        return CREATE && ENCASED_FAN.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()));
    }

    /**
     * The direction a Create Encased Fan at {@code pos} faces (the way it blows / where its nozzle
     * attaches), or {@code null} if it is not a fan. Read generically off the block's {@code facing}
     * property, so there is still no compile dependency on Create's block class.
     */
    public static Direction fanFacing(BlockGetter level, BlockPos pos) {
        if (!isFan(level, pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("facing") && state.getValue(property) instanceof Direction dir) {
                return dir;
            }
        }
        return null;
    }

    private static FanState fanAt(Level level, BlockPos pos) {
        if (!level.isLoaded(pos) || !isFan(level, pos)) {
            return FanState.NONE;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !resolve(be)) {
            return FanState.NONE;
        }
        try {
            float speed = getSpeed.invoke(be) instanceof Float f ? Math.abs(f) : 0.0F;
            Direction flow = getAirFlowDirection.invoke(be) instanceof Direction d ? d : null;
            return new FanState(speed, flow);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return FanState.NONE;
        }
    }

    /** Resolve (once) the reflective handles off a real fan BE. False if Create's API doesn't match. */
    private static boolean resolve(BlockEntity fanBe) {
        if (getSpeed != null && getAirFlowDirection != null) {
            return true;
        }
        if (resolveFailed) {
            return false;
        }
        try {
            getSpeed = fanBe.getClass().getMethod("getSpeed");
            getAirFlowDirection = fanBe.getClass().getMethod("getAirFlowDirection");
            return true;
        } catch (NoSuchMethodException e) {
            resolveFailed = true;
            Fogged.LOGGER.warn("Create fan present but expected methods not found; nozzle filter stays inert", e);
            return false;
        }
    }

    private CreateFan() {}
}
