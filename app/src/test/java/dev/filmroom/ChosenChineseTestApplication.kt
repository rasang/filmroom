package dev.filmroom

/** Legacy UI fixtures exercise editing, not onboarding. Locale tests opt into the real app. */
class ChosenChineseTestApplication : FilmroomApplication() {
    override fun onCreate() {
        super.onCreate()
        dev.filmroom.i18n.L10n.choose(this, "zh")
    }
}
