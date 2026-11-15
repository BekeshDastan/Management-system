# Diet — локальный подписанный APK и обновления

**Состояние 15 ноября2026:** финальный R8 APK подписан и статически проверен; install/update smoke на выделенных29/37 выполняются отдельно. Unsigned и debug не выдаются за конечный пользовательский выпуск. Ключ не является аккаунтом/облачной синхронизацией или функцией резервной копии приложения.

## Постоянная подпись

Создан один приватный RSA3072/JKS alias diet-release на30лет. Keystore и отдельный случайный пароль находятся только в игнорируемой .local-tools/signing. Скрипт не печатает пароль и не передаёт его значением аргумента: keytool/apksigner читают password file. Потеря этого материала затруднит совместимое обновление; не удалять/не пересоздавать ключ при следующей сборке. Сам ключ/пароль не загружать в GitHub, issue, журнал или APK. Хранение приватного материала владельцем — отдельно от исключённой продуктовой функции backups.

Публичный SHA256 сертификата текущего ключа: ba726a7b3396655b56a2329827bc5de3874a12d37198ba8ac64134f783d43220. Это публичный идентификатор, не секрет. Первая установленная пользователем версия и следующие обновления должны иметь appId app.diet и этот сертификат. Для новых публикуемых версий повышать versionCode; неизвестную будущую DB schema не сбрасывать. Проверку update выполняют на синтетическом dataset до установки на личный телефон.

## Команды

1. scripts/build.ps1 :app:assembleRelease — minified/shrunk unsigned.
2. python scripts/sign_apk.py app/build/outputs/apk/release/app-release-unsigned.apk artifacts/Diet-1.0.0.apk — проверка zipalign -c -P16 до подписи, затем v2/v3 signing. --init-key нужен только при первом создании; существующий ключ никогда не заменяется.
3. python scripts/verify_apk.py artifacts/Diet-1.0.0.apk --release --require-signature --report artifacts/Diet-1.0.0.validation.json — non-debug/min29/target37, permissions, оба compiled XML,9доменных исключений каждого транспорта, отсутствие DB/keys,60starter, все native PT_LOAD и ZIP16KiB, apksigner verify/публичный certificate SHA.
4. Установить именно подписанный minified APK на тестовые API29/37, пройти сценарии, force-stop/relaunch и same-key update с контрольными фактами/настройками/фото. Успех debug-тестов не доказывает отсутствие R8-specific проблем. Cloud/D2D/OEM/физические проверки остаются отдельными.

[Android apksigner](https://developer.android.com/tools/apksigner) требует выравнивать APK до подписи; изменение ZIP после подписи делает её недействительной. [Сборка командной строкой](https://developer.android.com/build/building-cmdline) описывает отдельную ручную подпись. Использован BuildTools36.0.0 и JDK21 из принятой основы.

## Интерпретация результатов

Без --require-signature verifier проверяет только упаковку и явно выдаёт signature.checked=false: такой PASS не подтверждает устанавливаемый выпуск. С флагом unsigned APK должен завершиться ошибкой. ELF/ZIP16KiB проверка не заменяет запуск на отдельном16KiB образе. APK hash/размер меняются после новой сборки; финальные значения фиксируются отдельно после последнего изменения кода.

Перенос конечного APK: скопировать файл на телефон, открыть в файловом менеджере, разрешить этому менеджеру установку из данного источника по запросу Android и установить Diet. При последующих обновлениях не удалять Diet: удаление стирает локальный дневник, а функции копирования/восстановления нет. Приложение не обходит системные разрешения установки.

## Состав подписанного установочного кандидата

Файл artifacts/Diet-1.0.0.apk:14219379байт (13.56MiB), app.diet/versionCode1/versionName1.0.0, min29/target37. SHA256 b62d72707dd43327c735ffad3029026bc2cea5339009af2fc24c919732168fd6. Сертификат тот же постоянный ba726a7b3396655b56a2329827bc5de3874a12d37198ba8ac64134f783d43220.

Фактический assembleRelease/full lintRelease/domain-checks прошёл за3м22с; Lint0errors/7warnings. Sign script перед чтением ключа дополнительно проверил, что оба private paths ignored/untracked. Проверка require-signature прошла вместе с9domains каждого compiled backup transport, отсутствием DirectBoot/INTERNET/broad media/camera/exact alarm, отсутствием DB/keys, starter60 и12nativeбиблиотеками на4ABI. ELF LOAD/ZIP offsets16KiB подтверждены. Наличие подписи не заменяет установку; её результаты дописываются в build-validation.

Скомпилированный app source соответствует9c72aca94bfddfc3b62a3a48dfc06dd4b310d6a2. Более поздние изменения до фиксации этого hash затронули документацию, test completion wait и проверку private signing path; Main source/ресурсы/зависимости runtime не менялись. SHA256 конечного файла важнее имени; не путать этот файл со старым rc05.
