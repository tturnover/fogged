package com.fogged.data;

import java.util.Map;

import com.fogged.Fogged;
import com.fogged.registry.ModBlocks;
import com.fogged.registry.ModItems;

import net.minecraft.data.PackOutput;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.common.data.LanguageProvider;

/**
 * Ukrainian ({@code uk_ua}) counterpart of {@link ModLanguageProvider}: same keys, translated strings.
 * Block and item names are looked up in {@link #NAMES} by registry path, so a block added without a
 * Ukrainian name still gets an entry -- it just falls back to its English one until translated.
 *
 * <p>Death lines are phrased so their verb agrees with "туман" rather than with the player, keeping them
 * correct whoever died (Ukrainian past-tense verbs are gendered).
 */
public class ModLanguageProviderUk extends LanguageProvider {

    private static final Map<String, String> NAMES = Map.of(
            "fog_detector", "Детектор туману",
            "fog_detector_extension", "Секція детектора туману",
            "nozzle_filter", "Фільтр туману");

    public ModLanguageProviderUk(PackOutput output) {
        super(output, Fogged.MODID, "uk_ua");
    }

    @Override
    protected void addTranslations() {
        addConfigTranslations();

        add("fogged.tooltip.nozzle.desc1", "Застосуйте _вовняний блок_, щоб відфільтрувати _густий туман_.");
        add("fogged.tooltip.nozzle.desc2", "Швидкість _вентилятора в корпусі_ задає _зону дихання_.");
        add("fogged.tooltip.fog_detector.desc1",
                "Видає _редстоуновий сигнал_ залежно від глибини під туманом.");
        add("fogged.tooltip.fog_detector.desc2", "ПКМ _іншим таким же_, щоб зробити колону вищою.");

        add("death.attack.fog_suffocation", "Гравця %1$s поглинув туман");
        add("death.attack.fog_suffocation.player", "Гравця %1$s поглинув туман під час втечі від %2$s");

        ModBlocks.BLOCKS.getEntries()
                .forEach(holder -> add(holder.get(), name(holder.getId().getPath())));

        ModItems.ITEMS.getEntries().forEach(holder -> {
            if (holder.get() instanceof BlockItem) {
                return; // shares the block's translation key
            }
            add(holder.get(), name(holder.getId().getPath()));
        });
    }

    private void addConfigTranslations() {
        add("itemGroup.fogged", "Fogged");
        add("fogged.configuration.title", "Налаштування Fogged");
        add("fogged.configuration.section.fogged.common.toml", "Налаштування Fogged");
        add("fogged.configuration.section.fogged.common.toml.title", "Налаштування Fogged");

        add("fogged.configuration.planeHeightSchedule", "Розклад висоти межі");
        add("fogged.configuration.planeHeightCycle", "Циклічний розклад висоти");
        add("fogged.configuration.overdayOffset", "Добове зміщення висоти");
        add("fogged.configuration.fogDistance", "Дальність туману під площиною");
        add("fogged.configuration.flipFog", "Перевернути бік туману");
        add("fogged.configuration.submergeWorld", "Затоплення світу");
        add("fogged.configuration.submergeSkip", "Мертва зона під площиною");
        add("fogged.configuration.snuffedDevices", "Пристрої, які гасить туман");

        add("fogged.configuration.playerSuffocation", "Задуха гравців");
        add("fogged.configuration.airLossPerTick", "Втрата повітря за тік");
        add("fogged.configuration.depthScaling", "Посилення з глибиною");
        add("fogged.configuration.depthScalingStep", "Крок посилення [глибина, відсоток]");
        add("fogged.configuration.mobSuffocation", "Задуха мобів");
        add("fogged.configuration.allowedMobs", "Моби, дозволені під туманом");
        add("fogged.configuration.mobSuffocateDelaySeconds", "Затримка задухи мобів (секунди)");
        add("fogged.configuration.mobSuffocateDamage", "Шкода від задухи мобів");

        add("fogged.configuration.renderPlane", "Показувати розділову площину");
        add("fogged.configuration.planeColor", "Колір площини (hex RGBA)");
        add("fogged.configuration.foamColor", "Колір піни (hex RGBA)");
        add("fogged.configuration.foamWidth", "Ширина піни");
        add("fogged.configuration.sableFoam", "Піна на підрівнях Sable");
        add("fogged.configuration.foamDebug", "Налагоджувальний вигляд піни");
        add("fogged.configuration.renderVapor", "Показувати шар холодних випарів");
        add("fogged.configuration.vaporColorOffset", "Зміщення кольору випарів");
        add("fogged.configuration.vaporStrength", "Насиченість випарів");
        add("fogged.configuration.vaporSheets", "Кількість шарів випарів");
        add("fogged.configuration.vaporUndulation", "Хвилястість випарів");
    }

    private static String name(String path) {
        return NAMES.getOrDefault(path, ModLanguageProvider.name(path));
    }
}
