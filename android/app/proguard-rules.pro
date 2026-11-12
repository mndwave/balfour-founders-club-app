# R8 rules for the release build (R8-BALFOUR-2026-11-12, healer:8ab059db; same rules as Randalls Rewards healer:cc015830).
# Capacitor (@capacitor/android) and its plugins ship consumer rules that keep every Plugin subclass
# and its @PluginMethod/@PermissionCallback/@ActivityCallback methods by name: the bridge looks them up
# reflectively from capacitor.plugins.json. Manifest components (MainActivity and any messaging service)
# are kept by AAPT's generated rules. Only what those cannot know about is here.

# Methods called from JavaScript by name (LoyaltyShell and CardPage bridges).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Readable Play crash reports: Play applies the mapping.txt inside the AAB; keep line numbers for it
# and hide the source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
