# ===================================================================
# ProGuard / R8 Rules for Project Cerberus (v5 - Final & Precise)
# ===================================================================

# -----------------
# 1. Xposed Hook
# -----------------
# [关键] 保护 Xposed Hook 入口类，防止其被混淆或移除。
-keep public class com.crfzit.crfzit.lsp.ProbeHook {
    public <init>();
}
# 保留Xposed API，以防万一
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }


# -----------------
# 2. GSON & Serialization
# -----------------
# [关键] 保护所有用于 GSON (或任何基于反射的序列化库) 的数据模型。
# 这会保留类、所有字段、所有方法和所有构造函数。
-keep class com.crfzit.crfzit.data.model.** { *; }

# [关键] 明确保留 GSON 使用的注解，确保它们在编译后依然存在。
-keep @com.google.gson.annotations.SerializedName class *

# [关键] 保留 GSON 内部用于反射的类和成员。
# 这可以防止 GSON 本身在处理复杂类型（如泛型）时出错。
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 保持 Parcelable 实现的完整性，如果您的模型类是 Parcelable 的话。
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}


# -----------------
# 3. Kotlin & Coroutines
# -----------------
# 这些是维护 Kotlin 元数据和协程功能的标准规则。
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.jvm.internal.DebugMetadataKt { *; }
-keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    private java.lang.Object[] a;
    private int b;
}


# -----------------
# 4. Jetpack Compose
# -----------------
# Compose 运行时需要的一些规则
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}


# -----------------
# 5. 其他库 (如果需要)
# -----------------
# Coil - 通常不需要特定规则，但如果遇到问题可以取消注释
# -keep class coil.** { *; }