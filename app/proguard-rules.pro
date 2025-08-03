-keep public class com.crfzit.crfzit.lsp.ProbeHook {
    public <init>();
}

-keep class com.crfzit.crfzit.** {*;}

-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

-keep class com.crfzit.crfzit.data.model.** { *; }

-keep @com.google.gson.annotations.SerializedName class *

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.jvm.internal.DebugMetadataKt { *; }
-keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    private java.lang.Object[] a;
    private int b;
}

-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}

-keep class coil.** { *; }