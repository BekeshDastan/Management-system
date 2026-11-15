# Diet — фиксированный каталог базовых зависимостей

Проверено и актуализировано: 15 ноября 2026 года. **Набор подключён в созданном Android-проекте:** граф разрешён, KSP/debug/JVM48/Lint0/R8 unsigned выполнены; API29 full80 и последующий UI24 PASS. Compile16/17 с test-only Espresso3.7.0 PASS; официальный API37.0 ps16k/x86_64 run17:81 PASS, обычный API37/4 KiB run17:81 PASS за141,638с. Версии — [version catalog](../gradle/libs.versions.toml), hashes — [verification metadata](../gradle/verification-metadata.xml); инструменты — [baseline](android-build-baseline.md), результаты — [validation](build-validation.md), функции — [coverage](implementation-coverage.md). Каталог версий не называется lockfile и не доказывает всю runtime-матрицу.

## 1. Выбранный набор

| Назначение | Координаты / группа | Версия | Основание |
|---|---|---|---|
| Kotlin stdlib | org.jetbrains.kotlin:kotlin-stdlib | 2.4.21 | Та же линия, что KGP/compiler plugins; [Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html) |
| Compose BOM, Stable | androidx.compose:compose-bom | 2026.09.00 | [BOM](https://developer.android.com/develop/ui/compose/bom), проверен [опубликованный POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom) |
| UI/runtime/foundation/animation | Соответствующие androidx.compose.*; версии задаёт BOM | 1.12.1 из выбранного BOM | Не закреплять другие версии вручную поверх BOM |
| Material 3 | androidx.compose.material3:material3 | 1.4.0 из BOM | [Релизы](https://developer.android.com/jetpack/androidx/releases/compose-material3); собственный дизайн принятого макета сохраняется |
| Android Core | androidx.core:core | 1.19.1 | [Релизы](https://developer.android.com/jetpack/androidx/releases/core); отдельный core-ktx не нужен для нового проекта |
| Compose Activity / Photo Picker contracts | androidx.activity:activity-compose | 1.13.0 | [Activity](https://developer.android.com/jetpack/androidx/releases/activity) |
| Lifecycle / ViewModel / сохранённое UI-состояние | lifecycle-runtime-compose, lifecycle-viewmodel-compose, lifecycle-viewmodel-savedstate группы androidx.lifecycle | 2.11.0 | [Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| Coroutines/Flow | org.jetbrains.kotlinx:kotlinx-coroutines-core и kotlinx-coroutines-android | 1.11.0 | [Официальный выпуск](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0) |
| Независимые UI-предпочтения | androidx.datastore:datastore-preferences | 1.3.0 | [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore) |
| Дневник / транзакции / миграции | androidx.room:room-runtime, room-compiler через KSP, Gradle plugin androidx.room | 2.8.5 для всех трёх | [Room 2](https://developer.android.com/jetpack/androidx/releases/room) |
| SQLite driver | androidx.sqlite:sqlite-bundled | 2.7.1 | [SQLite](https://developer.android.com/jetpack/androidx/releases/sqlite); собственная библиотека SQLite, не системная версия разных телефонов |
| Навигация Compose | androidx.navigation3:navigation3-runtime и navigation3-ui | 1.2.1 | [Navigation 3](https://developer.android.com/jetpack/androidx/releases/navigation3); не брать alpha из примера страницы |
| ViewModel на запись back stack | androidx.lifecycle:lifecycle-viewmodel-navigation3 | 2.11.0 | Та же версия Lifecycle, отдельная от версии Navigation 3 |
| Ключи навигации и versioned JSON черновиков | org.jetbrains.kotlinx:kotlinx-serialization-core и kotlinx-serialization-json | 1.13.0 | [Официальный выпуск](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.13.0), compiler plugin 2.4.21 из базовой конфигурации |

BOM 2026.09.00 — реально прочитанный Stable POM, а не предположение по номеру месяца. Он задаёт UI/runtime/foundation/animation 1.12.1 и Material 3 1.4.0. Compiler plugin, Activity, Lifecycle, Room и прочие группы BOM не фиксирует. Alpha/Beta BOM и случайные preview-подстановки из примеров не используются.

Serialization применяется к versioned JSON черновиков/контекста и receipt payload; это не экспорт/резервирование дневника. Изначально библиотека выбрана также по [правилам сохраняемых ключей Navigation3](https://developer.android.com/guide/navigation/navigation-3/save-state); фактический стек сейчас ведёт UiHost, без NavDisplay. Raw-числа и p/q сохраняются точными строками, не Double; payload ограничен numeric-contract. Первоначальная проверка POM установила core1.13.0/stdlib2.4.10 у Serialization и stdlib2.2.20 у Coroutines1.11.0; выбранный Kotlin2.4.21 выше этих баз. После проверки метаданных выполнены сборка и тесты, перечисленные в validation.

### Почему Room 2 и bundled SQLite

Для Android-only дневника выбрана поддерживаемая стабильная ветка Room 2.8.5: её транзакции/миграции и SQLiteDriver достаточны для текущего контракта. Room 3.0.3 также стабилен, но вводит отдельные пакеты/координаты и обязательные coroutine/KSP API, преимущественно развивая KMP. Это не делает его плохим вариантом; смена ветки сейчас не нужна требованиям Diet. [Различия Room 3](https://developer.android.com/jetpack/androidx/releases/room3).

Используется androidx.room, без смешивания с androidx.room3; compiler/runtime/Gradle plugin имеют одну версию, KSP2 генерирует код и schema v1. SQLite2.7.1 выбран отдельно от Room. В Git хранится только схема без пользовательских данных; это не экспорт дневника. Разрушительного fallback нет.

BundledSQLiteDriver уменьшает зависимость SQL-поведения от прошивки; единый файл БД находится в CE noBackup. Собственного NDK-кода нет. Статические ABI/ELF/ZIP проверки кандидата, API29/x86_64 и API37.0 ps16k/x86_64 с PAGE_SIZE16384 выполнены. Физический ARM64 и другие окружения остаются отдельными проверками. Выбор драйвера сам по себе не закрывает TECH-004/матрицу миграций.

## 2. Проверенные AAR-границы

Первоначальный read-only аудит читал официальные AAR в памяти: AndroidManifest.xml и META-INF/com/android/build/gradle/aar-metadata.properties. Таблица сохраняет эти декларации; последующее разрешение графа и исполнение описаны отдельно. Бинарники библиотек не добавляются в Git. Здесь Android-варианты multiplatform-артефактов; подключение — по координатам раздела1.

| Проверенный Android AAR | minSdk | minCompileSdk | min AGP |
|---|---:|---:|---:|
| compose.ui:ui-android:1.12.1 | 23 | 37 | 9.1.0 |
| compose.material3:material3-android:1.4.0 | 21 | 35 | 8.6.0 |
| activity:activity-compose:1.13.0 | 23 | 36 | 8.9.1 |
| lifecycle:lifecycle-runtime-compose-android:2.11.0 | 23 | 37 | 9.1.0 |
| datastore:datastore-preferences-android:1.3.0 | 24 | 34 | 8.1.1 |
| room:room-runtime-android:2.8.5 | 23 | 34 | 8.1.1 |
| sqlite:sqlite-bundled-android:2.7.1 | 23 | 34 | 8.1.1 |
| navigation3:navigation3-ui-android:1.2.1 | 24 | 36 | 8.9.1 |
| core:core:1.19.1 | 23 | 37 | 9.1.0 |

Для этих девяти AAR максимумы требований: minSdk24, minCompileSdk37, min AGP9.1.0; minor/extension не требуют повышения выбранной платформы. База29/37/AGP9.3.1 укладывается в декларации. Этот аудит не заменяет проверку всего графа; фактические Gradle metadata/KSP/build checks затем прошли без повышения minSdk.

Первичный источник файлов — [Google Maven](https://dl.google.com/dl/android/maven2/). Воспроизводимый путь: группа с точками, заменёнными на слеши / artifact / version / artifact-version.aar; например [UI AAR](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1.aar), [Room AAR](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-android/2.8.5/room-runtime-android-2.8.5.aar). Общий AndroidX default minSdk не заменяет эти отдельные декларации.

Дополнительно прочитаны POM Navigation3/runtime и Lifecycle/navigation3: базовые Compose/Lifecycle ниже выбранных, связи Lifecycle —2.11.0. SQLite bundled требует sqlite-android ровно2.7.1. Выравнивание/отсутствие preview проверяются в реально разрешённом графе и повторно при изменении семей; несколько POM не заменяют этот контроль.

## 3. Подключённые тестовые зависимости

| Проверки | Закреплённые версии |
|---|---|
| JVM-правила и арифметика | junit:junit 4.13.2; org.jetbrains.kotlinx:kotlinx-coroutines-test 1.11.0 |
| Android runner / контекст | androidx.test:runner 1.7.0, androidx.test:core 1.7.0 |
| Android JUnit | androidx.test.ext:junit 1.3.0 |
| Compose UI | ui-test-junit4 / ui-test-manifest / ui-tooling через тот же BOM; tooling только в debug/test |
| Espresso для Compose instrumentation | androidx.test.espresso:espresso-core:3.7.0, явно только androidTest; заменяет разрешавшуюся транзитивно3.5 |
| Room-миграции | androidx.room:room-testing 2.8.5 |

Основания: [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test), [JUnit POM](https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom), выбранные BOM/Coroutines/Room. Тесты созданы и исполняются; основной и тестовый classpath проверяются отдельно.

На обычном API37 старый transitive Espresso3.5 вызвал24 UI failure при reflection `InputManager.getInstance`, хотя пользовательский UI запускался вручную. Явный test-only3.7.0 содержит переход на `getSystemService` по [официальным release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0). Version catalog и verification metadata обновлены; compile16/17 PASS. На официальном API37 ps16k run17:81 PASS; обычный API37/4 KiB run17 также прошёл81 тест за141,638с. Прерванный ps16k run16 был отдельной ошибкой teardown: отзыв notification permission внутри UID завершал процесс; grant/restore перенесены во внешний CLI после runner. Эти изменения тестового окружения не меняют production-код и не добавляют Espresso в release.

## 4. Политика зависимостей

- Сначала одна Android app-основа, понятные UI/правила/данные и ручное связывание зависимостей. Hilt/Koin, серверный клиент, ML/OCR, камера и дополнительные SDK не добавляются без предметной необходимости.
- Графики реализованы средствами Compose с отдельными расчётами/текстовой таблицей, без внешнего chart SDK. Фото использует framework ImageDecoder API28+ (в min29), Activity picker contracts, собственный cache/очередь/bounded stream по [TECH-005](photo-processing.md). Сетевой image-loader/ExifInterface dependency не добавлены; возможный новый fallback требует проверки графа. Реальный планировщик использует framework AlarmManager/NotificationManager по TECH-007.
- Фиксировать версии без +, latest.release, snapshots и preview. При изменении одного семейства повторять проверку связанной части графа; не обновлять всё одновременно.
- Version catalog, Wrapper/checksum и verification metadata сохранены. При изменении зависимостей повторять debug/release/test classpath, выравнивание Kotlin/Compose/Lifecycle/SQLite, KSP generation и Android minimum metadata; не отключать verification ради нового артефакта.

Библиотеки являются текущей сборочной основой, а не только предложением. Сборочная часть TECH-002 и названные runtime-наборы подтверждены; обычный API37/4 KiB также прошёл81 тест, другие API/физическая матрица и полный каталог остаются открытыми. Новые зависимости проходят тот же контроль перед своим этапом.
