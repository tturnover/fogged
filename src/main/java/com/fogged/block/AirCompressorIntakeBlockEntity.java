package com.fogged.block;

import com.fogged.registry.ModBlockEntities;
import com.fogged.registry.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
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

    /**
     * The intake as an inventory of one slot, the filter: a Mechanical Arm, hopper, funnel or chute puts
     * filter media in as a hand would ({@link AirCompressorIntakeBlock#fit}). Nothing comes out -- a
     * hopper under it must not pull a fresh filter back out; a hand takes it out, and a spent one falls
     * apart.
     */
    private final net.neoforged.neoforge.items.IItemHandler inventory = new net.neoforged.neoforge.items.IItemHandler() {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 0 ? filter : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty() || !isItemValid(slot, stack) || !filter.isEmpty()
                    || level == null || level.isClientSide
                    || getBlockState().getValue(AirCompressorIntakeBlock.FILTERED)) {
                return stack;
            }
            if (!simulate && !AirCompressorIntakeBlock.fit(level, worldPosition, stack)) {
                return stack;
            }
            return stack.copyWithCount(stack.getCount() - 1);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && com.fogged.Config.filterCapacity(stack) >= 0;
        }
    };

    // What the intake sends its compressor: told every tick by the compressor drawing on it; not told for
    // a second, it is not connected to one. Synced as it changes, for the goggles.
    private long feedTick = Long.MIN_VALUE;
    private double feedFlow;
    private boolean connected;
    private int syncedFeed = -1;

    /** Its compressor drawing on it at {@code gameTime}: {@code flow} blocks/s of clean air, maybe none. */
    void feeding(long gameTime, double flow) {
        feedTick = gameTime;
        feedFlow = flow;
        int now = (int) Math.round(flow * 10);
        if (!connected || now != syncedFeed) {
            connected = true;
            syncedFeed = now;
            changed();
        }
    }

    // Once a tick, server side: drawn on by no compressor for a second, it is on its own.
    void checkFeed(long gameTime) {
        if (connected && gameTime - feedTick > 20) {
            connected = false;
            feedFlow = 0.0;
            syncedFeed = -1;
            changed();
        }
    }

    /** Whether a compressor draws on it. Either side. */
    public boolean connected() {
        return connected;
    }

    /** Blocks/s of clean air it sends its compressor. Either side. */
    public double feedFlow() {
        return feedFlow;
    }

    /** The intake's inventory, for the item-handler capability; any side. */
    public net.neoforged.neoforge.items.IItemHandler inventory() {
        return inventory;
    }

    /** The filter fitted, or empty. */
    public ItemStack filter() {
        return filter;
    }

    /** Fit {@code stack} as the filter, as polluted as the stack says ({@link ModDataComponents#POLLUTION}). */
    void setFilter(ItemStack stack) {
        int steps = stack.getOrDefault(ModDataComponents.POLLUTION.get(), 0);
        filter = stack.copy();
        filter.remove(ModDataComponents.POLLUTION.get());
        wear = (double) steps / ModDataComponents.MAX_POLLUTION * Math.max(0, capacity());
        changed();
    }

    /**
     * Take the filter out: returns it, leaving none, carrying how polluted it is -- rounded up to a whole
     * step, so it stacks with others as dirty.
     */
    public ItemStack takeFilter() {
        ItemStack out = filter;
        int steps = (int) Math.ceil(worn() * ModDataComponents.MAX_POLLUTION - 1.0E-6);
        if (!out.isEmpty() && steps > 0) {
            out.set(ModDataComponents.POLLUTION.get(), Math.min(ModDataComponents.MAX_POLLUTION, steps));
        }
        filter = ItemStack.EMPTY;
        wear = 0.0;
        changed();
        return out;
    }

    /** The fitted filter's capacity, in blocks of murk (Config#filterCapacity): 0 never clogs. */
    public int capacity() {
        return Math.max(0, com.fogged.Config.filterCapacity(filter));
    }

    /** Pollute the filter by {@code blocks} of murk cleaned out of the air; negative cleans it. */
    void wear(double blocks) {
        int capacity = capacity();
        if (capacity <= 0) {
            return;
        }
        int signal = AirCompressorIntakeBlock.signal(level, worldPosition);
        wear = Mth.clamp(wear + blocks, 0.0, capacity);
        setChanged();
        if (level != null && AirCompressorIntakeBlock.signal(level, worldPosition) != signal) {
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock()); // comparators
        }
        int percent = (int) Math.min(100, wear * 100 / capacity);
        if (percent != syncedPercent) {
            syncedPercent = percent;
            changed(); // the goggles and the renderer read it
        }
    }

    /**
     * The filter's pollution in the intake's steps ({@link AirCompressorIntakeBlock#POLLUTION}): 0 clean
     * up to {@link AirCompressorIntakeBlock#CLOGGED}, reached only when it is wholly spent.
     */
    int pollutionStep() {
        double worn = worn();
        if (worn >= 1.0) {
            return AirCompressorIntakeBlock.CLOGGED;
        }
        return Math.min(AirCompressorIntakeBlock.CLOGGED - 1, (int) (worn * AirCompressorIntakeBlock.CLOGGED));
    }

    /** How polluted the filter is, 0..1. Either side. */
    public double worn() {
        int capacity = capacity();
        return capacity <= 0 ? 0.0 : Math.min(1.0, wear / capacity);
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
        if (tag.contains("Connected")) { // only in what is sent to the client
            connected = tag.getBoolean("Connected");
            feedFlow = tag.getDouble("Feed");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        tag.putBoolean("Connected", connected);
        tag.putDouble("Feed", feedFlow);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
