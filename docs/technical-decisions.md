# Diet — технические решения и состояние проверки

Дата проверки источников и актуализации: 15 ноября 2026 года. **Android-реализация разрешена пользователем и создана.** Kotlin/Compose/Room и minSdk29 выбраны отдельно от принятия прототипа; точная конфигурация выбрана инженерно. Выполнены сборка/KSP, JVM48, Lint без ошибок, debug/R8 unsigned, API29 run13:80 PASS и UI run15:24 PASS. Compile16/17 с обновлённым тестовым Espresso прошли; официальный API37.0 ps16k/x86_64 run17:81 PASS, обычный API37/4 KiB run17:81 PASS за141,638с. Точная история — [build-validation.md](build-validation.md), функции — [implementation-coverage.md](implementation-coverage.md), публикация — [work-log.md](work-log.md). Продуктовые инварианты остаются в data-rules.md; наличие кода не закрывает физические/OEM-проверки.

## 1. Реестр решений

| ID | Что зафиксировать | Когда закрыть | Проверяемый выход |
|---|---|---|---|
| TECH-001 | Целевой физический телефон/Android, минимальная поддержка, min/target/compile SDK, локальная установка и матрица тестов | SDK/план матрицы до проекта; физические поля/способ установки до испытаний | 29/37/37 и матрица выбраны; способ первой установки выбран — открытие APK-файла на телефоне; остаются модель близких и остальные физические поля. Непроверенные устройства не объявляются совместимыми |
| TECH-002 | Версии Kotlin/Compose/Room, toolchain/зависимости и организация UI/данных | Сборочная часть исполнена; повторять при изменениях, полный runtime по матрице | Граф/KSP/JVM/Lint/debug/R8 проверены, version catalog/Wrapper/verification metadata сохранены. Статический ABI/16 KB контроль и API37 ps16k/x86_64 run17 выполнены; обычный API37/4 KiB также прошёл81 тест; физическая и полная предметная матрица остаются открытыми |
| TECH-003 | Точность/диапазоны/даты/объёмы/Unicode по [numeric-contract](numeric-contract.md) | Контракт и ядро реализованы; полнота приёмки по coverage | micro INTEGER, точные p/q BigInteger, HALF_UP, пределы/Unicode15.1 реализованы; JVM и реальные Room/Compose проверки PASS. Это не весь NC/FD/WT/SL/PG-каталог на всех устройствах |
| TECH-004 | [Модель](storage-model.md), [миграции](migrations.md), [черновики](drafts.md), атомарность/идемпотентность/конфликты | Room v1/основные транзакции исполнены; update/kill matrix остаётся | Entities/schema v1, WAL/FK/FULL, checkpoint/receipt, timeline/ordinal и DataStore проверены на API29. Future schema отклоняется без разрушения; миграция между двумя выпущенными схемами пока неприменима — существует толькоv1 |
| TECH-005 | [Выбор/обработка/память](photo-processing.md), [файлы/владение/recovery/GC](media-storage.md) | Реализовано и частично проверено; PH/providers/physical продолжаются | API29 JPEG/EXIF/PNG/WebP/F16/P3/downsample/fault cases PASS; N16-37 дополнительно подтвердил synthetic Gainmap/MPF → свежий SDR master, без assumption skip. Реальные camera HDR/providers/OOM/disk-full и все kill-фазы ещё не подтверждены |
| TECH-006 | [Исключение системного cloud/D2D](system-backup-policy.md) | Пути/manifest/XML проверены; реальные cloud/D2D/OEM — открытый допуск | Actual CE noBackup inventory, allowBackup=false, все9 domains legacy/cloud/D2D проверены; local transport отклонил backup пакета. Это не доказательство cloud/D2D/OEM. UI восстановления отсутствует |
| TECH-007 | [Мягкие локальные напоминания](remaining-stage-zero.md): AlarmManager/setWindow, generation/outbox/claim | Реализовано; framework posting проверен, доставка/Doze/OEM открыты | JVM DST/зона и API29 AlarmManager/NotificationManager/replay PASS. Три вида, окно15мин без exact permission; POSTED не означает доставку, claim и OS post не атомарны |
| TECH-008 | [Starter/изображения](remaining-stage-zero.md): USDA SR Legacy April2018/CC0 и отдельный реестр иллюстраций | Asset60 создан/проверен; повторять аудит при изменении контента |60 FDC IDs с четырьмя значениями per100г и provenance; integration проверяет реальный asset, edit/delete/reopen и changed manifest без воскрешения. Данные не лицензируют чужие фото |
| TECH-009 | [Цели качества/S0–S3](remaining-stage-zero.md), фото-бюджеты TECH-005 | Первые измерения выполнены; полные S0–S3/p95/physical открыты | S2 food-only100000/3653 и bounded report PASS на эмуляторе; это не полный S2 с1000 фото/каталогом/планами и не3с/p95 на Samsung. История не ограничивается размером теста |

В TECH-001/002 выбор стека и SDK повторного согласования не требует. Пользователь отдельно разрешил реализацию15 ноября; открытые пункты теперь описывают конкретные непроверенные окружения/свойства, а не запрет кода. Объём не расширяется молча; полный каталог приёмки не объявляется пройденным по одному набору тестов.

## 2. Проверенные платформенные основания

