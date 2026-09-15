package tech.valerochkagym.controller.publicweb

import tech.valerochkagym.controller.model.RoutineSharePreview

internal object RoutineShareHtml {
  const val contentSecurityPolicy =
    "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
  const val latestApkUrl = "https://github.com/Valerochka1337/YarumoGymAndroid/releases/latest"

  fun page(preview: RoutineSharePreview): String =
    """
    <!doctype html>
    <html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${escape(preview.title)} · Yarumo</title>
    <style>body{margin:0;background:#f8f7fb;color:#1d1b20;font:16px system-ui,sans-serif}main{max-width:680px;margin:auto;padding:24px}article{background:#fff;border-radius:24px;padding:24px;box-shadow:0 2px 12px #0001}h1{font-size:clamp(1.7rem,7vw,2.5rem);overflow-wrap:anywhere;margin:0 0 8px}.muted{color:#625b71}.exercise{border-top:1px solid #e8e0ec;padding:16px 0}.exercise:last-of-type{padding-bottom:0}a{display:inline-block;margin-top:24px;background:#6750a4;color:#fff;padding:14px 18px;border-radius:999px;text-decoration:none;font-weight:700}</style>
    </head><body><main><article><p class="muted">Yarumo · программа тренировки</p><h1>${escape(preview.title)}</h1><p class="muted">Примерно ${preview.estimatedDurationSeconds / 60} мин.</p>
    ${preview.exercises.joinToString("") { exercise -> "<section class=\"exercise\"><strong>${escape(exercise.name)}</strong><p class=\"muted\">${escape(exercise.type)} · ${exercise.sets.size} подх. · отдых ${exercise.restSeconds} с</p>${exercise.sets.mapIndexed { index, set -> "<p>Подход ${index + 1}: ${setDescription(set)}</p>" }.joinToString("")}</section>" }}
    <a href="$latestApkUrl">Скачать Yarumo для Android</a><p class="muted">После установки снова откройте эту ссылку, чтобы сохранить программу себе.</p></article></main></body></html>
    """
      .trimIndent()

  fun unavailable(): String =
    """
    <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Ссылка недоступна · Yarumo</title>
    <style>body{margin:0;display:grid;min-height:100vh;place-items:center;background:#f8f7fb;color:#1d1b20;font:16px system-ui,sans-serif}main{max-width:34rem;padding:24px;text-align:center}</style>
    </head><body><main><h1>Ссылка недоступна</h1><p>Возможно, автор отключил её.</p></main></body></html>
    """
      .trimIndent()

  private fun escape(value: String): String =
    buildString(value.length) {
      value.forEach {
        append(
          when (it) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '\"' -> "&quot;"
            '\'' -> "&#39;"
            else -> it
          }
        )
      }
    }

  private fun setDescription(
    set: tech.valerochkagym.controller.model.RoutineSharePreviewSet
  ): String =
    listOfNotNull(
        set.weightKg?.let { "$it кг" },
        set.reps?.let { "$it повторов" },
        set.durationSec?.let { "$it с" },
        set.speedKmh?.let { "$it км/ч" },
        set.inclinePct?.let { "наклон $it%" },
      )
      .joinToString(" · ")
      .ifBlank { "без заданных параметров" }
}
