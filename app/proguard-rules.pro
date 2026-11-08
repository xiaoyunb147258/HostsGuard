# 保留 JS 桥方法（@JavascriptInterface 标注的）
-keepclassmembers class com.acelite.hosts.MainActivity$Bridge {
    public *;
}

-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.** { *; }

# 本项目
-keep class com.acelite.hosts.** { *; }