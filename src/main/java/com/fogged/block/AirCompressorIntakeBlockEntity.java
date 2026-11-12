package com.fogged.block;

import com.fogged.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The intake's block entity: the filter fitted in it, kept whole so it comes back out as the same item.
 * Synced, so Create's goggles can name it. With Create installed it is the goggled kind
 * (com.fogged.create).
 */
public class AirCompressorIntakeBlockEntity extends BlockEntity {

    private ItemStack filter = ItemStack.EMPTY;
    /** Blocks of air the fitted filter has cleaned so far. */
    private double wear;
    private int syncedPercent;

    public AirCompressorIntakeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIR_COMPRESSOR_INTAKE.get(), pos, state);
    }

    /** A new intake block entity: the goggled kind with Create installed. */
    public static AirCompressorIntakeBlockEntity create(BlockPos pos, BlockState state) {
        return CREATE ? com.fogged.create.CreateContent.goggledIntake(pos, state) : new AirCompressorIntakeBlockEntity(pos, state);
    }

    private static final boolean CREATE = net.neoforged.fml.ModList.get().isLoaded("create");

    /** The filter fitted, or empty. */
    public ItemStack filter() {
        return filter;
    }

    void setFilter(ItemStack stack) {
        filter = stack;
        wear = 0.0;
        changed();
    }

    /** Take the filter out: returns it, leaving none. A worn filter comes out as it is, and a new one starts fresh. */
    public ItemStack takeFilter() {
        ItemStack out = filter;
        filter = ItemStack.EMPTY;
        wear = 0.0;
        changed();
        return out;
    }

    /** Wear the filter by {@code blocks} of air cleaned, out of {@code life}; true once it is spent. */
    boolean wear(double blocks, int life) {
        wear += blocks;
        setChanged();
        int percent = (int) Math.min(100, wear * 100 / life);
        if (percent != syncedPercent) {
            syncedPercent = percent;
            changed(); // the goggles read it
        }
        return wear >= life;
    }

    /** How much of the filter is used up, 0..1. Either side. */
    public double worn() {
        int life = com.fogged.Config.FILTER_LIFE.getAsInt();
        return life <= 0 ? 0.0 : Math.min(1.0, wear / life);
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!filter.isEmpty()) {
            tag.put("Filter", filter.save(registries));
        }
        tag.putDouble("Wear", wear);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        filter = tag.contains("Filter") ? ItemStack.parseOptional(registries, tag.getCompound("Filter")) : ItemStack.EMPTY;
        wear = tag.getDouble("Wear");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
