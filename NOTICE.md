# Litematica Material HUD

Copyright (c) 2026 Aell_

Лицензия: GNU General Public License версии 3 или любой более поздней (GPL-3.0-or-later), полный текст — `LICENSE`.

Неофициальный аддон к Litematica и malilib: не связан с их авторами (masa, Sakura-Ryoko) и не одобрен ими.
Не является официальным продуктом Minecraft, не одобрен Mojang и Microsoft и не связан с ними.

## Зависимости

- **Litematica** и **malilib** (masa; сборки для 26.x выпускает Sakura-Ryoko) — LGPL-3.0. В мод не входят и
  ставятся отдельно: мод вызывает их API и подключает свои миксины во время работы игры, их код и ресурсы в сборку
  не включены.
- **Voxy** (Cortex) — All Rights Reserved. Не обязателен и в мод не входит: если Voxy стоит отдельно или вложен в
  другой мод (например, VoxyMap), 3D-превью вкладки «Выделение» рисуется его движком — мод вызывает его классы и
  подключает свои миксины во время работы игры. Код Voxy в сборку не включён; без Voxy превью рисует сам мод.

## Встроенный код

- **aell-ui** (Aell_) — MIT. Общий модуль экрана настроек, исходники лежат в `../aell-ui`. При сборке он
  переносится в пакет `com.aell.layerlist.shaded.ui`, чтобы два мода с этим модулем не делили одинаковые классы в
  одинаковом пакете.
- **VoxyMap LTS** (LTS_Server, https://github.com/raben12paste/3D-maps-Voxy-Addon) — MIT, © 2026 raben12paste.
  Отрисовка 3D-превью «Выделения» отдельным видом Voxy (`compat/VoxyPreviewImpl`, миксины `mixin/voxy`) перенесена
  из его окна 3D-карты (`WindowMapRenderer`) с изменениями; текст лицензии — `LICENSE_VoxyMap`.

## Встроенные шрифты

- **Press Start 2P** (© 2012 The Press Start 2P Project Authors, Reserved Font Name «Press Start 2P») и
  **JetBrains Mono** (© 2020 The JetBrains Mono Project Authors) — SIL Open Font License 1.1. Лежат в
  `assets/layerlist/fonts/` вместе с текстом лицензии (`OFL.txt`) и используются только для картинки-выгрузки
  списка материалов (PNG). Шрифты не изменялись.

## Идеи функций

- Тумблеры «Неровный темп», «Не копать лишнего», «Брать инструмент для копания» и «Заменять почву» (с 0.0.20)
  повторяют возможности форка **EasyPlaceFix** 0.6.5–0.6.6 (ServChan, https://github.com/ServChan/EasyPlaceFix;
  на Modrinth — Apache-2.0, в репозитории — MIT, © 2024 uiop). Реализация своя: в 0.0.26 выбор инструмента
  (`ToolPick`) переписан заново, сходство с кодом форка проверено токенным сравнением и находится на уровне общих
  идиом Java.
- Вкладка «Схемы» (с 0.0.26) — идеи навеяны модами **SchematicPreview** (DimasKama) и **LitematicaPLUS**
  (LTS_Server). Независимая реализация: код и графика не заимствованы.
