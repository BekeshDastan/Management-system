# Diet — личный Android-дневник

Kotlin + Jetpack Compose + Room, Android 10+/API29, compile/target37. Подписанный APK **1.1.0**: D:/Diet/artifacts/Diet-1.1.0.apk. Работает офлайн без регистрации, рекламы и INTERNET permission. [Файл, установка поверх старой версии и результаты проверки](docs/release-1.1.0.md). Физические S23+/Android14/OEM остаются отдельной приёмкой.

Питание, свои продукты/рецепты и 60 проверенных стартовых продуктов; ручные цели по датам; вес/сон; календарь/история/поиск; фото/заметки/настроение и фотолента; шаблоны/план/быстрые порции; настраиваемая главная, оформление и игровые уровни; сворачиваемые отчёты/графики и мягкие напоминания. Копии/восстановление исключены пользователем, аккаунт/синхронизация/расчётные рекомендации и ML отложены.

## Запуск и проверка

1. Скопировать D:/Diet/artifacts/Diet-1.1.0.apk на телефон и открыть в файловом менеджере; [пошаговая установка](docs/release-1.1.0.md#установка-на-телефон), [процесс выпуска](docs/release-process.md). Бинарники/приватный ключ находятся в игнорируемых artifacts/.local-tools; исходники наGitHub сами по себе не являются установленным APK.
2. Среда: python scripts/bootstrap_tools.py → scripts/install-sdk.ps1 → scripts/build.ps1 :app:assembleDebug. Первый запуск загрузок требует сети; само приложение работает без неё. Точные версии/контрольные суммы — [baseline](docs/android-build-baseline.md).
3. JVM/Lint: scripts/build.ps1 :app:testDebugUnitTest :app:lintDebug :domain-checks:test.
4. На выделенном AVD: scripts/build.ps1 :app:assembleDebugAndroidTest, затем scripts/test-android.ps1 -Serial emulator-5582 -ServerPort 15037 -GrantNotifications. Указать свой явно выбранный serial/port; runtime grant возвращается после runner. Скрипт не делает uninstall/clear.
5. Minified release/signature/16KiB: [release-process](docs/release-process.md). Не терять локальный ключ обновлений; не коммитить его или реальные записи.

Проверено: JVM54 PASS; полный Android-прогон API37/16 КиБ — 106 PASS; после обработки IME повторены 36 UI, PASS. Финальные R8/Lint/подпись/упаковка прошли. Native update и сохранность отдельно описаны в [выпуске 1.1.0](docs/release-1.1.0.md), ранние сбои и исправления — в [проверках UX](docs/ux-validation-1.1.md). Полная физическая матрица и все168 приёмочных случаев не объявляются закрытыми.

В 1.1 реализованы [улучшения UX](docs/ux-v1.1.md): адаптивные окна, сохранение прокрутки, закреплённое создание еды, выбор нескольких продуктов, отмена удаления, быстрый повтор/перенос, отчёт со столбиками, анимации, виджет и необязательное знакомство.

## Документы

| Документ | Назначение |
|---|---|
| [Выпуск1.1.0](docs/release-1.1.0.md), [проверки UX](docs/ux-validation-1.1.md) | Текущий APK, SHA256, обновление и фактические проверки |
| [Выпуск1.0.0](docs/release-1.0.0.md) | Предыдущий выпуск и исторические результаты |
| [ТЗv1.0](docs/product-spec.md) | Принятый объём 25FR/8NFR |
| [План разработки](docs/development-plan.md) |17 этапов и ворота приёмки |
| [Реализация/покрытие](docs/implementation-coverage.md) | Код→тесты→оставшиеся проверки |
| [Native UI](docs/android-ui.md) | Маршруты, формы, оформление и иллюстрации |
| [Фактическая проверка](docs/build-validation.md) | JVM/Android/packaging и ограничения результатов |
| [Правила данных](docs/data-rules.md), [числа](docs/numeric-contract.md) | Снимки, точность, N/Q, даты/единицы |
| [Хранение](docs/storage-model.md), [черновики](docs/drafts.md), [миграции](docs/migrations.md) | Room/DataStore и сохранность |
| [Обработка фото](docs/photo-processing.md), [файлы](docs/media-storage.md), [исключение копий](docs/system-backup-policy.md) | Бюджеты, Ready/recovery/GC и граница OEM |
| [Starter60](docs/starter-catalog.md) | USDA/CC0, source IDs/240values/checksums |
| [Baseline](docs/android-build-baseline.md), [dependencies](docs/android-dependencies.md), [стек](docs/stack-proposal.md) | Точные выбранные/исполненные инструменты |
| [Матрица](docs/android-test-matrix.md), [устройства](docs/device-profile.md) | Проверенные и неизвестные окружения |
| [TECH](docs/technical-decisions.md), [решения](docs/decisions.md) | Основания/статусы |
| [Сценарии](docs/user-flows.md), [приёмка 168](docs/acceptance-tests.md) | Ожидаемые результаты |
| [Прототипv0.4](docs/prototype/README.md), [его покрытие](docs/prototype/coverage.md), [его проверки](docs/prototype/validation.md) | Исторический визуальный ориентир, не Android runtime |
| [Журнал](docs/work-log.md), [handoff](docs/handoff.md), [AGENTS](AGENTS.md) | История публикаций и текущая работа |

Исторический HTML: открыть docs/prototype/index.html вместе с assets/JS/CSS. Его 51 условный продукт/симуляции не являются пищевой базой; Android содержит 60 проверенных исходных USDAпозиций и реальное локальное хранение. Принятый prototype commit:e60e10e3f41205fac0a6a7f6ddcb18ed21c7ebad; изображения в нём не менялись при native переносе.
