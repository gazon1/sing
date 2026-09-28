---
title: Emulator crash on Renoir — Mesa 25.3.6 + kernel 6.17 regression
date: 2026-09-28
status: resolved
---

# Emulator crash on Renoir — Mesa 25.3.6 + kernel 6.17 regression

## Context

На dev-хосте (AMD Ryzen 7 5700G / Renoir APU) Android-эмулятор не стартовал:
процесс падал через 15–45 секунд после запуска, до загрузки гостя.

```
INFO  | Graphics Adapter Android Emulator OpenGL ES Translator
      | (AMD Radeon Graphics (radeonsi, renoir, ACO, DRM 3.64, 6.17.1-300.fc43.x86_64))
WARNING | adb command 'adb -s emulator-5554 shell getprop sys.boot_completed' failed: 'adb: device offline'
amdgpu: The CS has been rejected, see dmesg for more information (-22).
emulator: IOT instruction (core dumped)
```

По `coredumpctl` падения шли непрерывно с 2026-09-03 — с первого дня после
установки эмулятора. На этой машине он не работал ни разу.

## Root cause

Регрессия связки **Mesa + ядро** на Renoir, а не настройка проекта и не AVD.
Сломаны оба драйвера Mesa:

- `radeonsi` (OpenGL) — падал при создании GL-контекста эмулятора, то есть
  сразу на старте;
- `radv` (Vulkan) — падал при первой GPU-нагрузке из гостя:

```
INFO    | Created VkDevice:... for application:'Chromium'
radv/amdgpu: The CS has been rejected, see dmesg for more information (-22).
WARNING | dispatchVkQueueSubmit failed: VK_ERROR_DEVICE_LOST [-4]
FATAL   | Encountered device lost.
```

Ядро отвергало command stream'ы с `-22` (`EINVAL`).

## What was ruled out

Проверялось экспериментально, а не предполагалось:

| Вариант | Результат |
|---|---|
| `-gpu swiftshader_indirect` | SIGSEGV в `gles_swiftshader/libGLESv2.so` |
| `-gpu off` | SIGSEGV, то же место |
| `-gpu host` (по умолчанию) | SIGILL, `IOT instruction` + `CS rejected` |
| `-accel off` (TCG вместо KVM) | SIGSEGV — KVM не при чём |
| `-no-window` / `QT_QPA_PLATFORM=offscreen` | SIGSEGV раньше создания окна — Qt/X11/Wayland не при чём |
| Свежий AVD на `android-36.1` | SIGSEGV ещё раньше — AVD не повреждён |
| Эмулятор 37.1.11 → 37.3.1 | Падают в одном кадре стека — не баг версии |
| `VK_LOADER_LAYERS_DISABLE=VK_LAYER_MESA_device_select` | Без эффекта |
| `VK_DRIVER_FILES` на lavapipe | Эмулятор отвергает host-lavapipe (см. ниже) |
| Лимиты памяти, glibc | В норме / совместимо |

## Resolution

Регрессия ушла при обновлении системы до **Fedora 44**:
`mesa-26.2.3-1.fc44` + `kernel-7.2.7-200.fc44`. Раньше стояло
`mesa-25.3.6-3.fc43` + `kernel-6.17.1-300.fc43` — первые сборки Fedora 43.

Mesa 25.3.6 была последней в репозиториях fc43, поэтому обновления только Mesa
не помогло бы — помогло именно обновление ядра, попавшее в связку с новой Mesa.

Эмулятор обновлён 37.1.11 → 37.3.1 (build 16373887) — попутно, к багу отношения
не имело, но оставляет установку актуальной.

## Проверка (после решения)

```
Boot completed in 18746 ms
INFO | Created VkDevice:... for application:'Chromium' instance:0x...
```

- `amdgpu: CS has been rejected` — 0
- `VK_ERROR_DEVICE_LOST` / `Encountered device lost` — 0
- Chromium открывает и рендерит страницы, скролл работает
- Аппаратный GPU: `Selecting Vulkan device: AMD Radeon Graphics (RADV RENOIR)`,
  `gles_mode_selected:host`

## Dead ends (чтобы не повторять)

**`VK_DRIVER_FILES` / `VK_ICD_FILENAMES` на lavapipe.** Эмулятор host-lavapipe
не поддерживает в принципе:

```
WARNING | hasSufficientHostVulkanDriver: host lavapipe is not supported.
INFO    | Host Vulkan driver is not supported.
INFO    | initIcdPaths: ICD set to 'lavapipe', using Lavapipe ICD
```

