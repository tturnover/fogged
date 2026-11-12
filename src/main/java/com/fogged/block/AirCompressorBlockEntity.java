package com.fogged.block;

import java.util.List;

import com.fogged.Config;
import com.fogged.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * The FE air compressor's block entity: an {@link AirCompressorCore} paid for in FE.
 *
 * <p>A room costs {@link Config#COMPRESSOR_HOLD_FE} per block per tick to hold, and
 * {@link Config#COMPRESSOR_FILTER_FE} per block, once, to fill. The FE comes in through the four sides
 * -- not the intake or the outlet -- of any member of the unit, into the one store the controller keeps
 * -- which holds no reserve: it takes in only what the next tick will use (see {@link Battery}), so it
 * runs straight off its supply and stops when the supply does. A unit short of its holding cost runs at
 * the share it can pay, and holds that share of its room.
 */
public class AirCompressorBlockEntity extends BlockEntity implements CompressorHost {

    private final Battery energy = new Battery();
    private final AirCompressorCore core;
    // Holding is paid in fractions of an FE a tick; what is owed carries over rather than rounding away.
    private double owed;
    // FE spent this tick, and its running average: what the goggles show as the draw.
    private int spentThisTick;
    private float draw;
    // The controller's draw, mirrored for the goggles.
    private float shownDraw;

    public AirCompressorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIR_COMPRESSOR.get(), pos, state);
        this.core = new AirCompressorCore(this, this, new FeDrive());
    }

    /**
     * A new FE compressor block entity: with Create installed, the kind Create's goggles can read
     * (com.fogged.create.GoggledAirCompressorBlockEntity), so it never names Create's classes itself.
     */
    public static AirCompressorBlockEntity create(BlockPos pos, BlockState state) {
        return CREATE ? com.fogged.create.CreateContent.goggledCompressor(pos, state) : new AirCompressorBlockEntity(pos, state);
    }

    private static final boolean CREATE = net.neoforged.fml.ModList.get().isLoaded("create");

    /** The goggles' lines for this unit (see AirCompressorCore#goggleLines). Client side. */
    public void goggleLines(List<net.minecraft.network.chat.Component> tooltip) {
        core.goggleLines(tooltip);
    }

    @Override
    public AirCompressorCore core() {
        return core;
    }

    /** The energy store FE comes into through {@code side}: the controller's, and only on the four sides. */
    public IEnergyStorage energyStorage(Direction side) {
        if (side != null && side.getAxis() == core.facing().getAxis()) {
            return null; // not through the intake or the outlet
        }
        return core.controller().blockEntity() instanceof AirCompressorBlockEntity c ? c.energy : energy;
    }

    // Pays from the controller's store. Holding first, a tick's worth of fractions at a time.
    private final class FeDrive implements AirCompressorCore.Drive {
        @Override
        public float hold(List<BlockPos> members, int heldBlocks) {
            double need = heldBlocks * Config.COMPRESSOR_HOLD_FE.get();
            if (need <= 0.0) {
                return energy.getEnergyStored() > 0 ? 1.0F : 0.0F; // nothing to hold: on while charged
            }
            int stored = energy.getEnergyStored();
            if (stored <= 0) {
                owed = 0.0;
                return 0.0F; // nothing to pay with: off, whatever fraction would have been due this tick
            }
            owed += need;
            int whole = (int) owed;
            int paid = Math.min(whole, stored);
            if (paid > 0) {
                energy.set(stored - paid);
                spentThisTick += paid;
                setChanged();
            }
            owed -= paid;
            if (paid >= whole) {
                return 1.0F;
            }
            owed = Math.min(owed, need); // a debt does not pile up past a tick's worth
            return (float) paid / whole;
        }

        @Override
        public int filter(int want) {
            double each = Config.COMPRESSOR_FILTER_FE.get();
            if (each <= 0.0) {
                return want;
            }
            int afford = (int) Math.min(want, energy.getEnergyStored() / each);
            if (afford > 0) {
                int cost = (int) Math.ceil(afford * each);
                energy.set(energy.getEnergyStored() - cost);
                spentThisTick += cost;
                setChanged();
            }
            return afford;
        }

        @Override
        public List<net.minecraft.network.chat.Component> readout() {
            return List.of(
                    net.minecraft.network.chat.Component.translatable("fogged.goggles.energy_usage")
                            .withStyle(net.minecraft.ChatFormatting.GRAY),
                    net.minecraft.network.chat.Component.literal(" " + AirCompressorCore.amount(shownDraw) + "\u26A1/t")
                            .withStyle(net.minecraft.ChatFormatting.AQUA));
        }

        // Pool the members' energy into the controller, so joining or splitting loses none.
        @Override
        public void joined(AirCompressorCore controller) {
            if (controller == core || !(controller.blockEntity() instanceof AirCompressorBlockEntity c)) {
                return;
            }
            c.energy.set(c.energy.getEnergyStored() + energy.getEnergyStored());
            energy.set(0);
            c.setChanged();
            setChanged();
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, AirCompressorBlockEntity be) {
        be.core.serverTick();
        be.draw += (be.spentThisTick - be.draw) * 0.1F; // about a second's average
        be.spentThisTick = 0;
        be.energy.limit(be.nextDemand());
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, AirCompressorBlockEntity be) {
        be.core.clientTick();
    }

    // --- Save / sync / lifecycle --------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        core.save(tag);
        tag.putInt("Energy", energy.getEnergyStored());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        core.load(tag);
        energy.set(tag.getInt("Energy"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        core.writeClient(tag);
        tag.putFloat("Draw", draw);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        core.readClient(tag);
        shownDraw = tag.getFloat("Draw");
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

    // The block entity's data alone, to the players who have this chunk; not the block as well.
    @Override
    public void syncToClient() {
        if (level instanceof ServerLevel server) {
            Packet<ClientGamePacketListener> packet = getUpdatePacket();
            for (net.minecraft.server.level.ServerPlayer player : server.getChunkSource().chunkMap
                    .getPlayers(new net.minecraft.world.level.ChunkPos(worldPosition), false)) {
                player.connection.send(packet);
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        core.onLoad();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        core.onRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        core.onChunkUnloaded();
    }

    // What the unit will spend next tick at most: holding the room it has, and a full tick's filtering.
    private int nextDemand() {
        if (!core.isController()) {
            return 0;
        }
        int area = core.size() * core.size();
        double hold = core.heldBlocks() * Config.COMPRESSOR_HOLD_FE.get();
        double fill = Config.COMPRESSOR_FILL_RATE.getAsInt() * area / 20.0 * Config.COMPRESSOR_FILTER_FE.get();
        return (int) Math.ceil(hold + fill);
    }

    /**
     * Where FE comes in, at the controller. Not a store: it only takes in what the next tick will
     * spend (resized every tick by {@link #limit}), so nothing is held back and the unit runs straight
     * off its supply. Takes FE in, never hands it out.
     */
    static final class Battery extends EnergyStorage {
        Battery() {
            super(0, Integer.MAX_VALUE, 0);
        }

        void limit(int demand) {
            capacity = Math.max(0, demand);
            energy = Math.min(energy, capacity);
        }

        void set(int value) {
            energy = Mth.clamp(value, 0, capacity);
        }
    }
}
