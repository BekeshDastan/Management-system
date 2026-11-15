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

## Фото — Android10/API29

PhotoEngineTest:11cases PASS в совместном instrumentation run06. Проверены все8EXIF-поворотов с цветными контрольными углами и чтение master после удаления источника; прозрачный PNG превращается в белый opaque sRGB JPEG; штатный свежий ICC_PROFILE допустим, EXIF/MPF/Photoshop/JFXX отклоняются. Невалидный второй файл не публикует первую половину, фактические5MiB+1 и неподдержанный MIME отклоняются. Отмена до Ready не публикует поздний batch; отмена после durable Ready сохраняет draft/master. Ошибка fsync файла или каталога не создаёт Ready-ссылку и освобождает reserve; read lease защищает master от GC, неизвестные файлы сохраняются; PhotoID/caption не меняются после чтения draft.

Первый runtime-прогон нашёл ошибку blanket APP2 rejection: Android29 encoder создаёт новый стандартный sRGB ICC, который не содержит пользовательских EXIF/GPS. Валидатор теперь принимает только один структурно проверенный ICC_PROFILE/RGB/acsp; остальные private segments по-прежнему запрещены. Свежий canvas остаётся источником оптимизированного JPEG.

Не выполнены этим run: WebP/animation/HDR/F16 input; реальные low-RAM/OOM; все6веток downsizing; framework Picker/SAF на разных providers; отзыв grants/медленный stream; process-kill каждой файловой фазы; настоящий disk-full/cache eviction128MiB; физические устройства. Их не считать PASS по наличию guard-кода. Media operation/read gates и native decode cancellation протестированы в названных синтетических точках.

## Приватность, пути и системная локальная копия

API29 run09: path inventory PASS, но merged-manifest test FAIL из-за библиотечного Room MultiInstanceInvalidationService/directBootAware=true. Single-process приложение не использует эту службу; удалена через manifest merger, строгая проверка сохранена. В run10 оба StoragePrivacyIntegrationTest PASS: manifest allowBackup=false/no BackupAgent/no broad network/storage, никакого Direct Boot; два compiled XML содержат все9доменных исключений каждого транспорта. Проверены фактический CE Room path по PRAGMA database_list, WAL/SHM/FK/FULL, production-path DataStore+tokens/raw, masters/staging/cache и отсутствие synthetic marker вне разрешённых областей.

На изолированном DietApi29 был выбран com.android.localtransport/.LocalTransport, backup manager временно включён, bmgr backupnow app.diet вернул для пакета «Backup is not allowed». Затем manager disabled и прежний Google transport восстановлены. Ответ общего job «Success» не трактуется как наличие копии: результат самого пакета — запрет. Никакой учётной записи/личного телефона/системного factory reset не использовалось.

Cloud/D2D/OEM и uninstall/reinstall на физических телефонах не выполнены; гарантия чужого транспорта не заявлена. Full run10 пока не PASS: отдельный S2 timeout100000facts и async UI test ожидание разбираются,71из73успешны. Это не дефект privacy cases.

## Настоящий starter, draft-медиа и WebP

API29 run09/10: StarterCatalogIntegrationTest2 PASS на реальном asset60, а не копии ожидаемых констант. Проверены source IDs/English/form/revision, контрольная банановая порция, edit/delete/reopen и changed-manifest без reset/resurrection.

DraftMediaRecoveryIntegrationTest2 PASS: повторный вход существующего moment добавляет недостающие существующие refs к staged без clear; raw payload/revision не перезаписываются. Ограничение4, date/owner/READY/media revision остаются активными; повтор idempotent. Это необходимо для формы, которая может получить callback/StateFlow с задержкой.

WebpPhotoEngineTest3 PASS API29: статический lossy WebP; прозрачный WebP→белый opaque JPEG; animated WebP→первый красный кадр. Локальная синтетическая32×24 red→blue2frames fixture140B хранится только в androidTest/assets, SHA256a669035f74de598a56f82b8e41e2f3d9d8ccde95fd9d84b5d64869c00a674e89; личных фото нет. Это расширяет прежние11media cases; HDR/F16/real providers ещё не считать PASS.

