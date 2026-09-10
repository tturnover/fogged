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

        // Категорія JEI для перетворень блоків каламуттю (див. FoggedJeiPlugin).
        add("fogged.jei.murk_transform", "Перетворення каламуттю");

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
        cfg("fogStartRaise", "Зміщення початку каламуті",
                "Де починається каламуть відносно площини: вище, 0 — на ній, або нижче.");
        cfg("flipFog", "Перевернути бік туману",
                "Розмістити каламуть над межею замість під нею. Саму межу дихання не змінює.");
        cfg("enableWorldChanges", "Змінювати світ",
                "Головний вимикач усього, що каламуть робить із блоками й предметами під межею: гасить "
                        + "вогонь, забирає речі, перетворює їх на інші. Вимкнено — світ лишається таким, "
                        + "яким його збудували.");
        cfg("worldChangeSkip", "Недоторканий шар",
                "Скільки блоків одразу під межею каламуть не чіпає.");
        add("fogged.configuration.group.murkActions", "Що робить каламуть");
        add("fogged.configuration.boundary.tooltip", "Де проходить межа, як каламуть поводиться на ній і що вона робить зі світом під нею. Спільне для сервера й клієнтів: це ігролад, а не вигляд.");
        add("fogged.configuration.suffocation.tooltip", "Що каламуть робить із тими, хто в ній дихає -- гравцями й мобами -- і наскільки гірше це стає з глибиною.");
        add("fogged.configuration.plane.tooltip", "Видима поверхня каламуті: її колір, піна вздовж лінії води й те, як вона зустрічає блоки, що її перетинають. Лише ваше; сервер цього не диктує.");
        add("fogged.configuration.vapor.tooltip", "Шар холодних випарів: шари імли над поверхнею, розкладені шумом на тераси, щоб площина не виглядала пласкою.");
        add("fogged.configuration.debug.tooltip", "Способи побачити, що робить рендер: сирі вигляди буферів за каламуттю, показ її стану й запис проблем сумісності в лог.");
        add("fogged.configuration.group.murkActions.tooltip", "Три вимикачі над усім, що каламуть робить із блоками й предметами; кожен керує однойменним списком нижче.");
        cfg("enableExtinguish", "Гасіння вогню",
                "Каламуть гасить вогонь під межею: вогонь зникає, лава застигає, смолоскипи падають, "
                        + "а пристрої нижче згасають і втрачають паливо.");
        cfg("snuffedDevices", "Пристрої, які гасить туман",
                "Пристрої з вогнем, які каламуть гасить: кожен згасає, його таймер горіння обнуляється, "
                        + "а паливо викидається, щоб він не працював під туманом.\n"
                        + "По одному ідентифікатору блока на запис; '*' відповідає будь-якому набору "
                        + "символів, 'minecraft:' можна не писати. Запис для відсутнього моду просто "
                        + "ігнорується.\n"
                        + "furnace\n"
                        + "create:lit_blaze_burner\n"
                        + "simulated:*_portable_engine");
        cfg("enableScour", "Знищення блоків",
                "Каламуть прибирає речі під межею: усе зі списку нижче, без випадіння, і ріллю, яка "
                        + "стає землею й втрачає посаджене.");
        cfg("scouredBlocks", "Блоки, які знищує туман",
                "Додаткові блоки, які каламуть ламає під межею, поверх вбудованого очищення. Ламає без "
                        + "випадіння.\n"
                        + "По одному ідентифікатору блока на запис; '*' відповідає будь-якому набору "
                        + "символів, а '#' на початку означає тег. 'minecraft:' можна не писати.\n"
                        + "cobweb\n"
                        + "#minecraft:banners\n"
                        + "create:*_casing");
        cfg("enableTransforms", "Увімкнути перетворення",
                "Головний вимикач списку перетворень. Вимкнено — каламуть нічого ні на що не змінює, а "
                        + "JEI перестає їх показувати; сам список зберігається.");
        cfg("transforms", "Перетворення каламуті",
                "На що каламуть перетворює речі під межею -- і покладені блоки, і кинуті стопки, з "
                        + "одного списку.\n"
                        + "Синтаксис: [кількість] з = [кількість] на [@глибина] [!silent]\n"
                        + "з: ідентифікатор блока чи предмета, ідентифікатор із '*', або '#' тег. "
                        + "'minecraft:' можна не писати.\n"
                        + "на: один ідентифікатор. БЛОК замінює покладений блок і зберігає спільні "
                        + "властивості; ПРЕДМЕТ ламає його й випадає замість нього.\n"
                        + "кількість: скільки віддає кинута стопка і скільки отримує навзамін. Від 1 до "
                        + "64. Кількості стосуються стопок: запис із кількістю не чіпає покладені блоки, "
                        + "хіба що це число предметів, які випадуть зі зламаного блока.\n"
                        + "@глибина: лише на стільки блоків нижче поверхні. Рухається разом із межею. "
                        + "Від 0 до 512.\n"
                        + "!silent: не показувати в JEI. Усе інше показане там.\n"
                        + "coal_ore=stone !silent\n"
                        + "moss_block=coarse_dirt @50\n"
                        + "4 diamond=2 dirt\n"
                        + "#minecraft:leaves=1 stick !silent\n"
                        + "create:*_casing=mud @20");
        cfg("itemTransforms", "Перетворення предметів",
                "Заміни \"з=на\" для кинутих предметів, за ідентифікатором. Усі показані в JEI.");
        cfg("playerSuffocation", "Задуха гравців",
                "Під межею гравці тонуть: повітря спадає, далі йде шкода від утоплення.");
        cfg("airLossPerTick", "Втрата повітря за тік",
                "Скільки повітря гравець втрачає за тік із 300. Більше = швидше тоне.");
        cfg("depthScaling", "Посилення з глибиною",
                "Чим глибше, тим сильніше кусає каламуть: повітря спадає швидше, сфери дихання меншають.");
        cfg("depthScalingBlocks", "Крок глибини",
                "Скільки блоків під межею складають один крок посилення. 0 вимикає його.");
        cfg("depthScalingPercent", "Відсоток на крок",
                "За крок: втрата повітря зростає на цей відсоток, радіус сфери фільтра спадає. Кроки множаться.");
        cfg("mobSuffocation", "Задуха мобів",
                "Недозволені моби не з'являються в каламуті й отримують шкоду, якщо затримуються там.");
        cfg("mobSpawnsInBreathingSpheres", "Моби з'являються у сферах дихання",
                "Сфери фільтра-сопла звільнені від заборони появи: моби з'являються в них, як і над межею.");
        cfg("mobEscape", "Моби тікають із каламуті",
                "Моби, що задихаються, біжать до найближчої сфери дихання або лізуть угору до межі.");
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
        cfg("planeSoftOcclusion", "М'який край (експериментально)",
                "Розчиняти площину біля блоків, мобів і механізмів замість різкого зрізу на межі та "
                        + "відкривати м'який отвір навколо кожної істоти, що її перетинає. "
                        + "Експериментально й типово вимкнено: читає буфер глибини сцени, який інші "
                        + "мод-рендерери можуть замінити. Вимкніть це першим, якщо каламуть виглядає "
                        + "неправильно.");
        cfg("waterlineCellsPerBlock", "Роздільність сітки піни",
                "Комірок на блок у полі відстаней піни. Менше = грубіша піна й помітно дешевше.");
        cfg("vaporColorOffsetRed", "Червоне зміщення випарів",
                "Червоний, доданий до кольору площини, щоб отримати колір випарів.");
        cfg("vaporColorOffsetGreen", "Зелене зміщення випарів",
                "Зелений, доданий до кольору площини, щоб отримати колір випарів.");
        cfg("vaporColorOffsetBlue", "Синє зміщення випарів",
                "Синій, доданий до кольору площини, щоб отримати колір випарів.");
        cfg("vaporStrength", "Насиченість випарів",
                "Загальна прозорість туману. 0 повністю ховає випари.");
        cfg("vaporSheets", "Кількість шарів випарів",
                "Скільки шарів туману складено над площиною.");
        cfg("vaporUndulation", "Хвилястість випарів",
                "Наскільки високо (у блоках) шари туману піднімаються над площиною. 0 = пласко.");
        cfg("vaporUnderside", "Імла під площиною",
                "Дзеркалити шари імли й під поверхнею, щоб шар виглядав однаково з обох боків.");
        cfg("vaporUnderwater", "Імла під водою",
                "Малювати імлу і перед проходом води, щоб озеро вкривало її, а не вона лежала зверху.");
        cfg("debugView", "Налагоджувальний вигляд",
                "Замінити площину сирим виглядом одного з буферів, що її формують.");
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
