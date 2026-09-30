package com.fogged;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import com.mojang.serialization.MapCodec;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The datapacks the mod ships inside itself, offered in the pack list like any other.
 *
 * <p>Five, and between them everything the murk used to turn over from the {@code transforms} config
 * list, which is now empty: <b>Grass Scourch</b> (every grassed block back to the bare ground under
 * it), <b>Moss Scourch</b> (moss blocks, prismoss, mossy stone and brick with their cuts), <b>Copper
 * Oxidation</b> (every unwaxed copper block weathered the whole way through), <b>Silverfish
 * Suffocation</b> (infested blocks back to the plain stone the silverfish were hiding in) and <b>Coal
 * Remover</b> (coal ore back to its own rock, blocks of coal taken outright).
 *
 * <p>Coal Remover shows what a pack of these can do beyond recipes: it also adds the blocks of coal to
 * {@code #fogged:scoured}, so turning the pack off leaves coal alone entirely rather than half of it.
 *
 * <p>Each is a set, answered yes or no: either the murk strips the grass off what it covers or it does
 * not. As config lines that was a dozen entries to delete one at a time to say no; as a pack it is one
 * switch. It is also the mod eating its own cooking -- they are the same
 * {@code fogged:murk_transform} recipes any other mod would write (see {@link MurkTransformRecipe}).
 *
 * <p>The files live at {@code datapacks/<name>} in the mod jar.
 */
@EventBusSubscriber(modid = Fogged.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class FoggedDatapacks {
    private FoggedDatapacks() {}

    // The condition each pack's recipes carry, so that the switch in [datapacks] and the pack list
    // itself say the same thing (see PackEnabledCondition).
    private static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS =
            DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, Fogged.MODID);

    static {
        CONDITIONS.register("pack_enabled", () -> PackEnabledCondition.CODEC);
    }

    public static void register(IEventBus modEventBus) {
        CONDITIONS.register(modEventBus);
    }

    private static final String[] PACKS = {
            "grass_scourch", "moss_scourch", "copper_oxidation", "silverfish_suffocation", "coal_remover",
    };

    @SubscribeEvent
    static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        for (String name : PACKS) {
            // Not alwaysActive: the point of moving these out of the config was to leave somewhere to
            // turn them off. A world that has never seen one takes it up the way it takes up any new
            // pack; one where it has been switched off keeps it off.
            event.addPackFinders(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "datapacks/" + name),
                    PackType.SERVER_DATA, Component.translatable("fogged.datapack." + name),
                    PackSource.BUILT_IN, false, Pack.Position.TOP);
        }
    }
}
