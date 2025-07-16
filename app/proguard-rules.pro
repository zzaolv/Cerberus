# app/proguard-rules.pro

# ==========================================================
# Jetpack Compose 规则
# ==========================================================
-keep public class * extends androidx.compose.runtime.Composer
-keep public class * implements androidx.compose.runtime.Composer
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <fields>;
}
-keepclassmembernames class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ==========================================================
# LSPosed/Xposed Hook 规则
# ==========================================================
# 保留我们的Hook入口类和所有成员，防止被移除
-keep public class com.crfzit.crfzit.lsp.ProbeHook { *; }

# 保留所有Xposed的回调接口和实现类
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources

# 保留所有被Hook的系统类的方法，防止因找不到方法而Hook失败
# 使用通配符以适应不同Android版本可能的方法签名变化
-keep class com.android.server.am.ActivityManagerService { *; }
-keep class com.android.server.notification.NotificationManagerService { *; }
-keep class com.android.server.power.PowerManagerService { *; }
# ...未来可能Hook的其他系统服务...

# ==========================================================
# 依赖库规则
# ==========================================================
# 为Gson保留规则，防止用于反射的模型类被混淆
-keep public class com.crfzit.crfzit.data.model.** { *; }
-keepattributes Signature
-keepclassmembers,allowshrinking,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 为Kotlin协程保留规则
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.DefaultExecutor { *; }
-keepclassmembernames class kotlinx.coroutines.flow.internal.AbstractSharedFlowKt {
    *** EMPTY_RESUMES;
}

# 为Coil保留规则
-keepclassmembers class * extends android.view.View {
    void set(***, ***);
    *** get(***);
}
-dontwarn coil.RealImageLoader$newDiskCache$1

# ==========================================================
# Android ViewModel 规则
# ==========================================================
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}