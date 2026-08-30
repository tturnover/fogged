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
 * A block with no {@link #NAMES} entry falls back to its English name rather than going missing.
 *
 * <p>The death lines put their verb on "туман" rather than on the player, so they stay correct whoever
 * died -- Ukrainian past-tense verbs are gendered.
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
        add("fogged.configuration.section.fogged.common.toml", "Ігролад Fogged");
        add("fogged.configuration.section.fogged.common.toml.title", "Ігролад Fogged");
        add("fogged.configuration.section.fogged.client.toml", "Графіка Fogged");
        add("fogged.configuration.section.fogged.client.toml.title", "Графіка Fogged");
        add("fogged.configuration.boundary", "Межа");
        add("fogged.configuration.suffocation", "Задуха");
        add("fogged.configuration.plane", "Розділова площина");
        add("fogged.configuration.vapor", "Холодні випари");
        add("fogged.configuration.debug", "Налагодження");
        cfg("planeHeightSchedule", "Розклад висоти межі",
                "Один запис \"день=висота\" на рядок; межа плавно переходить між ними з плином днів.");
        cfg("planeHeightCycle", "Циклічний розклад висоти",
                "Повторювати розклад туди й назад замість того, щоб назавжди тримати останню висоту.");
        cfg("overdayOffsetNoon", "Добове зміщення опівдні",
                "Висота, додана до розкладу опівдні -- нижня точка добового коливання.");
        cfg("overdayOffsetMidnight", "Добове зміщення опівночі",
                "Висота, додана до розкладу опівночі -- верхня точка добового коливання.");
        cfg("fogDistance", "Дальність туману під площиною",
                "Наскільки далеко видно (у блоках) під межею. Менше = густіша каламуть.");
        cfg("flipFog", "Перевернути бік туману",
                "Розмістити каламуть над межею замість під нею. Саму межу дихання не змінює.");
        cfg("submergeWorld", "Затоплення світу",
                "Каламуть топить усе під собою: гасить вогонь, застигає лаву, топить смолоскипи, в'ялить рослини.");
        cfg("submergeSkip", "Мертва зона під площиною",
                "Скільки блоків одразу під площиною залишаються недоторканими.");
        cfg("snuffedDevices", "Пристрої, які гасить туман",
                "Ідентифікатори блоків, які каламуть гасить. '*' відповідає будь-якому набору символів.");
        cfg("playerSuffocation", "Задуха гравців",
                "Під межею гравці тонуть: повітря спадає, далі йде шкода від утоплення.");
        cfg("airLossPerTick", "Втрата повітря за тік",
                "Скільки повітря гравець втрачає за тік із 300. Більше = швидше тоне.");
        cfg("depthScaling", "Посилення з глибиною",
                "Чим глибше, тим сильніше кусає каламуть.");
        cfg("depthScalingBlocks", "Крок глибини",
                "Скільки блоків під межею складають один крок посилення. 0 вимикає його.");
        cfg("depthScalingPercent", "Відсоток на крок",
                "За крок: втрата повітря зростає на цей відсоток, радіус сфери фільтра спадає. Кроки множаться.");
        cfg("mobSuffocation", "Задуха мобів",
                "Недозволені моби не з'являються в каламуті й отримують шкоду, якщо затримуються там.");
        cfg("allowedMobs", "Моби, дозволені під туманом",
                "Ідентифікатори істот, яким можна жити в каламуті. Простір імен 'minecraft:' можна не писати.");
        cfg("mobSuffocateDelaySeconds", "Затримка задухи мобів",
                "Скільки секунд недозволений моб витримує в каламуті до першої шкоди.");
        cfg("mobSuffocateDamage", "Шкода від задухи мобів",
                "Шкода щосекунди після затримки. 2.0 = одне серце.");
        cfg("renderPlane", "Показувати розділову площину",
                "Малювати поверхню каламуті на межі дихання.");
        cfg("planeColor", "Колір площини",
                "Колір поверхні каламуті. Альфа ігнорується -- площина завжди непрозора.");
        cfg("foamColor", "Колір піни",
                "Колір піни навколо всього, що перетинає поверхню. Альфа задає її помітність.");
        cfg("foamWidth", "Ширина піни",
                "Наскільки далеко (у блоках) сягає піна від кожного краю. 0 вимикає її.");
        cfg("sableFoam", "Піна на підрівнях Sable",
                "Обводити піною кораблі та контрапції Sable. Без Sable не діє.");
        cfg("planeSoftOcclusion", "М'який край",
                "Розчиняти площину біля блоків, мобів і механізмів замість різкого зрізу на межі.");
        cfg("distantHorizonsCompat", "Підтримка Distant Horizons",
                "Розширити каламуть до обрію LOD і керувати туманом DH. Вимкнено \u2014 мод повністю ігнорує Distant Horizons.");
        cfg("distantHorizonsLodCut", "Обрізати далеку каламуть по LOD",
                "З Distant Horizons далекий рельєф LOD піднімається крізь каламуть, замість того щоб бути нею вкритим.");
        cfg("waterlineCellsPerBlock", "Роздільність сітки піни",
                "Комірок на блок у полі відстаней піни. Менше = грубіша піна й помітно дешевше.");
        cfg("renderVapor", "Показувати шар холодних випарів",
                "Шари туману над площиною, для вигляду рідкого азоту.");
        cfg("vaporColorOffsetRed", "Червоне зміщення випарів",
                "Червоний, доданий до кольору площини, щоб отримати колір випарів.");
        cfg("vaporColorOffsetGreen", "Зелене зміщення випарів",
                "Зелений, доданий до кольору площини, щоб отримати колір випарів.");
        cfg("vaporColorOffsetBlue", "Синє зміщення випарів",
                "Синій, доданий до кольору площини, щоб отримати колір випарів.");
        cfg("vaporStage", "Стадія рендера випарів",
                "На якій стадії рендера малюються шари туману, незалежно від каламуті.");
        cfg("vaporUnderwater", "Випари під водою",
                "Малювати другу копію туману до проходу води, щоб він читався як шар під поверхнею озера.");
        cfg("vaporUnderwaterStage", "Стадія випарів під водою",
                "На якій стадії малюється та друга копія. Має бути до прозорого проходу води.");
        cfg("vaporStrength", "Насиченість випарів",
                "Загальна прозорість туману. 0 повністю ховає випари.");
        cfg("vaporSheets", "Кількість шарів випарів",
                "Скільки шарів туману складено над площиною.");
        cfg("vaporUndulation", "Хвилястість випарів",
                "Наскільки високо (у блоках) шари туману піднімаються над площиною. 0 = пласко.");
        cfg("debugView", "Налагоджувальний вигляд",
                "Замінити площину сирим виглядом одного з буферів, що її формують.");
        cfg("planeStage", "Стадія рендера площини",
                "На якій стадії рендера накладається каламуть \u2014 тобто що вже є в буферах кольору та глибини на той момент.");
        cfg("debugHud", "Налагоджувальний надпис",
                "Малювати стан рендера (межа, згасання, знімок глибини, кількість отворів) у кутку.");
        cfg("logRenderCompatWarnings", "Логувати попередження сумісності",
                "Один раз записати в лог, коли мод не може працювати з поточним налаштуванням рендера.");
    }

    /** One config option: its display name and the tooltip both screens show under it. */
    private void cfg(String key, String name, String tooltip) {
        add("fogged.configuration." + key, name);
        add("fogged.configuration." + key + ".tooltip", tooltip);
    }

    private static String name(String path) {
        return NAMES.getOrDefault(path, ModLanguageProvider.name(path));
    }
}
