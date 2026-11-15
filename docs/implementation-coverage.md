# Diet — соответствие Android-реализации требованиям

Дата: 15 ноября 2026 года. База карты — native1.0 и run13/run17; дополнение1.1 и его фактические проверки перечислены ниже. Это навигация по **25 активным FR и 8 NFR**, а не новая редакция ТЗ и не отметка о прохождении всего каталога приёмки.

Требования остаются в [product-spec.md](product-spec.md), ожидаемые случаи — в [acceptance-tests.md](acceptance-tests.md), точные правила — в [data-rules.md](data-rules.md). Результаты запусков и ограничения окружений ведутся в [build-validation.md](build-validation.md); эта карта не заменяет их первичные отчёты.

## Как читать статусы

- **Частично проверено:** код реализован, есть названные исполняемые проверки; они подтверждают указанные свойства, а не все случаи соответствующего FR/NFR.
- **Реализовано, отдельная проверка открыта:** маршрут и сохранение есть в исходниках, но отдельный полный сценарий из каталога не подтверждён имеющимися результатами.
- Ни один статус ниже не означает прохождение всего каталога приёмки, физической матрицы или готовность любого OEM-устройства.

Сборка13 прошла compile/JVM/lint. Полный run13 на API29: **80 тестов PASS за 73,733 с**, без ошибок и прежнего native SIGSEGV: Repository22, ReportQueries5, ReportScale1, PreferencesRecovery4, остальные integration6, PhotoEngine11, WebP3, PhotoColor2, ReminderScheduler2, DietUi24. Gainmap-тест имеет `SdkSuppress(minSdkVersion=34)` и в эти80 **не входит**. Дополнительно выполнены оба API37 run17 по81case без ошибок/пропусков, включая synthetic Gainmap, и повтор24UI финальной15наAPI29. Подписанный minified release отдельно прошёл офлайн первый запуск и same-key update на29/37: профиль/питание/измерения, а на37 также цели/медиа/план/шаблон/dark/raw-черновик сохранены. [Пакет1.0.0 и sourceSHA этого исторического среза](release-1.0.0.md). Физические проверки открыты; успех debug не переносится на них автоматически.

## Дополнение UX 1.1

[Принятые требования](ux-v1.1.md) дополняют исходные25FR; [manifest 1.1.0](release-1.1.0.md) и [проверки UX](ux-validation-1.1.md) фиксируют конкретные версии/ограничения.

