package tech.valerochkagym.service.ai

/** Initial editable drafts, seeded only when configuration is absent. */
object PlannerPatternDefaults {
  fun collections(): List<PlannerPatternCollection> =
    listOf(
      collection(
        "strength",
        "STRENGTH",
        "Сила",
        listOf("full-a", "full-b", "upper-a", "lower-a", "upper-b", "lower-b", "push", "pull"),
      ),
      collection(
        "muscle",
        "MUSCLE_GAIN",
        "Набор мышц",
        listOf(
          "full-a",
          "full-b",
          "upper-a",
          "lower-a",
          "upper-b",
          "lower-b",
          "push",
          "pull",
          "legs",
        ),
      ),
      collection(
        "fat-loss",
        "FAT_LOSS",
        "Похудение",
        listOf("full-a", "full-b", "upper-a", "lower-a", "short"),
      ),
      collection(
        "fitness",
        "GENERAL_FITNESS",
        "Общая форма",
        listOf("full-a", "full-b", "upper-a", "lower-a", "short"),
      ),
      collection(
        "endurance",
        "ENDURANCE",
        "Выносливость",
        listOf("steady", "intervals", "long-easy", "muscular", "mixed"),
      ),
    )

  private fun collection(
    id: String,
    goal: String,
    name: String,
    variants: List<String>,
  ): PlannerPatternCollection {
    val strength = goal == "STRENGTH"
    val hypertrophy = goal == "MUSCLE_GAIN"
    val combined = goal in setOf("FAT_LOSS", "GENERAL_FITNESS")
    fun work(movement: String, accessory: Boolean = false) =
      PlannerPatternSlot(
        role = if (accessory) "ACCESSORY" else "PRIMARY",
        movement = movement,
        sets = if (strength && !accessory) 4 else if (combined) 2 else 3,
        repsMin = if (strength && !accessory) 3 else if (accessory) 8 else 6,
        repsMax = if (strength && !accessory) 6 else if (accessory) 15 else 12,
        restSeconds = if (strength && !accessory) 180 else if (accessory) 90 else 120,
      )
    fun cardio(seconds: Int, movement: String = "Равномерное кардио в разговорном темпе") =
      PlannerPatternSlot("CARDIO", movement, "CARDIO", 1, 1, 1, 1, 0, seconds)
    val patterns =
      variants.map { variant ->
        val focus =
          when {
            variant.startsWith("upper") -> "UPPER"
            variant.startsWith("lower") || variant == "legs" -> "LOWER"
            variant == "push" -> "PUSH"
            variant == "pull" -> "PULL"
            variant in listOf("steady", "intervals", "long-easy") -> "CARDIO"
            variant == "mixed" || variant == "muscular" -> "MIXED"
            else -> "FULL_BODY"
          }
        val label =
          when (variant) {
            "full-a" -> "Всё тело A"
            "full-b" -> "Всё тело B"
            "upper-a" -> "Верх A"
            "upper-b" -> "Верх B"
            "lower-a" -> "Низ A"
            "lower-b" -> "Низ B"
            "push" -> "Акцент на жим"
            "pull" -> "Акцент на тягу"
            "legs" -> "Ноги"
            "short" -> "Короткая смешанная"
            "steady" -> "Равномерное кардио"
            "intervals" -> "Интервальное кардио"
            "long-easy" -> "Длительное лёгкое кардио"
            "muscular" -> "Мышечная выносливость"
            else -> "Смешанная выносливость"
          }
        val movements =
          when (variant) {
            "full-a" ->
              listOf(
                "Коленно-доминантное движение",
                "Горизонтальный жим",
                "Горизонтальная тяга",
                "Кор",
              )
            "full-b" ->
              listOf(
                "Тазово-доминантное движение",
                "Вертикальная тяга",
                "Вертикальный жим",
                "Односторонняя работа ног",
              )
            "upper-a" ->
              listOf("Горизонтальный жим", "Горизонтальная тяга", "Вертикальная тяга", "Трицепс")
            "upper-b" ->
              listOf("Вертикальный жим", "Вертикальная тяга", "Горизонтальный жим", "Бицепс")
            "lower-a",
            "legs" ->
              listOf(
                "Коленно-доминантное движение",
                "Тазово-доминантное движение",
                "Сгибание ног",
                "Икры",
              )
            "lower-b" ->
              listOf(
                "Тазово-доминантное движение",
                "Односторонняя работа ног",
                "Разгибание ног",
                "Кор",
              )
            "push" ->
              listOf(
                "Акцентное жимовое упражнение",
                "Дополнительный жим",
                "Средние дельты",
                "Трицепс",
              )
            "pull" ->
              listOf(
                "Акцентное тяговое упражнение",
                "Тяга в другой плоскости",
                "Задние дельты",
                "Бицепс",
              )
            "short",
            "muscular",
            "mixed" -> listOf("Приседательное движение", "Жим", "Тяга")
            else -> emptyList()
          }
        val slots =
          movements.mapIndexed { index, movement -> work(movement, index >= 2) }.toMutableList()
        if (variant == "muscular") {
          slots.replaceAll { it.copy(sets = 2, repsMin = 12, repsMax = 20, restSeconds = 60) }
        }
        if (combined) slots += cardio(if (variant == "short") 480 else 900)
        when (variant) {
          "steady" -> slots += cardio(1800)
          "long-easy" -> slots += cardio(2700, "Лёгкое непрерывное кардио в разговорном темпе")
          "intervals" -> {
            slots += cardio(300, "Лёгкая разминка")
            slots +=
              PlannerPatternSlot(
                "INTERVAL",
                "Контролируемый интенсивный интервал; без обязательного максимального усилия",
                "CARDIO",
                1,
                6,
                1,
                1,
                120,
                60,
              )
            slots += cardio(300, "Лёгкая заминка")
          }
          "mixed" -> slots += cardio(1200)
        }
        PlannerPattern(
          "$id-$variant",
          label,
          focus,
          "Редактируемая заготовка. " +
            (if (hypertrophy) "Распредели рабочий объём по истории. " else "") +
            "Сохрани подходящие акцентные упражнения; адаптируй состав и объём под время, оснащение, опыт и выполненные занятия.",
          slots,
        )
      }
    return PlannerPatternCollection(id, goal, name, patterns)
  }
}
