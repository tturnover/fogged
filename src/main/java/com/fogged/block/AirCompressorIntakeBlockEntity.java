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
        changed();
    }

    /** Take the filter out: returns it, leaving none. */
    public ItemStack takeFilter() {
        ItemStack out = filter;
        filter = ItemStack.EMPTY;
        changed();
        return out;
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
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        filter = tag.contains("Filter") ? ItemStack.parseOptional(registries, tag.getCompound("Filter")) : ItemStack.EMPTY;
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
