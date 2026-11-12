package com.fogged.create;

import java.util.List;

import com.fogged.Config;
import com.fogged.block.AirCompressorCore;
import com.fogged.block.CompressorHost;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The kinetic air compressor's block entity: an {@link AirCompressorCore} driven by rotation.
 *
 * <p>One shaft drives the whole unit this block is in (FE blocks of it included): fully at Create's
 * medium speed or faster, not at all slower -- {@link KineticAirCompressorBlock} asks for that speed, so
 * Create's goggles show it. An overstressed network turns it not at all. The unit's whole load sits on
 * this block, in SU and the same at any speed: {@link Config#KINETIC_STRESS_PER_BLOCK} for every block
 * of room it holds, and four times that over the whole room while it is filling it. It changes as the
 * room does, so the network carries a sealed base at a steady load and a breach at a moment's peak.
 */
public class KineticAirCompressorBlockEntity extends KineticBlockEntity implements CompressorHost {

    /** Filling a block costs this many times holding one, as it does in FE (2 against 0.5). */
    private static final float FILTER_FACTOR = 4.0F;
    /** Ticks between telling the network about a changed load: often enough, not every tick. */
    private static final int STRESS_INTERVAL = 10;

    private final AirCompressorCore core;

    public KineticAirCompressorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.core = new AirCompressorCore(this, this, new ShaftDrive());
    }

    @Override
    public AirCompressorCore core() {
        return core;
    }

    @Override
    public void syncToClient() {
        sendData();
    }

    // This block's shaft drives the whole unit it is in: fully while it turns at its minimum speed or
    // faster, not at all below it (or overstressed, when Create stops it).
    private final class ShaftDrive implements AirCompressorCore.Drive {
        @Override
        public float hold(List<BlockPos> members, int heldBlocks) {
            return getSpeed() != 0.0F && isSpeedRequirementFulfilled() ? 1.0F : 0.0F;
        }

        // As Create shows a block's stress: its own label, then the SU in aqua -- the same at any speed.
        @Override
        public List<net.minecraft.network.chat.Component> readout() {
            List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
            lines.add(net.minecraft.network.chat.Component.translatable("create.tooltip.stressImpact")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            lines.add(net.minecraft.network.chat.Component.literal(" " + AirCompressorCore.amount(unitStress()))
                    .append(net.minecraft.network.chat.Component.translatable("create.generic.unit.stress"))
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
            if (getSpeed() != 0.0F && !isSpeedRequirementFulfilled()) {
                lines.add(net.minecraft.network.chat.Component.translatable("fogged.goggles.too_slow")
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
            return lines;
        }

        @Override
        public boolean shaft() {
            return true;
        }

        @Override
        public int filter(int want) {
            return want; // paid in stress, not per block
        }
    }

    // The whole unit's load in SU, whatever the speed: this is the one block of it the shaft turns. On
    // the client, from what the server last sent (the goggles ask there).
    private float unitStress() {
        AirCompressorCore controller = core.controller();
        if (controller.stopped()) {
            return 0.0F; // stopped by redstone: no load on the network
        }
        float perBlock = (float) (double) Config.KINETIC_STRESS_PER_BLOCK.get();
        boolean client = level != null && level.isClientSide;
        // Server side, its share of the room it holds or shares with other units (AirCompressorCore#loadHeld).
        boolean filling = client ? controller.shownPressurizing() : controller.holder().pressurizing();
        int volume = client ? controller.shownVolume() : controller.loadVolume();
        int held = client ? controller.shownHeld() : controller.loadHeld();
        return filling ? perBlock * FILTER_FACTOR * volume : perBlock * held;
    }

    // Create counts a block's load per RPM and multiplies it by the speed; handing it the SU over the
    // speed keeps the load the same at any speed. Not turning, it is reckoned at the minimum speed.
    private float stressShare() {
        float speed = Math.max(Math.abs(getTheoreticalSpeed()), MIN_SPEED_RPM);
        return unitStress() / speed;
    }

    /** Below Create's medium speed (30 RPM by default) the compressor does not run; reckoned there when still. */
    private static final float MIN_SPEED_RPM = 30.0F;

    @Override
    public float calculateStressApplied() {
        float stress = stressShare();
        lastStressApplied = stress;
        return stress;
    }

    // A change of speed changes the per-RPM share that holds the load steady: tell the network.
    @Override
    public void onSpeedChanged(float previousSpeed) {
        super.onSpeedChanged(previousSpeed);
        if (level != null && !level.isClientSide) {
            pushStress();
        }
    }

    private void pushStress() {
        float stress = stressShare();
        if (Math.abs(stress - lastStressApplied) > 1.0E-4F) {
            lastStressApplied = stress;
            if (hasNetwork()) {
                getOrCreateNetwork().updateStressFor(this, stress);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) {
            return;
        }
        if (level.isClientSide) {
            core.clientTick();
            return;
        }
        core.serverTick();
        if (level.getGameTime() % STRESS_INTERVAL == Math.floorMod(worldPosition.asLong(), STRESS_INTERVAL)) {
            pushStress();
        }
    }

    // The unit's own readout only, the same from every block of it: not Create's kinetic section too,
    // which only the shafted block would show and which says again what the readout's stress line does.
    @Override
    public boolean addToGoggleTooltip(List<net.minecraft.network.chat.Component> tooltip, boolean isPlayerSneaking) {
        core.goggleLines(tooltip);
        return true;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    @Override
    public void initialize() {
        super.initialize();
        core.onLoad();
    }

    @Override
    public void remove() {
        super.remove();
        core.onRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        core.onChunkUnloaded();
        super.onChunkUnloaded();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            core.writeClient(tag);
        } else {
            core.save(tag);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            core.readClient(tag);
        } else {
            core.load(tag);
        }
    }
}
