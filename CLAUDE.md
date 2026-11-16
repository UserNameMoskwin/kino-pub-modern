# moskwin-pub

Личный клиент kino.pub для Android TV. Требования и принятые решения — `docs/spec.md`,
читать перед любой работой. Официальная документация API — `docs/kinoapi/`.

- Общаться с владельцем на русском.
- Код — в `source/` (там же `gradlew`). Проверка ядра: `source\gradlew :core:test` из папки
  `source` (JDK 17–21 в `JAVA_HOME`). Выпуск версии — `device\release.ps1`, см. `docs/development.md`.
- Значение `client_secret` не выводить в логи и не вставлять в документацию.
