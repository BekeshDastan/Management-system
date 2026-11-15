# Diet — фиксированный каталог базовых зависимостей

Проверено: 15 ноября 2026 года. Статус: **инженерный выбор версий для будущего проекта**, без Gradle Sync, полного разрешённого графа и Android-кода. Инструменты и SDK — в [android-build-baseline.md](android-build-baseline.md). Каталог не является lockfile или доказательством совместной работы всех транзитивных зависимостей.

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
| Сериализуемые ключи навигации | org.jetbrains.kotlinx:kotlinx-serialization-core | 1.13.0 | [Официальный выпуск](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.13.0), compiler plugin 2.4.21 из базовой конфигурации |

BOM 2026.09.00 — реально прочитанный Stable POM, а не предположение по номеру месяца. Он задаёт UI/runtime/foundation/animation 1.12.1 и Material 3 1.4.0. Compiler plugin, Activity, Lifecycle, Room и прочие группы BOM не фиксирует. Alpha/Beta BOM и случайные preview-подстановки из примеров не используются.

Serialization применяется к небольшим ключам навигации по [правилам Navigation 3](https://developer.android.com/guide/navigation/navigation-3/save-state); это не функция экспорта/резервирования дневника. Факты и постоянные черновики остаются в транзакционном хранилище, а не в сериализованном back stack. POM Serialization 1.13.0 указывает stdlib 2.4.10, Coroutines 1.11.0 — 2.2.20; выбранный Kotlin 2.4.21 выше этих базовых версий. Это проверка заявленных версий, не бинарный тест.

### Почему Room 2 и bundled SQLite

Для Android-only дневника выбрана поддерживаемая стабильная ветка Room 2.8.5: её транзакции/миграции и SQLiteDriver достаточны для текущего контракта. Room 3.0.3 также стабилен, но вводит отдельные пакеты/координаты и обязательные coroutine/KSP API, преимущественно развивая KMP. Это не делает его плохим вариантом; смена ветки сейчас не нужна требованиям Diet. [Различия Room 3](https://developer.android.com/jetpack/androidx/releases/room3).

Использовать androidx.room, не смешивать с androidx.room3. Room compiler/runtime/Gradle plugin имеют одну версию; KSP2 обязателен для выбранного Kotlin-пути. SQLite-библиотека 2.7.1 выбрана отдельно от версии Room. Экспорт схем в Git при будущей разработке допустим только как схема без пользовательских данных, а не экспорт дневника. Разрушительный fallback запрещён контрактом данных.

BundledSQLiteDriver уменьшает зависимость SQL-поведения от прошивки телефона; единый файл БД остаётся в области приложения. В APK появляются готовые native-библиотеки: ARM64/эмуляторные ABI, размер, загрузка и 4/16 КБ требуют отдельной проверки. NDK-код Diet сейчас не создаётся. Полный механизм хранения/миграций остаётся TECH-004; выбор драйвера не закрывает его.

## 2. Проверенные AAR-границы

Читались официальные AAR в памяти: AndroidManifest.xml и META-INF/com/android/build/gradle/aar-metadata.properties. Библиотеки не исполнялись и не сохранялись в репозиторий. В таблице Android-варианты multiplatform-артефактов; прямое подключение проекта остаётся по координатам раздела 1.

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

Для этих девяти AAR максимумы требований: minSdk 24, minCompileSdk 37, min AGP 9.1.0; у проверенных артефактов minor/extension не требуют повышения сверх выбранной платформы. База Diet 29/37/AGP 9.3.1 укладывается в эти декларации. Это не максимум всего ещё не разрешённого транзитивного графа.

Первичный источник файлов — [Google Maven](https://dl.google.com/dl/android/maven2/). Воспроизводимый путь: группа с точками, заменёнными на слеши / artifact / version / artifact-version.aar; например [UI AAR](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1.aar), [Room AAR](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-android/2.8.5/room-runtime-android-2.8.5.aar). Общий AndroidX default minSdk не заменяет эти отдельные декларации.

Дополнительно прочитаны POM Navigation 3/runtime и Lifecycle/navigation3: базовые версии Compose/Lifecycle у них ниже выбранных, строгие связи внутри Lifecycle — 2.11.0. SQLite bundled требует sqlite-android ровно 2.7.1. В будущем разрешённом графе проверить выравнивание семей и отсутствие неожиданного повышения/preview; это остаётся задачей Gradle, не ручным обещанием на основании нескольких POM.

## 3. Будущие тестовые зависимости

| Проверки | Версии для первой проверочной сборки |
|---|---|
| JVM-правила и арифметика | junit:junit 4.13.2; org.jetbrains.kotlinx:kotlinx-coroutines-test 1.11.0 |
| Android runner / контекст | androidx.test:runner 1.7.0, androidx.test:core 1.7.0 |
| Android JUnit | androidx.test.ext:junit 1.3.0 |
| Compose UI | ui-test-junit4 / ui-test-manifest / ui-tooling через тот же BOM; tooling только в debug/test |
| Room-миграции | androidx.room:room-testing 2.8.5 |

Основания: [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test), [JUnit POM](https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom), выбранные BOM/Coroutines/Room. Это будущие инструменты для каталога приёмки, не уже написанные тесты. Тестовый транзитивный граф и фактические задачи проверяются вместе с основным графом.

## 4. Политика зависимостей

- Сначала одна Android app-основа, понятные UI/правила/данные и ручное связывание зависимостей. Hilt/Koin, серверный клиент, ML/OCR, камера и дополнительные SDK не добавляются без предметной необходимости.
- Графики можно строить средствами Compose с отдельными расчётами и доступными текстовыми значениями; сторонняя библиотека графиков сейчас не выбрана. Фото-декодирование/миниатюры/возможный image-loader решаются в TECH-005; реальный планировщик — TECH-007. Их отсутствие в каталоге не удаляет функции из релиза.
- Фиксировать версии без +, latest.release, snapshots и preview. При изменении одного семейства повторять проверку связанной части графа; не обновлять всё одновременно.
- После разрешения кода сохранить version catalog, Wrapper/verification metadata и фактически разрешённые версии. Проверить debug и release classpath, выравнивание Kotlin/Compose/Lifecycle/SQLite, KSP generation и Android minimum metadata. Сейчас ни одного такого build-файла нет.

Выбор библиотек принят как плановая техническая база текущего поручения. Совместная сборочная/исполнительная проверка остаётся открытой в TECH-002; любые новые зависимости проходят ту же проверку перед своим этапом.
