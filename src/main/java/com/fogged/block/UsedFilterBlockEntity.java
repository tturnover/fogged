package com.fogged.block;

import com.fogged.Config;
import com.fogged.FilterPollution;
import com.fogged.PlaneSensor;
import com.fogged.registry.ModBlockEntities;
import com.fogged.registry.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A polluted filter set down: the block it is ({@code Original}) and the murk it holds ({@code Murk}),
 * in its own NBT, so they go wherever the block goes -- onto a Sable ship as it is assembled, into a
 * Create contraption and back out. It sheds the murk out of the murk as a filter in an intake does
 * ({@link Config#FILTER_CLEAN_RATE}), and once clean turns back into the plain block.
 */
public class UsedFilterBlockEntity extends BlockEntity {

    private BlockState original; // null until set or loaded: then the block's own original stands in
    // Ticks until the clients are told again. A player's own placement is predicted on their client,
    // which drops this block's data as it comes (its predicted block has no block entity) and then
    // takes the block itself when the server confirms the click -- with an empty block entity. So the
    // data is sent once more after that. How dirty it looks needs none of this: that is in the block's
    // state (UsedFilterBlock#POLLUTION), which comes with the block.
    private int resync;
    private int murk;

    public UsedFilterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.USED_FILTER.get(), pos, state);
    }

    /** Hold {@code murk} blocks of murk as the filter {@code original}. */
    public void init(BlockState original, int murk) {
        this.original = original;
        this.murk = murk;
        setChanged();
        sync();
        resync = 2;
    }

    /** The block this filter is. */
    public BlockState original() {
        if (original != null) {
            return original;
        }
        // Not told yet: the block this used filter stands in for, if it is one block's own.
        Block own = getBlockState().getBlock() instanceof UsedFilterBlock used ? used.originalBlock() : null;
        return own != null ? own.defaultBlockState() : Blocks.WHITE_WOOL.defaultBlockState();
    }

    /** How worn it looks, 0 (clean) to 1 (clogged), by the steps its state shows. */
    public float wear() {
        return UsedFilterBlock.wear(getBlockState());
    }

    /** The step {@code murk} blocks of murk come to in the filter {@code original}, rounded up. */
    public static int stepOf(BlockState original, int murk) {
        float wear = Mth.clamp((float) murk / FilterPollution.capacityOf(original.getBlock().asItem()), 0.0F, 1.0F);
        return Math.min(ModDataComponents.MAX_POLLUTION, (int) Math.ceil(wear * ModDataComponents.MAX_POLLUTION - 1.0E-6));
    }

    /** The filter as an item, as polluted as it is: what it breaks into and what pick-block gives. */
    public ItemStack asItem() {
        ItemStack stack = new ItemStack(original().getBlock().asItem());
        int step = step();
        if (step > 0 && !stack.isEmpty()) {
            stack.set(ModDataComponents.POLLUTION.get(), step);
        }
        return stack;
    }

    /** The pollution step it breaks back out at, rounded up: setting it down never cleans it. */
    public int step() {
        return stepOf(original(), murk);
    }

    /** Once a second: out of the murk, shed some; clean, or no filter any more, back to the plain block. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, UsedFilterBlockEntity be) {
        if (be.resync > 0 && --be.resync == 0) {
            be.sync();
        }
        if (level.getGameTime() % 20 != Math.floorMod(pos.asLong(), 20)) {
            return;
        }
        if (be.murk <= 0 || Config.filterCapacity(new ItemStack(be.original().getBlock().asItem())) < 0) {
            level.setBlock(pos, be.original(), 3);
            return;
        }
        int shed = (int) Math.round(Config.FILTER_CLEAN_RATE.get());
        if (shed <= 0 || Config.fogged(level, PlaneSensor.worldY(level, pos))) {
            return;
        }
        be.murk -= shed;
        if (be.murk <= 0) {
            level.setBlock(pos, be.original(), 3);
            return;
        }
        be.setChanged();
        // A step cleaner: the state says so, and the clients shade it so with the block.
        int step = be.step();
        if (state.hasProperty(UsedFilterBlock.POLLUTION) && state.getValue(UsedFilterBlock.POLLUTION) != step) {
            level.setBlock(pos, state.setValue(UsedFilterBlock.POLLUTION, step), 2);
        }
    }

    // Tell the clients what it is.
    private void sync() {
        Level level = getLevel();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Original", NbtUtils.writeBlockState(original()));
        tag.putInt("Murk", murk);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Original")) {
            original = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("Original"));
        }
        murk = tag.getInt("Murk");
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
