# Diet — личный Android-дневник

Kotlin + Jetpack Compose + Room, Android10+/API29, compile/target37. Создана Android-реализация принятого прототипаv0.4: офлайн без регистрации, рекламы и INTERNET permission. Готов подписанный minified APK1.0.0: D:/Diet/artifacts/Diet-1.0.0.apk. Установка и обновление на выделенных Android10/17 прошли; физические S23+/Android14/OEM ещё не испытаны. [Файл, SHA256, установка и пределы проверки](docs/release-1.0.0.md).

Питание, свои продукты/рецепты и 60 проверенных стартовых продуктов; ручные цели по датам; вес/сон; календарь/история/поиск; фото/заметки/настроение и фотолента; шаблоны/план/быстрые порции; настраиваемая главная, оформление и игровые уровни; сворачиваемые отчёты/графики и мягкие напоминания. Копии/восстановление исключены пользователем, аккаунт/синхронизация/расчётные рекомендации и ML отложены.

## Запуск и проверка

1. Скопировать D:/Diet/artifacts/Diet-1.0.0.apk на телефон и открыть в файловом менеджере; [пошаговая установка](docs/release-1.0.0.md#установка-на-телефон), [процесс выпуска](docs/release-process.md). Бинарники/приватный ключ находятся в игнорируемых artifacts/.local-tools; исходники наGitHub сами по себе не являются установленным APK.
2. Среда: python scripts/bootstrap_tools.py → scripts/install-sdk.ps1 → scripts/build.ps1 :app:assembleDebug. Первый запуск загрузок требует сети; само приложение работает без неё. Точные версии/контрольные суммы — [baseline](docs/android-build-baseline.md).
3. JVM/Lint: scripts/build.ps1 :app:testDebugUnitTest :app:lintDebug :domain-checks:test.
4. На выделенном AVD: scripts/build.ps1 :app:assembleDebugAndroidTest, затем scripts/test-android.ps1 -Serial emulator-5582 -ServerPort 15037 -GrantNotifications. Указать свой явно выбранный serial/port; runtime grant возвращается после runner. Скрипт не делает uninstall/clear.
5. Minified release/signature/16KiB: [release-process](docs/release-process.md). Не терять локальный ключ обновлений; не коммитить его или реальные записи.

Наблюдено:48 уникальных JVM cases PASS; API29 полный 80 +повтор 24UI PASS; API37 полный 81 на 4KiB/16KiB PASS. Gainmap/MPF fixture наAPI37 действительно исполнилась. Lint0errors; подпись/упаковка конечного R8 APK и install-r поверх заполненного дневника на29/37 PASS. Точный файл/source/SHA — в [выпуске1.0.0](docs/release-1.0.0.md). [Полный отчёт и пределы](docs/build-validation.md) не объявляют все 168 приёмочных случаев/физическийp95/OEM пройденными.

## Документы

| Документ | Назначение |
|---|---|
| [Выпуск1.0.0](docs/release-1.0.0.md) | APK, SHA256, установка и фактические проверки |
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
