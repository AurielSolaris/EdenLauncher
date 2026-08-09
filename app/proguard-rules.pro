# Views inflated by name from XML need their (Context, AttributeSet) constructor kept.
-keepclasseswithmembers class app.auriel.edenlauncher.views.** {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class app.auriel.edenlauncher.allapps.** {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class app.auriel.edenlauncher.folder.** {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Room looks up its generated implementation by name at runtime.
-keep class app.auriel.edenlauncher.data.EdenDatabase_Impl { *; }

-dontwarn kotlinx.coroutines.**