### Локальные данные и миграции

Обновление должно сохранять пользовательские данные. Для выбранного Room 2 миграции проверяются на версиях схемы; разрушительное пересоздание таблиц не подходит требованию сохранности. Это вывод для Diet из поведения, описанного в [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions).

### Восстановление экрана

Малое состояние UI и постоянные записи — разные задачи. Для выбранного Compose сохраняемые состояния экрана подходят для небольшого контекста/ID, а данные и фото живут в отдельном слое. Выбор механизма и тест process death — TECH-004; [официальное описание сохранения UI](https://developer.android.com/develop/ui/compose/state-saving) не заменяет постоянную БД.

### Выбор фото

Системный Photo Picker даёт доступ к выбранным медиа без чтения всей галереи; разрешение на ресурс само по себе не равно собственной надёжной копии. В Diet реализованы проверенное чтение, document-fallback и собственный master по TECH-005; реальные providers остаются отдельной проверкой. Основание: [Photo Picker и сохранение доступа](https://developer.android.com/training/data-storage/shared/photo-picker). Камера и OCR не включены.

### Отсутствие функции копирования и Android Auto Backup

Исключение UI резервных копий не отключает системное резервирование автоматически. Android описывает отдельные правила для облака и device-to-device; поведение одного allowBackup может зависеть от производителя. Для Diet требуется явная проверка исключений, а не обещание контроля над любым OEM. Основание: [Auto Backup](https://developer.android.com/identity/data/autobackup). Это техническое выполнение D-003, не возврат функции копирования.

### Напоминания и разрешения

На Android 13+ обычные уведомления зависят от runtime-разрешения; отказ не должен блокировать дневник. Сохранённый переключатель не доказывает доставку. Основание: [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission). TECH-007 реализован через неточный AlarmManager/setWindow; exact alarms не используются.

Разрешения запрашиваются в контексте нужного действия, с рабочим сценарием отказа; [Android runtime permissions](https://developer.android.com/training/permissions/requesting). Эти источники проверены при подготовке документа, но актуальные версии/требования снова проверяются при выборе TECH-001/002 и перед релизом.

## 3. Отдельно от технических ворот локальной версии

Расчётные рекомендации КБЖУ остаются будущей функцией: выбрать первичный источник, область применения, необходимые параметры, формулу/активность/БЖУ и независимо проверить эталоны. Пока этого нет, ручные цели доступны без рекомендации и без неподтверждённого автоматического предупреждения. Синхронизация, аккаунт и внешние интеграции также не являются скрытыми зависимостями локальной версии.

## 4. От выбора к проверяемой реализации

Пользовательский выбор: [Kotlin + Jetpack Compose + Room](stack-proposal.md), minSdk29; инженерная основа — [инструменты/SDK](android-build-baseline.md), [библиотеки](android-dependencies.md), [матрица](android-test-matrix.md). compile/target37 не меняет нижнюю границу. Исторические обоснования выбора сохраняются; фактическое использование API и границы испытаний отделены от перечня подключённых библиотек.

**TECH-001:** SDK/матрица определены документально. [Профиль](device-profile.md) получен частично: модель близких, One UI и остальные физические поля уточняются до испытаний. Пользователь подтвердил около 250 ГБ свободного места и выбрал открытие APK-файла на телефоне; фактическая установка/диагностика не проверялись. S23+/Android 17/8 ГБ и самый старый Android 14 — сообщения пользователя, не результаты проверок. Физических Android 10–13 не названо.

**TECH-002:** app + JVM domain-checks созданы; CLI/JDK/SDK/Wrapper/verification metadata, KSP/schema, debug и minified release исполнялись. JVM48/Lint0 PASS; API29 full80 и повтор24 UI PASS. Espresso3.7.0 закреплён только для androidTest вместо transitive3.5; compile16/17 PASS. Официальный API37.0 ps16k/x86_64: PAGE_SIZE16384, run17:81 PASS/111,131с; обычный API37/4 KiB run17:81 PASS/141,638с. Физические устройства требуют отдельных результатов.

**TECH-003/004:** точная арифметика/Unicode и Room v1 реализованы; реальные транзакции, snapshots/replay/conflict/rollback, reopen, DataStore recovery и unknown-format guards проверены. NC/ST/AN остаются каталогом дальнейшей полной приёмки; tested reopen не подменяет все process-death/reboot случаи или ещё не существующую миграциюv2.

**TECH-005/006:** decoder/master/metadata/GC и manifest/XML реализованы. API29 проверил JPEG/8 EXIF/PNG/WebP/F16/P3/downsample, синтетические ошибки/cancel, actual paths и локальный запрет backup; N16-37 проверил ещё synthetic Gainmap/MPF stripping. PH-001–016/BP-001–008 полностью не закрыты: реальные HDR/providers/OOM/disk-full/kill, cloud/D2D/OEM и физические замеры памяти остаются открытыми.

**TECH-007/008/009:** scheduler и проверенный starter60 реализованы, первые framework/content/S2 проверки исполнены. Доставка в Doze/OEM, полный S2 и p95 на выбранных телефонах не подтверждены. Неизвестная модель телефона остаётся неизвестной; детали прогонов/дефектов/исправлений и remote SHA находятся в validation/work-log, а не выводятся из статуса этого реестра.
