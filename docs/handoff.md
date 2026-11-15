# Diet — Android-реализация и установочный кандидат

Обновлено 15 ноября 2026 года. Пользователь разрешил полностью реализовать приложение и обоснованные улучшения, требует документировать и публиковать небольшими отдельными коммитами. Старый запрет на код снят. Общаться по-русски; читать [AGENTS](../AGENTS.md). Backups/восстановление исключены; аккаунт, рекомендации и ML отложены.

## Результат и доказательства

Создан функциональный объём принятого v0.4: Kotlin/Compose/Room + DataStore, min29/compile37/target37. [ТЗ](product-spec.md) задаёт объём; [coverage](implementation-coverage.md) сопоставляет25FR/8NFR с кодом и пробелами; [validation](build-validation.md) содержит только наблюдённые результаты. HTML-прототип на e60e10e3f41205fac0a6a7f6ddcb18ed21c7ebad сохранён.

- 48 уникальных JVM cases: domain38/time6/media4, PASS.
- API29: полный80/73.733с и повтор UI24/run18/44.382с (предыдущийrun15/47.655с такжеPASS), PASS.
- API37/4KiB: полный81/141.638с; API37/16KiB: полный81/111.131с, PASS без пропусков. Getconf подтвердил16384.
- JPEG/EXIF/PNG/WebP/P3-F16/downsample/Gainmap+MPF проверены синтетическими native fixtures. Физическая камера/HDR-дисплей этим не проверены.
- Manual API37: первый запуск, еда, вес/сон, цели, шаблоны/планы, графики, picker/фото/изменение/удаление, permission/channel,320dp/font2/light-dark прошли. Дополнительно signedR8 офлайн29/37 и обновление с сохранением профиля/фактов/целей/медиа/плана/шаблона/dark/raw72, PASS. Это не весь каталог168cases и не физическийp95/Doze/OEM/cloud/D2D.

Каждая небольшая часть опубликована отдельным коммитом codex/android-implementation с проверкой remote SHA; завершённые исходники и release-документация интегрированы fast-forward в main до9cf99d39444e94d76296da1fa1a568c467beec41; remoteSHA совпал, история не переписана. Конечный app source 08a91ab94af5a461f736d9cba12b4fee3f565294 встроен в VCS metadata APK. [Выпуск1.0.0](release-1.0.0.md) фиксирует конечный файл/SHA/install/update. SignedR8 проверен офлайн на29/37, обновление поверх заполненной версии сохранило данные; старые unsigned09/b62/aa0 не являются конечным APK. Текущий GitHEAD/remote сверять Git, не переносить sourceSHA на последующие документальные коммиты.

## Сборка и файлы

D:/Diet. Локальная среда: Temurin21.0.13+9, Gradle9.5, AGP9.3.1, Kotlin2.4.21, KSP2.3.14; SDK37.0/BuildTools36.0.0; Emulator37.2.13. Скрипты в scripts/, SDK/cache/AVD/ключи в ignored .local-tools. AndroidStudio/глобальная Java не обязательны. Официальный native Android CLI сохраняет package IDs с точкой с запятой.

Маршруты ведёт UiHost/BackHandler; подключённые Nav3/ViewModel не выдавать за реально использованные API. [Подпись/команды](release-process.md): постоянный сертификат SHA256 ba726a7b3396655b56a2329827bc5de3874a12d37198ba8ac64134f783d43220. Private keystore/password только .local-tools/signing, не терять и не коммитить. Готовый конечный файл artifacts/Diet-1.0.0.apk, SHA256 46efbd7705f08d9429cde032a879d266a3c0948783c62299e864d7cb673a6b63; сырой APK/ключ/реальную историю в Git не добавлять.

## Важные границы реализации

- Exact p/q, HALF_UP при показе (ккал1/вес2), Unicode15.1. Изменение каталога не обновляет исторические snapshots.
- Receipt-first, frozen operation, serialized outcome proof; unknown Save не считать rollback. Цели по датам, missing/partial/confirmed-zero различаются.
- DataStore сериализует Map, observer имеет retry. Unknown independent draft изолирован с сохранением bytes; whole future file не перезаписывается.
- Photo master durable до Ready refs; all-or-none batch, leases/GC, URI не персистятся. Удаление: raw intent до detach; stale callback не возвращает фото. Декоративный cover не становится личным снимком.
- Report keyset по существующему индексу и LRU256 вместо повторного OFFSET. NULL quick не превращается в0; deadline10с не расширялся. Эмуляторные7.6–10с не являются S23 p95.
- Room default DirectBoot service удалён; оба XML исключают9domains каждого транспорта. API29 local transport отказал backup; физический/OEM gate открыт.
- Test teardown ждёт cancelAndJoin до db.close: устранён небезопасный read/close порядок. Production DB живёт весь процесс. Native SIGSEGV12 в SQLite releaseMemArray после исправления не повторился в полных прогонах.
- Espresso3.7 нужен API37. Runtime notification grant/restore выполняет внешний test-android.ps1 -GrantNotifications; revoke внутри @After убивает тестовый UID штатно.
- UI защищает полноту при скрытии всех home cards, старую запись по target/revision, замену snapshot, цели/midnight/deferred, повтор плана, picker41+, фото remove/recovery и возврат в ленту. Финальные palette/date layout проверены.

## Тестовая среда и открытая приёмка

Только owned AVD, ADBserver15037: DietApi29/emulator-5580, DietApi37/emulator-5582, DietApi37_16k/emulator-5584. Не выбирать default5037/USB/личный телефон. Перед stop/uninstall проверять emu avd name; тестовые записи синтетические. При R8 освобождать тяжёлые AVD; Start-Process Hidden. Raw logs/tombstones/screenshots остаются в .local-tools. По завершении API29/37 QA AVD закрыты после проверки имён; файлы/синтетические записи сохранены, физических телефонов не касались.

Пользователь выбрал первую установку открытием APK. Его сведения: S23+,8GB,Android17,около250GB свободно; OneUI неизвестна. У близких самый старый названный Android14, модель неизвестна. Их установка/очистка автоматически не выполнялась.

Остались физические/OEM/cloud/D2D, p95/frames/leak и полная предметная приёмка по168cases. [Матрица](android-test-matrix.md)/coverage сохраняют pending. Не стирать данные ради успешного теста переноса и не объявлять все ворота закрытыми по одному APK.