| Изменение | Реализация | Наблюдённая проверка |
|---|---|---|
| Окна и возврат | UiDialogs, UiViewport, UiHost | Navigation4, font2/320dp и низкое окно; отдельный native IME |
| Добавление и действия еды | CatalogTopBar, FoodEntryActions, repository batch/move/repeat | FoodWorkflow8 + FoodUi2, атомарность/снимки/ID |
| Отмена удаления | PendingDeletion и photo intent после grace | Deletion4, native photo Undo + холодный Viewer |
| Минималистичный отчёт | ReportScreens, ReportPresentation, ReportQueries | JVM2, ReportSegments3, ReportsUi2, S2 |
| Виджет | widget/** и platform RemoteViews | JVM4, Integration2, настоящий launcher pin/кнопки/live update |
| Анимации и знакомство | DietDesign, GuidanceScreen | Общий UI прогон, независимый source review; физический TalkBack/OEM открыт |

JVM54 и полный API37/16 КиБ106 PASS; повтор UI36 после основного исправления IME PASS. Финальный compactIME не приписывается предыдущему набору — он проверен нативным вводом отдельно. Подписанное обновление, точные SHA и сохранность описаны в manifest, не выводятся из наличия UI-тестов.

## Функциональные требования

В столбце «Проверка» названы существующие тестовые классы из [указателя ниже](#указатель-проверок). Названия AC/ON/FD/DY/WT/SL/GM/PG/CV/AN — ссылки на ожидаемое поведение в каталоге, а не автоматически проставленные PASS.

| ID / область | Реализация | Проверка и статус | Что ещё не подтверждено полностью |
|---|---|---|---|
| FR-001 — локальный первый запуск | [DiaryScreens][diary], [UiHost][host], [Room][repo]: welcome/profile/goals, пропуск, сохранённый шаг и raw draft | **Частично проверено.** DietUi: первый запуск без целей → порция → вес/сон/отчёт; Repository: идемпотентный init/reopen/receipt | Полный AC-001/ON/AN-001 с авиарежимом, force stop и физическим reboot |
| FR-003 — необязательный профиль | [Формы][editor], [команды][host], `saveProfile` / `saveOnboardingProfile` в [Room][repo]; профиль хранится отдельно от измерений | **Частично проверено.** Numbers: необязательные поля/границы; DietUi: сохранение профиля первого запуска | Все ON-008/AN-001: правки каждого поля и сохранность независимых веса/целей после restart |
| FR-005 — ручные КБЖУ и отсутствие цели | [Формы][editor], [главная][diary], `saveGoal` в [Room][repo], [Numbers][numbers] | **Частично проверено.** Numbers: калории цели >0, БЖУ ≥0; DietUi: безцелевая главная, нулевой макронутриент, отложенная цель | Полный ON-001–006: все отмены/ошибки, системные прерывания |
| FR-006 — фактическая еда | [Каталог][library], [формы][editor], [команды][host]; `addFood`, `addQuickFood`, `editFood`, отдельный `replaceFoodSnapshot`, `deleteFood` в [Room][repo] | **Частично проверено.** Nutrition: порции; Repository: история/явная замена/revision/rollback; DietUi:80 г →120 ккал, правка/удаление старой строки вне текущего окна | Полный AC-002/003 и FD: все источники/приёмы/даты/ошибки удаления через UI |
| FR-007 — продукты, рецепты, категории | [LibraryScreens][library], [EditorScreen][editor], [SettingsScreens][settings]; продукт/рецепт/категория CRUD в [Room][repo], снимки в [Entities][entities], [Nutrition][nutrition]; проверенный [starter][starter] | **Частично проверено.** Nutrition: г/мл/порции, выход рецепта/дроби; Repository: снимки; StarterCatalogIntegration: настоящий asset60, происхождение, edit/delete/reopen | Полный UI CRUD рецепта/категории, запрет вложения и каждый ошибочный состав по FD/CV-022 |
| FR-008 — избранное, недавние, быстрый ввод, копирование | [CatalogScreen][library], формы quick/copy в [EditorScreen][editor], `toggleFavorite`, `copyMeal`, recents в [Room][repo] | **Частично проверено.** DietUi: быстрая запись/валидность формы; Repository: receipt и независимые снимки; Nutrition: быстрый ввод без выдуманной массы | Полный FD-006–010/CV-023: копирование с изменённым источником, фильтры после удаления/переименования |
| FR-009 — главная выбранной даты | [HomeScreen][diary]:7 блоков, точные суточные итоги, страницы приёмов, вес/сон/моменты/уровень | **Частично проверено.** DietUi: основной маршрут, цель/превышение, скрытие блоков; Repository: история вне первых40 | Полная визуальная и доступная главная с архивными приёмами/IME/всеми сочетаниями данных на телефонах |
| FR-010 — одна запись веса на дату | [Форма веса][editor], `saveWeight` / `deleteWeight` в [Room][repo], целые сотые в [Numbers][numbers] | **Частично проверено.** Numbers/Reports: точность, границы, отдельный N; Repository: revision; DietUi: сохранение веса | Полный WT-001–007: отмены/ошибки удаления, часы/зона и restart на устройстве |
| FR-011 — суммарный сон | [Форма сна][editor], `saveSleep` / `deleteSleep` в [Room][repo], минуты/подтверждение0 в [Numbers][numbers] | **Частично проверено.** Numbers/Reports: целые минуты, подтверждённый0/пропуск/округление; DietUi:7 ч30 мин →450 минут | Полный SL-001–009: исправление/удаление и прерывания выбранной даты через UI |
| FR-012 — отчёты и графики | [ReportScreens][reports-ui], [ReportQueries][queries], [Reports][reports], [StreamingReports][streaming]: N/Q, сравнение, ≤600 интервалов и точные страницы дат | **Частично проверено.** Reports/ReportQueries/ReportScale: независимые выборки, snapshot, границы и100000 фактов; DietUi:4 нутриента/частичные точки | Полный PG-001–014 на физических экранах, TalkBack, большие значения и все переходы к записи |
| FR-013 — полнота и подтверждённый ноль | [DayCompletionControl][diary], `completeDay` / удаление последней строки в [Room][repo], [Reports][reports] | **Частично проверено.** Repository: partial/explicit zero/снятие полноты; Reports: пропуски/N; DietUi: явное подтверждение при скрытых блоках | Все DY-переходы вместе с реальным process kill/ошибками хранилища |
| FR-014 — история целей | [GoalsScreen][diary], формы/preview в [EditorScreen][editor], timeline revision и date-effective цели в [Room][repo] | **Частично проверено.** Repository/Reports: цепочка по датам; DietUi: занятая дата, устаревший preview, полночь и отложенная onboarding-цель | Полная CV-025 цепочка отмены/удаления/перезапуска и физическая смена часов |
| FR-015 — мягкие игровые элементы | [GameRules][game], [главная][diary], независимые переключатели в [SettingsScreens][settings] / [Preferences][prefs] | **Частично проверено.** GameRules: все пороги имён/уровней, отсутствие имени при0/пропуске; Repository/Reports: число заполненных дат | Все GM UX-состояния, отключение/возврат и reduced motion на выбранных телефонах |
| FR-017 — русский UI, темы/шрифт, приёмы | [DietDesign][design], [SettingsScreens][settings], [Preferences][prefs]; `saveMeal` / `reorderMeals`, стабильные ID в [Room][repo], [NameKeys][names] | **Частично проверено.** DietUi: крупный текст/достижимость; NameKeys: Unicode15.1/регистр; Preferences: сохранение групп | Полная CV-021 приёмов; все темы/акценты/fontScale/IME/TalkBack/поворот по AN-005 |
| FR-018 — календарь | [CalendarScreen][diary]: понедельник, месяцы/точная дата, отдельные отметки еды/веса/сна/моментов из [ReportQueries][queries] | **Реализовано, отдельная проверка открыта.** ReportQueries подтверждает исходные выборки; отдельного полного CV-001 теста нет | Все сочетания маркеров/переходы месяцев/доступность календаря |
| FR-019 — настройка7 блоков | [PreferenceEditor][settings], [HomeScreen][diary], независимый home-draft в [Preferences][prefs] | **Частично проверено.** DietUi: скрытие всех блоков сохраняет полноту/подтверждение0; Preferences: изоляция/replay/черновики | Все перестановки/отмена/reset/restart по CV-002/003 |
| FR-020 — шаблоны | [RoutinesScreen/TemplatePicker][library], [формы][editor]; `saveTemplate`, `applyTemplate`, `deleteTemplate` в [Room][repo] | **Реализовано, отдельная проверка открыта.** Состав/снимки/атомарность используют проверенные доменные и транзакционные механизмы; это не отдельный полный CV-004/005 PASS | Весь цикл из приёма/каталога → правка источника/имени/количества → применение/повтор/удаление |
| FR-021 — планы и перенос | [RoutinesScreen][library], формы plan/transfer, `savePlan` / `transferPlan` и receipts в [Room][repo] | **Частично проверено.** Repository: намеренный повтор против retry, сохранность факта/переносов после удаления плана; DietUi: предупреждение повторного переноса | Полный день/7 дат/+90, все ошибки/отмены CV-006–008 |
| FR-022 — личные моменты и локальные фото | [Форма moment][editor], [AppMediaActions][media-actions], [PhotoEngine][photo], [MediaMetadataRepository][metadata]; заметка/настроение/подписи/refs | **Частично проверено.** PhotoEngine11/WebP3/PhotoColor2, DraftMediaRecovery2; DietUi: default meal/удаление staged и восстановление намерения | Реальные camera/HDR/providers/grants, disk full/OOM, kill каждой файловой фазы и физический reboot |
| FR-023 — фотолента и просмотр | [HistoryScreens][history], paged `queryPhotos` / `loadPhoto` в [Room][repo], viewer/lease в [PhotoEngine][photo] | **Частично проверено.** Metadata: устойчивые IDs/подписи; PhotoEngine: lease/GC/master без источника; DietUi: удаление выбранного фото не подменяет его одинаковым | Все фильтры/архивные приёмы/возврат после restart; лента1000 фото на физическом устройстве |
| FR-024 — поиск фактической еды | [HistoryScreens][history], `queryHistory` / `loadEntry` / `dayComplete` в [Room][repo], сохранение контекста в [UiPreferences][ui-prefs] | **Частично проверено.** Repository: старые даты/историческое имя; DietUi: редактор строки вне текущего окна и её удаление | Полный CV-013/014: все фильтры, мгновенный клик после ввода, возврат и ошибки периода |
| FR-025 — быстрые количества | [EditorScreen][editor], [UiHost][host], `saveQuickPortions` / `resetQuickPortions` в [Room][repo]: группа ID+база+единица,5 значений | **Реализовано, отдельная проверка открыта.** Numbers/Nutrition проверяют числовой фундамент; это не отдельный полный CV-015/016/024 PASS | Все дубли/микроколичества/смены единицы, отмены/reset и отсутствие факта до Save |
| FR-026 —3 акцента/6 обложек | [DietDesign][design], [PreferenceEditor][settings], [Preferences][prefs]: preview/отмена/сохранение, локальные ресурсы | **Частично проверено.** Preferences: appearance изоляция/revision/token/replay; UI-код preview и возврата | Вся визуальная матрица6 обложек ×3 акцента ×2 темы, отмена/restart/скрытие обложки |
| FR-027 — компактный отчёт | [ReportsScreen][reports-ui]: сводка,6 закрытых по умолчанию секций, открыть/закрыть всё; period/open state в [UiPreferences][ui-prefs] | **Частично проверено.** DietUi: пустой отчёт, раскрытие, график и переключение нутриентов; вычисления — ReportQueries | Полный CV-018: все секции, возврат/restart, клавиатура и TalkBack |
| FR-028 — реальные напоминания | [SettingsScreens][settings], [ReminderScheduler][scheduler], [ReminderReceiver][receiver], [ReminderTime][time], Room outbox/occurrence | **Частично проверено.** ReminderTime6: DST/зона/даты; ReminderScheduler2: framework alarm/posting/отмена/повторы; DietUi: доступность каждого канала/переход к существующей записи | Полный цикл API33+ revoke/возврат; manual deny/Allow/один заблокированный канал API37 пройдены. Реальное ожидание окна, Doze/cold reboot/OEM. `POSTED` не означает доставку или прочтение |

## Нефункциональные требования

| ID | Реализация и доказательство | Статус и граница |
|---|---|---|
| NFR-001 — долговечность/обновления | [Room][database] / [Entities][entities], [Preferences][prefs], raw draft/receipt, durable media; Repository: reopen и запрет разрушения future schema; PreferencesRecovery: ошибка чтения/retry/неизвестный draft | **Частично проверено.** Повторное открытие и future-format guards не заменяют AN-001–004: force stop/reboot всех видов данных и миграцию между реально выпущенными схемами. Сейчас схемаv1; поддерживаемой пары v1→v2 нет |
| NFR-002 — неизменная история | Собственные [снимки][entities] у еды/состава, explicit replace, revision/timeline checks в [Room][repo]; Repository/StarterCatalogIntegration/ReportQueries | **Частично проверено.** Подтверждены edit/delete источника, явная замена одного факта, перенос и единый reader snapshot; полный UI-каталог копирования/шаблонов/планов ещё не пройден |
| NFR-003 — точность/ошибки/выборки | [Rational][rational], [Numbers][numbers], [Nutrition][nutrition], [Reports][reports], [StreamingReports][streaming], [NameKeys][names]; JVM48, ReportQueries5 и ReportScale1 в соответствующих запусках | **Частично проверено.** Точные дроби/округление/пределы/Unicode/N/Q/cancellation и100000 фактов проверены; не каждый числовой случай каталога прогнан через каждую Android-форму |
| NFR-004 — доступность | [DietDesign][design], семантика/подписи/текстовая таблица графика в [ReportScreens][reports-ui], скролл/IME в [DietApp][app-ui]; DietUi24 включает крупный текст и достижимость основных действий | **Частично проверено.** AN-005/PG-013/GM-012 на физических экранах, TalkBack, системный fontScale2.0, reduced motion и полная матрица цветов/поворота открыты |
| NFR-005 — отсутствие рекламы | Пользовательские маршруты без рекламных блоков; прямые зависимости [app/build.gradle.kts][gradle] не содержат рекламного SDK; [manifest][manifest] без INTERNET | **Частично проверено.** Статическая проверка состава текущей сборки не заменяет повторный аудит зависимостей/готового релиза при изменениях |
| NFR-006 — приватность/исключение системного копирования | [CE noBackup Room][database], [Preferences][prefs], [PhotoEngine][photo], [manifest][manifest] и compiled backup XML; StoragePrivacyIntegration2; local transport ответил для пакета `Backup is not allowed` | **Частично проверено.** Подтверждены реальные пути/WAL/SHM/FK/FULL и manifest/XML; cloud/D2D/OEM, физические устройства и полный аудит релизных логов не подтверждены. Исключение системного копирования не добавляет функцию копий |
| NFR-007 — атомарность/повтор | [Room coordinator][repo]: writer/receipt-first; [UiDrafts][ui-drafts] / [UiPreferences][ui-prefs]: замороженная команда; [media lifecycle][metadata] / [PhotoEngine][photo] | **Частично проверено.** Repository: rollback/conflict/replay/known commit при failed refresh; PreferencesRecovery/DietUi: потерянное подтверждение/неизвестный draft; PhotoEngine: batch/cancel/fsync/GC. Все реальные process-kill/IO/OOM точки AN-003 ещё не выполнены |
| NFR-008 — объём/скорость/память | Scoped/paged queries в [Room][repo], keyset250/LRU256/≤600 buckets в [ReportQueries][queries], paged UI/metadata; [PhotoBudget][budget] и единая native очередь [PhotoEngine][photo] | **Частично проверено.** S2 food-only100000/3653 и точность PASS; native PNG4096×1024 уменьшается до допустимого профиля. Это не полный S2 с1000 фото/каталогом/планами, не p95/3с на Samsung и не гарантия отсутствия OOM. StateFlow ещё читает полные списки целей/быстрых порций; их масштаб не измерен. Полные [TECH-009/S0–S3](remaining-stage-zero.md) и физические замеры открыты |

## Отложенное и исключённое

Отсутствие этих функций не является дырой реализации действующей локальной версии:

| Решение ТЗ | Статус |
|---|---|
| FR-002 аккаунт и синхронизация | Отложены; аккаунт не нужен для локальных маршрутов |
| FR-004 расчётные рекомендации/формулы целей, автоматическая согласованность КБЖУ | Отложены; присутствует только ручная цель. Арифметика порций/отчётов не является рекомендацией |
| FR-016 резервные копии/экспорт/импорт/восстановление/пароль | Исключены пользователем; номер не используется повторно. Системные исключения backup/privacy проверяются отдельно |
| OCR/ML, штрихкоды и внешние интеграции | Отложены; фото не распознаёт еду и не рассчитывает её КБЖУ |
| Отдельная камера, интервалы сна, тренировки, замеры тела, streak, социальные/магазинные функции, iOS | Вне согласованного объёма; см. полный список в ТЗ |
| «Полный пример/Сначала», фиксированная дата и кнопки имитации сбоя HTML-макета | Не функции пользовательского Android-приложения. Тестовые даты/изображения находятся в тестовых фикстурах |

## Указатель проверок

| Набор | Где смотреть | Какое свойство он доказывает |
|---|---|---|
| JVM domain/media/time | [domain](../app/src/test/java/app/diet/domain/), [PhotoBudgetTest](../app/src/test/java/app/diet/media/PhotoBudgetTest.kt), [ReminderTimeTest](../app/src/test/java/app/diet/timing/ReminderTimeTest.kt) | Точная арифметика/нормализация/границы/фолды, расчётный бюджет и правила времени; не поведение OEM |
| Repository / ReportQueries | [RepositoryTest](../app/src/androidTest/java/app/diet/data/RepositoryTest.kt), [ReportQueriesTest](../app/src/androidTest/java/app/diet/data/ReportQueriesTest.kt) | Реальные Room/BundledSQLite/DataStore, транзакции/replay/reopen, независимые выборки и reader snapshot |
| Integration | [tests/integration](../app/src/androidTest/java/app/diet/integration/) | Starter60, приватные пути/manifest/XML, восстановление refs и Preferences, S2 food-only. Метаданные crash-fixture сами по себе не доказывают JPEG decode |
| Photo | [PhotoEngineTest](../app/src/androidTest/java/app/diet/media/PhotoEngineTest.kt), [WebpPhotoEngineTest](../app/src/androidTest/java/app/diet/media/WebpPhotoEngineTest.kt), [PhotoColorIntegrationTest](../app/src/androidTest/java/app/diet/media/PhotoColorIntegrationTest.kt) | Native synthetic JPEG/PNG/WebP/EXIF/F16/P3/downsample, fail/cancel/GC; synthetic Gainmap/MPF действительно исполнен на API37/4KiB и16KiB (run17), реальная камера/HDR-дисплей не проверены |
| UI | [DietUiTest](../app/src/androidTest/java/app/diet/ui/DietUiTest.kt) | Compose + реальные изолированные хранилища, выбранные действия/ошибки/гонки.24 PASS не означают весь AN-010/CV-каталог |
| Notifications | [ReminderSchedulerTest](../app/src/androidTest/java/app/diet/notifications/ReminderSchedulerTest.kt) | Настоящие framework API с синтетическими спецификациями; posting/ledger не доказывают ожидание ОС или физическую доставку |

Следующий результат добавляется только после исполнения и с указанием окружения в [build-validation.md](build-validation.md). При изменении кода требуется проверить затронутые строки этой карты; старые успешные прогоны не доказывают неизменённое поведение новой сборки.

[diary]: ../app/src/main/java/app/diet/ui/DiaryScreens.kt
[editor]: ../app/src/main/java/app/diet/ui/EditorScreen.kt
[host]: ../app/src/main/java/app/diet/ui/UiHost.kt
[library]: ../app/src/main/java/app/diet/ui/LibraryScreens.kt
[settings]: ../app/src/main/java/app/diet/ui/SettingsScreens.kt
[reports-ui]: ../app/src/main/java/app/diet/ui/ReportScreens.kt
[history]: ../app/src/main/java/app/diet/ui/HistoryScreens.kt
[design]: ../app/src/main/java/app/diet/ui/DietDesign.kt
[app-ui]: ../app/src/main/java/app/diet/ui/DietApp.kt
[ui-drafts]: ../app/src/main/java/app/diet/ui/UiDrafts.kt
[ui-prefs]: ../app/src/main/java/app/diet/ui/UiPreferences.kt
[repo]: ../app/src/main/java/app/diet/data/DietRepository.kt
[database]: ../app/src/main/java/app/diet/data/DietDatabase.kt
[entities]: ../app/src/main/java/app/diet/data/Entities.kt
[prefs]: ../app/src/main/java/app/diet/data/VisualPreferencesRepository.kt
[queries]: ../app/src/main/java/app/diet/data/ReportQueries.kt
[metadata]: ../app/src/main/java/app/diet/data/MediaMetadataRepository.kt
[starter]: ../app/src/main/java/app/diet/catalog/StarterCatalog.kt
[numbers]: ../app/src/main/java/app/diet/domain/Numbers.kt
[nutrition]: ../app/src/main/java/app/diet/domain/Nutrition.kt
[reports]: ../app/src/main/java/app/diet/domain/Reports.kt
[streaming]: ../app/src/main/java/app/diet/domain/StreamingReports.kt
[game]: ../app/src/main/java/app/diet/domain/GameRules.kt
[names]: ../app/src/main/java/app/diet/domain/NameKeys.kt
[rational]: ../app/src/main/java/app/diet/domain/Rational.kt
[photo]: ../app/src/main/java/app/diet/media/PhotoEngine.kt
[budget]: ../app/src/main/java/app/diet/media/PhotoBudget.kt
[media-actions]: ../app/src/main/java/app/diet/AppMediaActions.kt
[scheduler]: ../app/src/main/java/app/diet/notifications/ReminderScheduler.kt
[receiver]: ../app/src/main/java/app/diet/notifications/ReminderReceiver.kt
[time]: ../app/src/main/java/app/diet/timing/ReminderTime.kt
[gradle]: ../app/build.gradle.kts
[manifest]: ../app/src/main/AndroidManifest.xml
