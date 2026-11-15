# Diet — фактическая проверка Android-сборки

Обновлено 15 ноября 2026 года. Реализация разрешена новым поручением пользователя. Здесь только наблюдённые результаты; [матрица](android-test-matrix.md) и [168 ожидаемых случаев](acceptance-tests.md) не объявляются автоматически пройденными.

## Первая проверенная сборочная основа

Windows11/AMD64, Temurin21.0.13+9, Gradle9.5.0, AGP9.3.1, Kotlin/compiler2.4.21, KSP2.3.14, SDK37.0r2/BuildTools36.0.0, min29/target37. Глобальная Java не перенастраивается: инструменты, SDK/Gradle cache, test AVD и signing material находятся в игнорируемой .local-tools. Команды — scripts/bootstrap_tools.py, scripts/install-sdk.ps1, scripts/build.ps1; сеть для первой загрузки обязательна. Артефакты и ключи не в Git.

| Проверка | Наблюдённый результат |
|---|---|
| Wrapper/JDK version, Android tasks, dependency resolution | PASS; официальный checksum дистрибутива и verification-metadata.xml |
| KSP/Room schema v1 | Генерация выполнена; схема без данных экспортирована, runtime ещё проверяется |
| Основной debug APK и instrumentation APK | Сборка выполнена; наличие APK не доказывает работоспособность на Android |
| Чистый JVM domain-checks | 8 suites/44 tests,0failures/0errors:38domain +6local-time; Unicode15.1 полная NormalizationTest/CaseFolding фикстура |
| app JVM tests | 9 suites/48 tests,0failures/0errors: то же ядро плюс4media budget/bounded-output |
| Minified release | R8/ресурсы/сборка выполнены; первый universal unsigned APK11233695байт. Это ещё не подписанный пользовательский выпуск |
| Lint debug | Первоначально2NewApi: BigInteger.intValueExact и splash31 при min29. Исправлены checked range/toInt и values-v31; повтор PASS,0errors, подавления/поднятия min нет |
| Android runtime/UI/media/Room/notification | API29:36data/media/report cases PASS; UI и notifications проверяются отдельно, полного PASS пока нет |
| Физические S23+/близкие, cloud/D2D/OEM | Устройства/транспорт не подключены и не проверены; неподтверждённая совместимость/доставка/скорость не заявляются |

Зависимые исходники публикуются отдельными коммитами в codex/android-implementation; эта начальная фиксация toolchain не является готовым приложением. Один app-модуль плюс JVM-only domain-checks, который компилирует тот же чистый source отдельно от Android/UI; второй APK приложения не создаётся.

## Происхождение инструментов

Скачаны с официальных Adoptium/GitHub, Gradle и Android SDK repository; hashes проверены до распаковки. SDK packages устанавливает официальный Android CLI с --no-metrics. Совместимость ранее выбрана в [baseline](android-build-baseline.md); наличие более новых версий Lint не является основанием обновить всё одновременно.

| Инструмент | Алгоритм | Проверенное значение |
|---|---|---|
| Temurin21.0.13+9 | sha256 | a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9 |
| Gradle9.5.0 | sha256 | 553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746 |
| AndroidCLI16111833 | sha1 | 57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d |

Gradle runtime/artifacts разрешаются только из Google Maven, Maven Central и Gradle Plugin Portal. Контрольные суммы не заменяют тестов/ревью; проверка новых dependencies не отключается при сбое. Новый SDK CLI заменил sdkmanager: непосредственный native вызов сохраняет package IDs с точкой с запятой; scripts/install-sdk.ps1 использует его.

## Пока не подтверждено

Личные данные не вводились. Нет физической матрицы/OEM cloud/D2D, длительного idle/performance-прогона и магазинной публикации. Не путать unit-test PASS/сборку с полной приёмкой NFR. Подпись конечного APK, фактический native ABI/page alignment, холодный старт, screenshots и Android результаты фиксируются после исполнения.

## Хранение и отчёты — Android10/API29

Сборка06: реальный instrumentation runner на отдельном DietApi29/x86_64, BundledSQLiteDriver/Room/DataStore. 36tests PASS за11.03с:22Repository,3ReportQueries и11PhotoEngine. Тесты используют UUID-файлы и синтетические значения; production dataset и личные данные не изменяются.

Repository: повтор после удалённого факта/закрытого draft возвращает старый receipt; конфликт не перезаписывает чужую revision; rollback не оставляет частичных строк/receipt; подтверждённая операция остаётся успешной при последующей ошибке refresh. Проверены отдельная явная замена снимка, отложенная ручная цель после onboarding, идемпотентное наполнение с изменённым manifest, полнота/нулевой день/удаление последней строки, raw draft после reopen, paged headers без raw JSON в StateFlow, история вне первых40, goal timeline, future schema/draft/preferences без сброса, изменение timezone и повторы планов. DataStore: независимые группы, token/fingerprint, recovery barrier и сериализация TreeMap как Map.

ReportQueries: эталон N/Q/нулей и moment-only даты; весь диапазон1900–9999 с ограничением600bucket и доступной точной последней страницей; параллельный writer не смешивает еду и цели разных снимков. Это не измерение скорости на Samsung и не доказательство100000строк под физическим memory pressure.
