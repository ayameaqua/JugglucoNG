package tk.glucodata.healthinsights

enum class HealthKind(val kind: String, val label: String, val defaultSelected: Boolean, val separateOptIn: Boolean) {
    SLEEP("sleep", "睡眠及阶段", true, false),
    STEPS("steps", "步数", true, false),
    HEART_RATE("heart_rate", "心率", true, false),
    EXERCISE("exercise", "运动", true, false),
    EXERCISE_LOCATION("exercise_route", "运动位置", false, true),
    SKIN_TEMPERATURE("skin_temperature", "皮肤温度", true, false),
    BLOOD_OXYGEN("blood_oxygen", "血氧", true, false),
    ACTIVITY_SUMMARY("activity_summary", "活动汇总", false, false),
    FLOORS_CLIMBED("floors_climbed", "爬楼层数", false, false),
    BLOOD_GLUCOSE("blood_glucose", "三星血糖", false, false),
    BLOOD_PRESSURE("blood_pressure", "血压", false, false),
    BODY_COMPOSITION("body_composition", "身体成分", false, false),
    SLEEP_GOAL("sleep_goal", "睡眠目标", false, false),
    STEPS_GOAL("steps_goal", "步数目标", false, false),
    ACTIVE_CALORIES_BURNED_GOAL("active_calories_burned_goal", "活动热量目标", false, false),
    ACTIVE_TIME_GOAL("active_time_goal", "活动时长目标", false, false),
    WATER_INTAKE("water_intake", "饮水", false, false),
    WATER_INTAKE_GOAL("water_intake_goal", "饮水目标", false, false),
    NUTRITION("nutrition", "营养摄入", false, false),
    NUTRITION_GOAL("nutrition_goal", "营养目标", false, false),
    ENERGY_SCORE("energy_score", "能量得分", false, false),
    USER_PROFILE("user_profile", "个人资料", false, true),
    SLEEP_APNEA("sleep_apnea", "睡眠呼吸暂停检测记录", false, false),
    IRREGULAR_HEART_RHYTHM_NOTIFICATION("irregular_heart_rhythm_notification", "心律不齐通知", false, false),
    BODY_TEMPERATURE("body_temperature", "体温", false, false);
    companion object { val defaults = entries.filter { it.defaultSelected }.toSet() }
}
