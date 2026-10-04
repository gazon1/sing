---
title: "Эмулятор падает с SIGSEGV в gfxstream при создании ColorBuffer (триггер — soft IME)"
date: 2026-09-28
status: accepted
status-was: resolved  # non-vocabulary value, normalized 2026-10-05
---

> **Control run added (2026-09-29).** The IME mitigation below is **insufficient** and
> the crash has a wider trigger than the soft keyboard. `scripts/ensure-emulator.sh`
> is now the recovery path. See
> [2026-09-29-emulator-crash-recovery-runner.md](2026-09-29-emulator-crash-recovery-runner.md)
> for the full control experiment; this entry's diagnosis of the stack still holds.

# Эмулятор падает с SIGSEGV в gfxstream при создании ColorBuffer

## Context

Во время прогона Maestro-flows эмулятор дважды за сессию исчезал: `adb: device
not found` / `device offline`. Раньше это выглядело как «эмулятор нестабилен»,
и вина была возложена на регрессию Mesa из
`2026-09-28-emulator-mesa-radeon-cs-rejected.md`. **Это был неверный вывод** —
см. «Что это не было».

`coredumpctl` показал два SIGSEGV за вечер (22:22:53 и 22:45:01), оба с
coredump'ом ~1 ГБ — то есть падал именно процесс эмулятора.

## Root cause

Стек из coredump'а PID 568807:

```
#0  __strlen_avx2 () from /lib64/libc.so.6
#1  gfxstream::host::gl::TextureResize::TextureResize(unsigned int, unsigned int)
      from .../emulator/lib64/libgfxstream_backend.so
#2  gfxstream::host::gl::ColorBufferGl::create(...)
#3  gfxstream::host::gl::EmulationGl::createColorBuffer(unsigned int, unsigned int)
#4  gfxstream::host::ColorBuffer::Impl::create(...)
#6  gfxstream::host::FrameBuffer::Impl::createColorBufferWithResourceHandleLocked(...)
#7  gfxstream::host::FrameBuffer::Impl::createColorBuffer(int, int)
#8  gfxstream::renderControl_decoder_context_t::decode(...)
#9  gfxstream::host::RenderThread::main()
```

Гость просит хост создать **новый color buffer** (то есть новую поверхность), и
внутри `TextureResize` эмулятор разыменовывает битый указатель — `__strlen_avx2`
падает на мусоре.

Триггер — **появление soft-клавиатуры**: каждая панель IME создаёт новую
поверхность, а значит новый ColorBuffer. Оба вечерних падения произошли во время
прогона flow'ов, которые вводят текст (`tasks/create-task`).

## Что это не было

- **Не регрессия Mesa/kernel.** ADR `2026-09-28-emulator-mesa-radeon-cs-rejected`
  описывает `amdgpu: The CS has been rejected (-22)` и `VK_ERROR_DEVICE_LOST` —
  это другое состояние, и оно уже исправлено обновлением до Mesa 26.2.3 +
  kernel 7.2.7. В `dmesg` этих сообщений больше нет.
- **Не OOM.** 62 ГБ RAM, 41 ГБ доступно.
- **Не снапшот.** Падения воспроизводились на холодном старте с
  `-no-snapshot-save`.

## Decision

Отключить soft-клавиатуру на время прогона UI-тестов:

```bash
adb -s "$SERIAL" shell settings put secure show_ime_with_hard_keyboard 0
```

Вшито в `scripts/run-maestro.sh` (шаг 2b, сразу после определения устройства),
поэтому включается автоматически при любом запуске flow'ов.

## Rationale

Софтверный рендеринг (`-gpu swiftshader_indirect`) убирает из уравнения
`libgfxstream_backend` целиком и был бы полноценным лечением, но на практике не
применим: после удаления снапшота (смена рендерера требует холодного старта —
см. ADR про снапшоты) эмулятор на swiftshader не завершил загрузку за 8+ минут
и не появился в adb. Для UI-тестов это неприемлемо.

Отключение IME убирает именно триггер, а не симптом, и не меняет поведение
приложения: с аппаратной клавиатурой поля ввода работают как обычно, `inputText`
Maestro продолжает вводить текст. Плата — нельзя тестировать поведение,
специфичное для экранной клавиатуры (imeAction, проверка composition).

## Verification

С отключённым IME, host-GPU, эмулятор `emulator-5554`:

| Что | Результат |
|---|---|
| 3 прогона `tasks/create-task` подряд (ввод текста) | эмулятор жив все 3 раза |
| полный smoke-набор (6 flow'ов, включая ввод текста) | эмулятор жив |

До включения mitigation: падение на 1-м или 2-м прогоне с вводом текста
(2 SIGSEGV за вечер).

Контрольного прогона с включённым IME в этой же сессии не делалось — к моменту,
когда гипотеза сложилась, павшие эмуляторы уже были перезапущены. Причинность
опирается на стек coredump'а (создание ColorBuffer) и на совпадение падений по
времени с прогонами, вводящими текст.

## Consequences

- Скриншотные тесты, требующие IME, придётся запускать с явным включением
  клавиатуры — и принимать риск падения.
- Настоящее лечение — обновление Android Emulator, когда в
  `TextureResize` исправят разыменование. Проверить версию:
  `emulator -version`.
- `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md` описывает
  **другую** проблему; при чтении ADR'ов важно не путать их.

## Links

- `scripts/run-maestro.sh` — шаг 2b
- ADR про снапшоты и смену рендерера: `2026-09-28-emulator-mesa-radeon-cs-rejected.md`