Он подменяет ICD своим встроенным через `initIcdPaths`, так что задать свой
путь бессмысленно; с этой переменной эмулятор падал с segfault ещё до загрузки
госта — строго хуже, чем без неё. Вывод: не переключать то, что инструмент
явно объявляет неподдерживаемым.

**`MESA_LOADER_DRIVER_OVERRIDE=llvmpipe`.` дал обманчивый результат:** эмулятор
стартовал и загружал систему, что легко принять за решение — но Chromium
терял устройство. «Эмулятор перестал падать» и «эмулятор работает» — разные
утверждения; проверять надо под нагрузкой, а не по факту загрузки.

**Обёртка `~/bin/android-emulator` удалена.** После обновления системы она не
только не нужна, но и вредит: форсит программный llvmpipe и замедляет
рендеринг. Запускать штатным `emulator`.

## Сопутствующая ловушка: снапшот

`~/.android/avd/Medium_Phone.avd/snapshots/default_boot` был сохранён на
программном рендере и затем загружен на radeonsi. Восстановление GPU-состояния
через смену рендерера давало лавину ошибок и в итоге SIGABRT:

```
WARNING      | Change of GLES renderer detected.
WARNING      | Failed to load snapshot 'default_boot'
ERROR        | eglMakeCurrent failed
ERROR        | 0x...: Draw context is NULL      (×20+)
→ SIGABRT
```

Лечится удалением снапшота (`rm -rf .../snapshots/default_boot`, 2.5 ГБ) и
холодным стартом. **Общий принцип:** при смене рендерера, драйвера или версии
ОС снапшот надо выбрасывать — иначе GPU-состояние восстанавливается не из того
контекста.

## Known benign log noise

На штатном запуске остаются безобидные сообщения:

| Сообщение | Что это |
|---|---|
| `WARNING \| Could not find the Qt platform plugin "wayland"` | В поставке эмулятора нет wayland-плагина, работает через XWayland |
| `WARNING \| File System is not ext4, disable QuickbootFileBacked` | ФС хоста не ext4, быстрый старт с файловым бэкапом отключён |
| `WARNING: cannnot unmap ptr ... protected range` | Шум внутреннего аллокатора эмулятора |
| `ERROR \| Failed to find ColorBuffer` / `bad color buffer handle` | Разовое, на старте, на результат не влияет |
| `ERROR \| Unable to connect to adb daemon on port: 5037` | adb-сервер не был запущен; чинится `adb start-server` перед стартом |
| `WARNING \| Failed to create process resource for puid N` | Шум учёта процессов гостя |

## adb авторизация

На свежем AVD гостевой `adbd` показывает диалог «Allow USB debugging?» и
устройство висит в `unauthorized`. Флаги `-prop ro.adb.secure=0` и
`-qemu -append androidboot.adb.secure=0` не помогают.

Автоматическое внедрение ключа в образ **невозможно**: `/data` в Play
Store-образе зашифрован (FBE). Проверено — по смещению 1080 вместо магии ext4
`53 EF` лежит `83 62 ab bb`, суперблок не ищется, начало образа
высокоэнтропийный шум, в AVD есть `encryptionkey.img`. `debugfs` не увидит
`/data/misc/adb/adb_keys` даже под root.

Достаточно одного клика «Allow» — после этого ключ сохраняется в AVD навсегда.

## Consequences

- **Эмулятор работает штатно**, на аппаратном GPU. Никаких обёрток и
  переменных окружения не требуется.
- **CI не затронут**: `.github/workflows/ci.yml` эмулятор не поднимает.
- **Сторонние модули**: переход ядра 6.17 → 7.2 ломает модули, собранные под
  старое ядро. Возможна потребность в пересборке/переустановке (например,
  AmneziaVPN). Откат — выбор старого ядра в GRUB.
- **Гипотеза на будущее:** если `amdgpu: CS has been rejected` вернётся после
  обновления Mesa, чинить надо ядро, а не Mesa — проверено на этой машине.

## Reverting

Ничего откатывать не нужно. Для чистоты можно удалить старый билд эмулятора:
`rm -rf ~/Android/Sdk/emulator.bak-37.1.11` (821 МБ).

## Verification

```bash
rpm -q mesa-dri-drivers mesa-vulkan-drivers kernel-core   # Mesa 26.2.3, ядро 7.2.7
emulator -avd Medium_Phone
adb wait-for-device
adb shell getprop sys.boot_completed                      # -> 1
adb shell monkey -p com.android.chrome -c android.intent.category.LAUNCHER 1
# в логе не должно быть: CS has been rejected / Encountered device lost
```
