package com.fogged;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * All soft (no compile-time dependency) integration with Create lives here: reading an Encased Fan's
 * state by name at runtime for the nozzle filter, and drawing a look-alike HUD indicator for a Create
 * backtank feeding air at our dry breathing boundary. If Create is absent, or its internals change,
 * every call here degrades harmlessly (empty fan state / nothing drawn) rather than throwing.
 *
 * <p>Fan: an Encased Fan is a {@code KineticBlockEntity} ({@code getSpeed()} -> signed RPM) and an
 * {@code IAirCurrentSource} ({@code getAirFlowDirection()} -> which way it moves air). We read both:
 * the speed magnitude drives the breathing radius, and the flow direction tells suck from blow.
 *
 * <p>Backtank HUD: Create's diving-gear overlay (RemainingAirOverlay) bails whenever the eye is in air,
 * so it never shows at our dry boundary even though the backtank is feeding the player air server-side.
 * {@link #onRenderGui} draws a look-alike in exactly that case. Vanilla's own air bubbles already show
 * up on their own (they trigger off {@code getAirSupply() < getMaxAirSupply()}, not off real water), so
 * there is no need to draw those ourselves. Reads the backtank's air via its registered data
 * component, so there is no compile dependency on Create -- if it isn't installed the component is
 * absent and nothing renders.
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class CreateCompatibility {

    // Create is optional at runtime (no required dependency, no compile-time reference). When it is
    // absent every fan lookup below is skipped and the nozzle filter simply stays inert -- same
    // isolation pattern SableCompatibility uses for Sable.
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

    // --- Backtank HUD overlay ------------------------------------------------------------------

    // Create registers this Integer component under "create:banktank_air" (its own spelling). It is
    // network-synchronised, so the value is present on the client.
    private static final ResourceLocation BACKTANK_AIR_ID =
            ResourceLocation.fromNamespaceAndPath("create", "banktank_air");

    private static final EquipmentSlot[] ARMOUR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    @SubscribeEvent
    static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || player.isCreative() || !belowBoundaryInAir(player)) {
            return;
        }

        // Vanilla's own air bubbles already show whenever getAirSupply() < getMaxAirSupply(), regardless
        // of whether the eye is in real water -- BreathHandler draining the air stat is enough to trigger
        // them on its own. Drawing a second copy here only doubled up and drifted out of sync with it.

        // Backtank-air indicator where Create's would be (only while gear is feeding air).
        int air = feedingAir(player);
        if (air > 0) {
            renderIndicator(event.getGuiGraphics(), mc.font, displayTank(player), air);
        }
    }

    private static void renderIndicator(GuiGraphics g, Font font, ItemStack tank, int air) {
        // Same anchor Create uses: right of centre, near the bottom; nudged down for a fire-proof tank.
        boolean fireproof = tank.has(DataComponents.FIRE_RESISTANT);
        int x = g.guiWidth() / 2 + 90;
        int y = g.guiHeight() - 53 + (fireproof ? 9 : 0);

        g.renderItem(tank, x, y);

        // Backtank drains 1 air per 20 ticks (~1s), so the air count is roughly the seconds remaining.
        int secs = Math.max(0, air - 1);
        Component text = Component.literal(secs / 60 + ":" + String.format("%02d", secs % 60));
        int color = 0xFFFFFFFF;
        if (secs < 60 && (secs & 1) == 0) {
            color = 0xFFFF5555; // flash red when running low, like Create
        }
        g.drawString(font, text, x + 16, y + 5, color);
    }

    // Total backtank air the player is breathing from right now, or 0 if our indicator shouldn't show:
    // not creative/spectator, eye in air (Create's own HUD covers real fluid), below the boundary, and
    // wearing a Create diving helmet over a backtank that still has air.
    private static int feedingAir(LocalPlayer player) {
        if (!belowBoundaryInAir(player) || !isDivingHelmet(player.getItemBySlot(EquipmentSlot.HEAD))) {
            return 0;
        }
        DataComponentType<?> airType = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(BACKTANK_AIR_ID).orElse(null);
        if (airType == null) {
            return 0; // Create not installed
        }
        int total = 0;
        for (EquipmentSlot slot : ARMOUR) {
            total += airOf(player.getItemBySlot(slot), airType);
        }
        return total;
    }

    // True when the player is below the breathing boundary with the eye in air (our fake-underwater
    // zone) -- where vanilla suppresses the air bar. Creative players are exempt.
    private static boolean belowBoundaryInAir(LocalPlayer player) {
        return player != null
                && !player.isCreative()
                && player.getEyeInFluidType().isAir()
                && player.getEyeY() < Config.breathHeight(player.level()) + Config.PLANE_SURFACE_OFFSET;
    }

    // The backtank icon to draw: the first worn item that still has air.
    private static ItemStack displayTank(LocalPlayer player) {
        DataComponentType<?> airType = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(BACKTANK_AIR_ID).orElse(null);
        if (airType != null) {
            for (EquipmentSlot slot : ARMOUR) {
                ItemStack stack = player.getItemBySlot(slot);
                if (airOf(stack, airType) > 0) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isDivingHelmet(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals("create") && id.getPath().contains("diving_helmet");
    }

    private static int airOf(ItemStack stack, DataComponentType<?> airType) {
        if (stack.isEmpty()) {
            return 0;
        }
        Object value = stack.get(airType);
        return value instanceof Integer i ? i : 0;
    }

    private CreateCompatibility() {
    }
}
