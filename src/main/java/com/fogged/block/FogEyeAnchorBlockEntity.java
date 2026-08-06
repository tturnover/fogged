package com.fogged.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared by {@link FogEyeBlockEntity} and {@link FogEyeStemBlockEntity}: the one thing either needs to
 * carry is which root this cell currently considers itself anchored to. Every root a plant has struck
 * is equally legitimate -- there is no single "main" one -- so this is just whichever root growth (or a
 * reroot after losing support, see {@code FogEyeBlock#tryReroot}) last pointed this cell at. Null means
 * no data was ever recorded (a block from before this existed, or placed by hand/structure/command);
 * callers fall back to the old purely-local support rules in that case.
 */
public abstract class FogEyeAnchorBlockEntity extends BlockEntity {
    @Nullable
    private BlockPos root;

    protected FogEyeAnchorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Nullable
    public BlockPos getRoot() {
        return root;
    }

    public void setRoot(BlockPos root) {
        this.root = root;
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("rootX")) {
            root = new BlockPos(tag.getInt("rootX"), tag.getInt("rootY"), tag.getInt("rootZ"));
        } else {
            root = null;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (root != null) {
            tag.putInt("rootX", root.getX());
            tag.putInt("rootY", root.getY());
            tag.putInt("rootZ", root.getZ());
        }
    }
}