## Мягкие напоминания — первые runtime результаты

6JVM local-time cases PASS: DST gap/overlap, строгая следующая дата, weekday mask, зона Kathmandu, текущая зона/дата/2ч grace и границы9999. Напоминания используют динамическую текущую зону при reconcile, одна декларация/канал на food/weight/sleep, изначально выключены.

API29 ReminderSchedulerTest2 PASS в runs09/10: настоящие AlarmManager/PendingIntent/NotificationManager и отдельная синтетическая Room. После setWindow outbox подтверждается и immutable PendingIntent существует; handleAlarm публикует реальную notification с общим текстом/intent, повтор не создаёт второй occurrence; disable отменяет pending notification и alarm intent. Старая generation, чужая timezone и новая generation после уже claimed даты не дублируют dispatch.

Это проверка реальных framework вызовов/каналов/posting с детерминированным временем, не ожидание ОС15минутного окна. Не подтверждены автоматическая доставка в Doze/после cold reboot/OEM, нажатие заблокированного экрана физического телефона, отсутствие задержек или фактическое прочтение пользователем. POSTED в ledger означает успешный вызов notify, не «доставлено». В UI эта неопределённость сформулирована.

## История100000фактов — исправление чтения

Первый S2food-only test на API29 не уложился в производственный10с deadline. По исходнику/EXPLAIN установлены повторные OFFSET-сканирования и повторная полная проверка одинаковых снимков. ReportQueries теперь проходит индекс(localDate,mealId,ordinal,id) compound keyset страницами250, без TEMP SORT/повторного начала диапазона; bounded LRU256 хранит только значения полного уже проверенного immutable ключа snapshot+quantity/quick в одном reader snapshot. Exact p/q и cancellation/deadline не ослаблены.

Targeted run11:5tests PASS (4ReportQueries+S2),19.504с всего. В неизменённом S2fixture100000строк/3653даты:seed8169мс/read8305мс; PSSдо110432KiB/после124365KiB. N1826/Q913 и все4нутриента сверены независимой BigInteger cross-product arithmetic;≤600bucket и последние40точныхдат доступны, missing/zero/measurement-only различаются. Это одна итерация x86_64/2vCPUэмулятора, не p95 и не достижение3с наSamsung.

Отдельные regressioncases:610строк одной даты с2приёмами, одинаковыми ordinal, >256unique key/eviction/изменённымquantity/quick/длинным диапазоном; NULL в quickнутриенте отклоняется, а не превращается черезSQLite.getLong в0. Их arithmetic/seek review выполнено независимо. Full12 позже прерван native teardownсбоем в другом классе; общийPASS не заявляется, отчётные cases до сбоя прошли.

## Полный повтор13 — Android10/API29

После исправлений teardown run13 полностью PASS:80executedtests/0failures/0errors за73.733с. Разбиение: Repository22, ReportQueries5, S2scale1, prefs4, прочиеintegration6, PhotoEngine11/WebP3/PhotoColor2, notifications2, UI24. Gainmap case имеет SdkSuppress API34+ и на29 не исполнялся; не включён в эти80.

Предыдущий full12 завершился SIGSEGV в native SQLite при переходе между media tests. Локальный tombstone сопоставлен с APK mapping/libsqliteJni/releaseMemArray; найден небезопасный teardown cancel→db.close без join при IO observer. Все такие test/reopen scopes теперь cancelAndJoin до close, без задержек «на удачу» и подавления ошибок. Production DB живёт весь процесс и не закрывается этой тестовой очисткой. После исправления весь набор80 прошёл; это один полный повтор, не доказательство всех crash-interleavings.

Новый RGBA_F16/P3 case первоначально честно отказал: API29 Bitmap PNG encoder выдал8бит. Fixture заменена настоящим тестовым16-bit PNG (CRC32/Deflater + реальный native P3 iCCP); native decode подтвердил F16/wide gamut. Затем проверены свежий opaque ARGB_8888/sRGB JPEG, белая прозрачная область/цвет. Большой4096×1024 RGB565 PNG проходит выбранный адаптивный profile, пропорции/цвета сохранены, bitmap источника освобождён до импорта.
