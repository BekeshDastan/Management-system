# Diet — базовая конфигурация Android-сборки

Дата проверки и актуализации: 15 ноября 2026 года. **Конфигурация применена в разрешённом Android-проекте и проверена исполнением:** CLI/JDK/SDK/Wrapper, KSP, debug, JVM48, Lint0 errors и R8 unsigned. API29 full80 и последующий UI24 PASS; compile16/17 с обновлённым тестовым Espresso PASS; официальный API37.0 ps16k/x86_64 run17:81 PASS. Обычный API37/4 KiB run17:81 PASS за141,638с. Основания выбора сохранены ниже; результаты — [build-validation](build-validation.md), функции — [implementation-coverage](implementation-coverage.md), публикация — [work-log](work-log.md).

Пользователь выбрал Kotlin + Jetpack Compose + Room, Android10+ / minSdk29 и отдельно разрешил реализацию15 ноября. Точные инструменты/SDK зафиксированы ниже, библиотеки — в [каталоге](android-dependencies.md); поддержка не сужается. Физические/OEM проверки и полный p95 остаются открытыми.

## 1. Выбранные инструменты

| Компонент | Фиксируемая версия / режим | Основание |
|---|---|---|
| Android Studio | Rabbit 2, 2026.2.2 Patch 1, Stable — выбранная IDE, её запуск не подтверждён | [Релизы Studio](https://developer.android.com/studio/releases): поддерживает выбранную ветку AGP; фактические сборки выполнялись CLI |
| Android Gradle Plugin | 9.3.1 | [AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes), внутри диапазона полной поддержки KGP |
| Gradle Wrapper | 9.5.0 | Минимум/default для AGP9.3; Wrapper и checksum созданы в проекте |
| JDK для Gradle daemon | Eclipse Temurin 21.0.13+9, Windows x64 HotSpot JDK | [Официальный выпуск](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9), [Gradle/Java](https://docs.gradle.org/current/userguide/compatibility.html) |
| Kotlin / runtime KGP | 2.4.21 | [Таблица KGP](https://kotlinlang.org/docs/gradle-configure-project.html): Gradle 7.6.3–9.7.0, AGP 8.5.2–9.3.1 |
| Подключение Kotlin в Android | Built-in Kotlin AGP 9, с явным повышением runtime KGP до 2.4.21 | [Правила built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes), [миграция](https://developer.android.com/build/migrate-to-built-in-kotlin) |
| Compose compiler plugin | org.jetbrains.kotlin.plugin.compose, 2.4.21 | [Совпадает с Kotlin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html); Compose BOM его не выбирает |
| Serialization compiler plugin | org.jetbrains.kotlin.plugin.serialization, 2.4.21 | Совпадает с Kotlin; нужен выбранной стратегии сохраняемых ключей Navigation 3 |
| Kotlin Symbol Processing | com.google.devtools.ksp, 2.3.14, KSP2 | [Официальный релиз](https://github.com/google/ksp/releases/tag/2.3.14), [требования KSP](https://kotlinlang.org/docs/ksp-overview.html) |
| Java/Kotlin bytecode target | 17 для обоих языков | [JDK и JVM target](https://developer.android.com/build/jdks); не путать с JDK Gradle или Android API |

Для выбранной Studio сохраняется собственный bundled JBR; STUDIO_JDK не переназначается. Реальные сборки выполнялись CLI на отдельном Temurin21/Windows AMD64; Android-эмуляторы запущены, API29 instrumentation прошёл. Это не подтверждает запуск выбранной IDE или иной архитектуры хоста. При смене хоста проверять JDK/образ/ускорение заново, не менять версии молча.

Built-in Kotlin используется без org.jetbrains.kotlin.android; org.jetbrains.kotlin.jvm не добавлен в Android app-модуль. Runtime KGP повышен до выбранного2.4.21 по правилам AGP. Отдельный JVM-only domain-checks может использовать JVM plugin и не создаёт второй Android APK.

KSP 2.3.14 опубликован 13 ноября; страница overview ещё описывает 2.3.12. Номер KSP не должен совпадать с Kotlin: [изменение схемы версий](https://github.com/google/ksp/releases/tag/2.3.0). Проверку конкретной пары KSP/Room не заменяет наличие релиза.

### Почему не взять последний AGP автоматически

Исходное обоснование выбора: AGP9.5 существует, но таблица полной поддержки KGP2.4.21 заканчивается на9.3.1. Выбрано пересечение диапазонов. В9.3.2/9.3.3 есть дополнительные исправления, включая Lint на JDK17; JDK21 обходит именно этот путь, но не делает9.3.1 свободным от других дефектов. Теперь Lint и minified release исполнены; новые патчи принимаются отдельным проверяемым изменением, без отключения проверок.

## 2. Выбранные SDK

| Параметр | Выбор | Смысл |
|---|---|---|
| minSdk | 29, Android 10 | Уже принят пользователем; нижняя граница установки |
| compileSdk | 37, minor 0 | Финальные API Android 17; не Preview/Canary/37.1/37.2 |
| targetSdk | 37 | Адаптация нового приложения к поведению Android 17; проверяется по матрице, а не выводится из телефона |
| SDK Platform | platforms;android-37.0, revision 2 | В официальном каталоге на дату проверки: channel-0, пустой codename |
| SDK Build-Tools | 36.0.0 | Default/minimum выбранного AGP; номер не обязан совпадать с compileSdk |
| Platform-Tools | 37.0.1, Stable | Каталог содержит также более новый preview 37.0.3; он не выбран |
| Android Emulator | 37.2.13, Stable | Каталог содержит также beta 37.3.9; она не выбрана |
| NDK/CMake | Собственного NDK-кода Diet нет | SQLite поставляется готовым артефактом; статические ABI/ELF/ZIP проверки кандидата и runtime на API37 ps16k/x86_64 выполнены; иные ABI/устройства требуют проверки |

Первоначально сведения прочитаны из [официального каталога SDK](https://dl.google.com/android/repository/repository2-3.xml), затем выбранные CLI-пакеты установлены для сборки. Наличие Stable само по себе недостаточно: каталог содержит и Canary; проверены имя финальной платформы, пустой codename и релизное объявление.

Android 17 выпущен по [официальному сообщению от 16 июня 2026 года](https://developer.android.com/blog/posts/android-17-is-here); [SDK setup](https://developer.android.com/about/versions/17/setup-sdk) задаёт compile/target 37. Отдельные справочные страницы всё ещё содержат Preview/Beta: для этого решения использованы релизное сообщение и фактические метаданные платформы. Выбор не подтверждает установленную ОС S23+ — её пока сообщил пользователь.

37 выбран как финальная основная платформа нового проекта и для совместимости с выбранными современными AndroidX. Для target 37 обязательна адаптация поведения; Android 10–16 остаются в поддержке. Не заменять minSdk на 37 и не использовать 37.x preview. Правила конкретного магазина проверяются отдельно перед публикацией APK; магазин сейчас не выбран и не является основанием этого решения.

## 3. Границы проверки

Выполнено:

- сверены официальные таблицы Studio/AGP/Gradle/KGP/JDK и релизы Kotlin/KSP;
- первоначально прочитаны Maven/SDK-метаданные, затем разрешён граф и исполнены сборки;
- зафиксированы version catalog, Wrapper/checksum и verification metadata;
- KSP/Room schema v1, debug, JVM48, Lint0 errors и R8 unsigned PASS;
- API29 full run13:80 PASS; после UI-правок run15:24 UI PASS. Compile16/17 включают test-only Espresso3.7.0; официальный API37.0 ps16k/x86_64 run17:81 PASS, обычный API37/4 KiB run17:81 PASS за141,638с.

IDE Gradle Sync отдельно не заявляется: использовался CLI. Физические устройства, API31/33–36, cloud/D2D/OEM, полная миграционная/performance-матрица не подтверждены. Статический alignment не заменяет runtime; на N16-37 фактически проверены PAGE_SIZE16384 и полный имеющийся набор81. Это не физический ARM64/иной16 KB образ. Исходное наблюдение о PATH не описывает установленную теперь среду.

## 4. Повторяемый сборочный контроль

Первоначальная сборочная основа создана после разрешения реализации. Ниже сохранён порядок контроля для изменений инструментария/зависимостей; выполненные пункты и оставшиеся runtime-проверки различаются по validation.

1. Подтвердить архитектуру хоста, выбранный JDK, доступность SDK и способ запуска эмулятора.
2. Зафиксировать Wrapper с контрольной суммой официального дистрибутива, версии плагинов/библиотек без динамических диапазонов и проверку зависимостей. Отдельно сохранить реально разрешённый граф; не называть список версий lockfile.
3. Проверить Gradle/JDK и задачи проекта: gradlew.bat --version, :app:tasks --all, :app:dependencies --configuration debugRuntimeClasspath. Отдельно проверять test runtime classpath; тестовая транзитивная библиотека тоже может быть несовместима с новым Android.
4. Выполнить KSP/Room generation на синтетической пробе, debug-сборку, Lint, предметные unit/instrumentation-проверки и minified release-сборку. Проверить Packaging/native ABI, 4/16 КБ, запуск на API 29 и 37. Сбой не обходить suppressUnsupportedCompileSdk или destructive migration.
5. Записать команды/версии/commit SHA и результаты. Исполнение одной конфигурации не переносит PASS на непроверенную ОС/ABI/физическое устройство.

Сборочная часть TECH-002 подтверждена выполненными проверками; непроверенные runtime-окружения и недостающие физические сведения TECH-001 остаются открытыми. Разрешение Android-реализации уже получено и повторно не требуется.
