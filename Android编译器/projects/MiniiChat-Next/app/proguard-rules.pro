# 纯 Kotlin 工程混淆规则（默认保留 AppCompat 所需元数据）
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**